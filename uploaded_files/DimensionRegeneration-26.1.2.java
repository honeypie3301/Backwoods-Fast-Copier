package net.mcreator.thebackwoods;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.network.FriendlyByteBuf;
import io.netty.buffer.Unpooled;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDestroyBlockEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.block.BreakBlockEvent;
import net.neoforged.neoforge.event.level.ExplosionEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Loaded-chunk terrain memory and gradual Lignum Caro wound repair.
 * Import with the accompanying procedure exports; no DimensionDefense changes.
 * All world reads/writes are on the server thread. Disk tasks contain only detached data.
 * Settings are private, immutable source constants. No configuration file is created.
 */
@EventBusSubscriber
public final class DimensionRegeneration {
    private static final int RADIUS_BLOCKS = 128;
    private static final long TICK_BUDGET_NANOS = 2_000_000L;
    private static final long QUIET_BUDGET_NANOS = 100_000L, RECORDING_BUDGET_NANOS = 750_000L;
    private static final int SECTION_CHECK_INTERVAL = 20, MAX_SECTION_CHECKS_PER_TICK = 128;
    private static final int MAX_COMPARISONS_PER_TICK = 32_768;
    // Source-only speed controls. A continuous per-tick quota; unused quota never accumulates.
    private static final int BASE_PLACEMENTS_PER_TICK = 12, PEAK_PLACEMENTS_PER_TICK = 12;
    private static final int ACCELERATION_INTERVAL_TICKS = 200;
    private static final int NETWORK_MIN_TICKS = 600, NETWORK_FALLBACK_TICKS = 2400;
    private static final int STRAND_IDLE_TIMEOUT_TICKS = 200;
    private static final int MAX_PLACEMENTS_PER_TICK = 12;
    private static final long SNAPSHOT_CACHE_BYTES = 256L * 1024 * 1024;
    private static final int MAX_WOUND_CELLS = 131_072;
    private static final int HEAL_DELAY_TICKS = 100;
    private static final List<Integer> GROWTH_INTERVAL_TICKS = List.of(10, 16, 24, 34);
    private static final int TENDON_LIFETIME_TICKS = 240;
    private static final int MAX_TENDON_RADIUS = 2, RETRACTION_INTERVAL_TICKS = 2;
    private static final List<Integer> DAMAGE_THRESHOLDS = List.of(64, 512, 4096);
    private static final List<Integer> TENDON_DIAMETERS = List.of(1, 3, 5, 7);
    private static final String TENDON_BLOCK = "the_backwoods:lignum_caro";
    private static final boolean LOG_METRICS = false;
    private static final int MAX_DETOUR_DISTANCE = 48, MAX_DETOUR_NODES = 4096;
    private static final int MAX_DETOUR_JOBS = 16, MAX_DETOUR_LENGTH = 192;
    private static final int MAX_SPAN_DISTANCE = 256, MAX_ROUTE_EXTRA_CELLS = 32768;
    private static final int MAX_SUPPORT_NODES = 1024, MAX_WOUND_SPANS = 48;
    private static final long MAX_ROUTE_TICKS = 20L*60*20;
    private static final Set<String> DIMENSIONS = Set.of(
            "the_backwoods:backwoods", "the_backwoods:the_grain",
            "the_backwoods:the_sub_strata", "the_backwoods:the_crossworks");
    private static final TagKey<EntityType<?>> WOODBOUND = TagKey.create(
            Registries.ENTITY_TYPE, Identifier.parse("the_backwoods:woodbound_entities"));
    private static final org.slf4j.Logger LOG = com.mojang.logging.LogUtils.getLogger();
    private static final Map<MinecraftServer, Runtime> SERVERS = new IdentityHashMap<>();
    private static final Direction[] DIRECTIONS = Direction.values();
    private static final int FORMAT = 6, QUANTUM = 256, MAX_IO = 8;
    private DimensionRegeneration() {}

    public static int tendonDiameter(int count) {
        int tier = 0;
        while (tier < DAMAGE_THRESHOLDS.size() && count >= DAMAGE_THRESHOLDS.get(tier)) tier++;
        return TENDON_DIAMETERS.get(tier);
    }
    public static int tendonGrowthInterval(int diameter) {
        int tier=0;
        while(tier+1<TENDON_DIAMETERS.size()&&diameter>=TENDON_DIAMETERS.get(tier+1))tier++;
        return GROWTH_INTERVAL_TICKS.get(tier);
    }
    private static void validateConfiguration() {
        if(RADIUS_BLOCKS<0||RADIUS_BLOCKS>512||TICK_BUDGET_NANOS<=0||MAX_COMPARISONS_PER_TICK<1
                ||MAX_PLACEMENTS_PER_TICK<1||SNAPSHOT_CACHE_BYTES<1_048_576||MAX_WOUND_CELLS<1
                ||HEAL_DELAY_TICKS<0||TENDON_LIFETIME_TICKS<40
                ||DAMAGE_THRESHOLDS.size()+1!=TENDON_DIAMETERS.size()||GROWTH_INTERVAL_TICKS.size()!=TENDON_DIAMETERS.size())
            throw new IllegalArgumentException("Invalid DimensionRegeneration configuration");
        int previous=0;
        for(int threshold:DAMAGE_THRESHOLDS){if(threshold<=previous)throw new IllegalArgumentException("Damage thresholds must increase");previous=threshold;}
        previous=0;
        for(int diameter:TENDON_DIAMETERS){if(diameter<previous||diameter<1||diameter>15||(diameter&1)==0)throw new IllegalArgumentException("Tendon diameters must be increasing odd values from 1 to 15");previous=diameter;}
        previous=0;
        for(int interval:GROWTH_INTERVAL_TICKS){if(interval<=previous)throw new IllegalArgumentException("Larger tendons must extend more slowly");previous=interval;}
    }
    /** Coherent asymmetry follows the strand rather than flickering between growth ticks. */
    public static boolean organicCrossSection(long seed,int along,int u,int v,int radius) {
        if(radius==0)return u==0&&v==0;
        double phase=(seed&1023)*0.013, t=along*0.37+phase;
        double offsetU=Math.sin(t)*0.40,offsetV=Math.cos(t*0.79+1.4)*0.40;
        double du=u-offsetU,dv=v-offsetV,angle=Math.atan2(dv,du);
        double edge=radius-0.12+0.28*Math.sin(angle*3.0+t*0.83)+0.12*Math.cos(angle*5.0-t*0.47);
        return du*du+dv*dv<=edge*edge;
    }
    public static Direction extensionDirection(Direction forward,long seed,int depth) {
        // Branch planning supplies curvature; do not inject periodic right-angle turns into a strand.
        return forward;
    }
    /** Timber-beam-style end-to-end centreline, with sag and a tapered lateral wave. */
    public static List<BlockPos> beamPath(BlockPos from,BlockPos to,long seed) {
        ArrayList<BlockPos> path=new ArrayList<>();BlockPos previous=from;
        Map<BlockPos,Integer> indices=new HashMap<>();indices.put(from,-1);
        int dx=to.getX()-from.getX(),dy=to.getY()-from.getY(),dz=to.getZ()-from.getZ();
        double length=Math.sqrt((double)dx*dx+(double)dy*dy+(double)dz*dz);
        int samples=Math.max(1,(int)Math.ceil(length*2));double phase=(seed&1023)*0.013;
        for(int sample=1;sample<=samples;sample++) {
            double t=(double)sample/samples,envelope=Math.sin(Math.PI*t);
            double horizontal=Math.hypot(dx,dz),sideX=horizontal<0.01?1:-dz/horizontal,sideZ=horizontal<0.01?0:dx/horizontal;
            double a=Math.sin(phase+0.7)*Math.min(14,length*0.14),b=Math.cos(phase*0.83+1.1)*Math.min(12,length*0.12);
            double side=3*(1-t)*(1-t)*t*a+3*(1-t)*t*t*b+(Math.sin(t*Math.PI*4+phase)*0.7+Math.sin(length*t*0.12+phase)*0.55)*envelope;
            double arch=3*(1-t)*(1-t)*t*Math.sin(phase*0.7)*Math.min(10,length*0.1)
                    +3*(1-t)*t*t*Math.cos(phase*1.3)*Math.min(8,length*0.08)-Math.min(4,length*0.04)*envelope+Math.sin(length*t*0.095+phase*0.7)*envelope*0.45;
            double progress=3*(1-t)*(1-t)*t*0.28+3*(1-t)*t*t*0.72+t*t*t;
            BlockPos target=sample==samples?to:new BlockPos((int)Math.round(from.getX()+dx*progress+sideX*side),
                    (int)Math.round(from.getY()+dy*progress+arch),(int)Math.round(from.getZ()+dz*progress+sideZ*side));
            while(!previous.equals(target)) {
                int x=target.getX()-previous.getX(),y=target.getY()-previous.getY(),z=target.getZ()-previous.getZ();
                if(Math.abs(x)>=Math.abs(y)&&Math.abs(x)>=Math.abs(z)&&x!=0)previous=previous.offset(Integer.signum(x),0,0);
                else if(Math.abs(y)>=Math.abs(z)&&y!=0)previous=previous.offset(0,Integer.signum(y),0);
                else previous=previous.offset(0,0,Integer.signum(z));
                Integer earlier=indices.get(previous);
                if(earlier!=null){while(path.size()>earlier+1)indices.remove(path.remove(path.size()-1));}
                else {indices.put(previous,path.size());path.add(previous);}
            }
        }
        return List.copyOf(path);
    }
    /** Smooth, strand-wide contour variation; no independent per-block lumps or random noise. */
    public static boolean organicSleeve(long seed,Direction.Axis axis,int depth,int x,int y,int z,int radius) {
        return organicTube(seed,depth,x,y,z,axis==Direction.Axis.X?1:0,axis==Direction.Axis.Y?1:0,axis==Direction.Axis.Z?1:0,radius);
    }
    /** Distance to a short continuous 3D spine segment, like the feature's overlapping sphere stamps. */
    public static boolean organicTube(long seed,int depth,int x,int y,int z,double tx,double ty,double tz,int radius){
        if(radius==0)return x==0&&y==0&&z==0;
        double length=Math.sqrt(tx*tx+ty*ty+tz*tz);if(length<1e-6){tx=1;ty=0;tz=0;length=1;}
        tx/=length;ty/=length;tz/=length;
        double along=x*tx+y*ty+z*tz;
        double ux=-tz,uy=0,uz=tx,n=Math.hypot(ux,uz);
        if(n<1e-6){ux=1;uy=0;uz=0;}else{ux/=n;uz/=n;}
        double vx=ty*uz-tz*uy,vy=tz*ux-tx*uz,vz=tx*uy-ty*ux;
        double u=x*ux+y*uy+z*uz,v=x*vx+y*vy+z*vz;
        double radial2=u*u+v*v,past=Math.max(0,Math.abs(along)-0.65);
        if(radial2+past*past>radius*radius)return false;
        if(radial2+past*past<=(radius-0.65)*(radius-0.65))return true;
        double phase=(seed&1023)*0.017,t=(depth+along)*0.19+phase;
        double aspect=0.065*Math.sin(t*0.67+0.4);
        double du=(u-0.16*Math.sin(t))/(1+aspect),dv=(v-0.16*Math.cos(t*0.83+1.3))/(1-aspect);
        double edge=radius-0.12+0.20*Math.sin(t*0.61)+0.10*Math.sin(Math.atan2(dv,du)*3+t*0.37);
        return du*du+dv*dv+past*past<=edge*edge;
    }
    public static boolean organicBall(long seed,int x,int y,int z,int radius) {
        if(radius==0)return x==0&&y==0&&z==0;
        double angle=Math.atan2(z,x),flaw=0.16*Math.sin(angle*3+y*0.6+(seed&255)*0.03);
        double r=radius-0.08+flaw;return x*x+y*y+z*z<=r*r;
    }
    public static long fillTile(BlockPos pos){return BlockPos.asLong(pos.getX()>>1,pos.getY()>>1,pos.getZ()>>1);}
    public static int materialDelay(long position){return TENDON_LIFETIME_TICKS+Math.floorMod(position^(position>>>23),20);}
    public static int adaptedInterval(int interval,int trauma){return Math.max(1,interval*8/(8+Math.min(64,Math.max(0,trauma))));}
    public static int healingRate(int damage,long age){return Math.min(PEAK_PLACEMENTS_PER_TICK,BASE_PLACEMENTS_PER_TICK+Math.min(Math.max(0,damage)/512,(int)Math.min(PEAK_PLACEMENTS_PER_TICK-BASE_PLACEMENTS_PER_TICK,Math.max(0,age)/ACCELERATION_INTERVAL_TICKS)));}
    /** Local topology guard: deleting a voxel must not split its face-connected remaining tissue. */
    public static boolean retractionLeavesConnected(Set<BlockPos> tissue) {
        Set<BlockPos> faces=new HashSet<>();for(Direction side:DIRECTIONS)if(tissue.contains(BlockPos.ZERO.relative(side)))faces.add(BlockPos.ZERO.relative(side));
        if(faces.size()<2)return true;
        ArrayDeque<BlockPos> open=new ArrayDeque<>();Set<BlockPos> visited=new HashSet<>();BlockPos start=faces.iterator().next();open.add(start);visited.add(start);
        while(!open.isEmpty()){BlockPos pos=open.remove();for(Direction side:DIRECTIONS){BlockPos next=pos.relative(side);if(tissue.contains(next)&&visited.add(next))open.add(next);}}
        return visited.containsAll(faces);
    }
    public static boolean networkPhaseReady(long age,long idle,int active,boolean targetReached){
        return age>=NETWORK_MIN_TICKS&&(active==0&&(targetReached||age>=NETWORK_FALLBACK_TICKS)
                ||age>=NETWORK_FALLBACK_TICKS&&idle>=STRAND_IDLE_TIMEOUT_TICKS);
    }
    public static int tipRadius(int maximum,int behind){return Math.min(maximum,Math.max(0,behind));}
    private static boolean enabled(ServerLevel level) { return DIMENSIONS.contains(level.dimension().identifier().toString()); }
    private static Runtime runtime(ServerLevel level) { return SERVERS.computeIfAbsent(level.getServer(), Runtime::new); }
    public static boolean isTemporary(LevelAccessor world, BlockPos pos) {
        if (!(world instanceof ServerLevel level) || !enabled(level)) return false;
        Runtime rt = runtime(level);
        Snapshot snap=rt.request(level,Key.of(level,pos));
        // Briefly suspend Caro callbacks while persisted ownership is still being read.
        if(snap==null || snap.loading!=null || snap.failed) return true;
        Cell cell = rt.cells.get(new Position(level.dimension().identifier().toString(), pos.asLong()));
        return cell != null && cell.phase != 0;
    }

    /** Audited woodbound mutations only. Close immediately after the actual edit, including delayed edits. */
    public static Mutation woodboundMutation(LevelAccessor world, BlockPos pos) {
        return new Mutation(world, pos.immutable());
    }
    public static final class Mutation implements AutoCloseable {
        private final LevelAccessor world;
        private final BlockPos pos;
        private boolean closed;
        private Mutation(LevelAccessor world, BlockPos pos) { this.world = world; this.pos = pos; }
        @Override public void close() {
            if (!closed) { closed = true; accept(world, pos); }
        }
    }
    public static boolean woodboundSet(LevelAccessor world, BlockPos pos, BlockState state, int flags) {
        try (Mutation ignored = woodboundMutation(world, pos)) { return world.setBlock(pos, state, flags); }
    }
    public static boolean woodboundSet(LevelAccessor world, BlockPos pos, BlockState state, int flags, int recursion) {
        try (Mutation ignored = woodboundMutation(world, pos)) { return world.setBlock(pos, state, flags, recursion); }
    }
    public static boolean woodboundSetAndUpdate(ServerLevel world, BlockPos pos, BlockState state) {
        return woodboundSet(world, pos, state, 3);
    }
    public static boolean woodboundDestroy(LevelAccessor world, BlockPos pos, boolean drops) {
        try (Mutation ignored = woodboundMutation(world, pos)) { return world.destroyBlock(pos, drops); }
    }
    public static boolean woodboundDestroy(LevelAccessor world, BlockPos pos, boolean drops, Entity source) {
        try (Mutation ignored = woodboundMutation(world, pos)) { return world.destroyBlock(pos, drops, source); }
    }
    public static boolean woodboundRemove(LevelAccessor world, BlockPos pos, boolean moving) {
        try (Mutation ignored = woodboundMutation(world, pos)) { return world.removeBlock(pos, moving); }
    }
    public static boolean destroyForEntity(Entity source, LevelAccessor world, BlockPos pos, boolean drops) {
        return woodbound(source) ? woodboundDestroy(world,pos,drops) : world.destroyBlock(pos,drops);
    }
    /** Used after snapshot rollback as well as by precise mutation wrappers. */
    public static void accept(LevelAccessor world, BlockPos pos) {
        if (!(world instanceof ServerLevel level) || !enabled(level)) return;
        if (!level.getServer().isSameThread()) throw new IllegalStateException("Regeneration hooks must run on the server thread");
        Runtime rt = runtime(level);
        rt.accept(level, pos);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onBreak(BreakBlockEvent event) {
        if (event.isCanceled() || !(event.getLevel() instanceof ServerLevel level) || !enabled(level)) return;
        Runtime rt = runtime(level);
        Cell temporary=rt.cells.get(new Position(level.dimension().identifier().toString(),event.getPos().asLong()));
        if(temporary!=null&&temporary.phase!=0) {
            // Removing a strand produces no Caro drops and never erases the original wound.
            event.setCanceled(true);
            level.setBlock(event.getPos(),Blocks.AIR.defaultBlockState(),3);
            temporary.interrupt(Blocks.AIR.defaultBlockState(),rt.time);
            Snapshot snap=rt.snapshots.get(temporary.key);if(snap!=null)snap.revision++;
            return;
        }
        rt.capture(level, event.getPos(), event.getState());
        rt.defer(level, event.getPos(), event.getState(), true);
    }
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onPlace(BlockEvent.EntityPlaceEvent event) {
        if (event.isCanceled() || !(event.getLevel() instanceof ServerLevel level) || !enabled(level)) return;
        if (!(event.getEntity() instanceof Player) && !woodbound(event.getEntity())) return;
        Runtime rt = runtime(level);
        if (event instanceof BlockEvent.EntityMultiPlaceEvent multi) {
            for (var snapshot : multi.getReplacedBlockSnapshots())
                rt.defer(level, snapshot.getPos(), snapshot.getCurrentState(), false);
        } else rt.defer(level, event.getPos(), event.getPlacedBlock(), false);
    }
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onLivingDestroy(LivingDestroyBlockEvent event) {
        if (event.isCanceled() || !woodbound(event.getEntity()) || !(event.getEntity().level() instanceof ServerLevel level)
                || !enabled(level)) return;
        runtime(level).defer(level, event.getPos(), level.getBlockState(event.getPos()), true);
    }
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onExplosion(ExplosionEvent.Detonate event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !enabled(level)) return;
        Runtime rt = runtime(level);
        boolean exempt = woodbound(event.getExplosion().getDirectSourceEntity()) || woodbound(event.getExplosion().getIndirectSourceEntity());
        if(exempt) {
            rt.exemptExplosions.add(new ExplosionWork(level.dimension().identifier().toString(),List.copyOf(event.getAffectedBlocks()).iterator()));
            return;
        }
        // Event coverage is bounded too; periodic comparisons handle any overflow.
        int remaining = Math.max(1, MAX_COMPARISONS_PER_TICK);
        for (BlockPos pos : event.getAffectedBlocks()) {
            if (--remaining < 0) break;
            rt.capture(level, pos, level.getBlockState(pos));
        }
    }
    private static boolean woodbound(Entity entity) { return entity != null && entity.getType().builtInRegistryHolder().is(WOODBOUND); }
    @SubscribeEvent public static void tick(ServerTickEvent.Post event) {
        Runtime rt = SERVERS.computeIfAbsent(event.getServer(), Runtime::new);
        rt.tick();
    }
    @SubscribeEvent public static void stop(ServerStoppingEvent event) {
        Runtime rt = SERVERS.remove(event.getServer());
        if (rt != null) rt.close();
    }

    private record Position(String dimension, long packed) {}
    private record Key(String dimension, int x, int y, int z) {
        static Key of(ServerLevel level, BlockPos pos) {
            return new Key(level.dimension().identifier().toString(), pos.getX() >> 4, pos.getY() >> 4, pos.getZ() >> 4);
        }
        BlockPos pos(int i) { return new BlockPos((x << 4) + (i & 15), (y << 4) + (i >> 8), (z << 4) + ((i >> 4) & 15)); }
    }
    private static int index(BlockPos pos) { return (pos.getY() & 15) << 8 | (pos.getZ() & 15) << 4 | pos.getX() & 15; }
    private static final class Group {
        Group parent = this;
        int size, diameter = 1;
        boolean seeded;
        long lastGrowth;
        boolean connected, filling;
        long networkBorn=Long.MAX_VALUE;
        int spansActive, connectedVolume, trauma;
        long nextSpan, nextRetraction, nextAdaptation;
        final Set<Long> spanGoals=new HashSet<>();
        final WoundBounds bounds=new WoundBounds();
        int spanStarts;
        BlockPos groundAnchor;
        Group root() { Group p = this; while (p.parent != p) p = p.parent; Group n = this; while(n.parent != n) { Group next=n.parent; n.parent=p; n=next; } return p; }
        static void join(Group a, Group b) {
            a = a.root(); b = b.root(); if (a == b) return;
            if (a.size < b.size) { Group swap = a; a = b; b = swap; }
            b.parent = a; a.size += b.size; a.seeded |= b.seeded;
            a.nextAdaptation=Math.max(a.nextAdaptation,b.nextAdaptation);a.trauma=Math.max(a.trauma,b.trauma);a.filling|=b.filling;a.networkBorn=Math.min(a.networkBorn,b.networkBorn);
            a.connected|=b.connected;a.spansActive+=b.spansActive;
            a.connectedVolume=Math.max(a.connectedVolume,b.connectedVolume);
            a.nextSpan=Math.max(a.nextSpan,b.nextSpan);a.nextRetraction=Math.max(a.nextRetraction,b.nextRetraction);
            a.spanGoals.addAll(b.spanGoals);
            a.bounds.merge(b.bounds);a.spanStarts+=b.spanStarts;if(a.groundAnchor==null)a.groundAnchor=b.groundAnchor;
            a.lastGrowth=Math.max(a.lastGrowth,b.lastGrowth);
            a.diameter = Math.max(Math.max(a.diameter, b.diameter), tendonDiameter(a.size));
        }
    }
    private static final class WoundBounds {
        boolean present;
        int minX,minY,minZ,maxX,maxY,maxZ;
        void include(BlockPos p){if(!present){present=true;minX=maxX=p.getX();minY=maxY=p.getY();minZ=maxZ=p.getZ();return;}
            minX=Math.min(minX,p.getX());minY=Math.min(minY,p.getY());minZ=Math.min(minZ,p.getZ());
            maxX=Math.max(maxX,p.getX());maxY=Math.max(maxY,p.getY());maxZ=Math.max(maxZ,p.getZ());}
        void merge(WoundBounds b){if(b.present){include(new BlockPos(b.minX,b.minY,b.minZ));include(new BlockPos(b.maxX,b.maxY,b.maxZ));}}
        boolean contains(BlockPos p,int pad){return present&&p.getX()>=minX-pad&&p.getX()<=maxX+pad&&p.getY()>=minY-pad&&p.getY()<=maxY+pad&&p.getZ()>=minZ-pad&&p.getZ()<=maxZ+pad;}
    }
    /** A bounded connected-solid search biased toward the supporting body below the wound floor. */
    private static final class SupportSearch {
        final BlockPos start;
        final int floor;
        final PriorityQueue<BlockPos> open;
        final Set<BlockPos> seen=new HashSet<>();
        BlockPos current;
        int direction, examined;
        boolean done, valid;
        long expires;
        SupportSearch(BlockPos start,int floor){this.start=start;this.floor=floor;
            open=new PriorityQueue<>(Comparator.comparingInt((BlockPos p)->p.getY()).thenComparingInt(p->p.distManhattan(start)));open.add(start);seen.add(start);}
        void finish(boolean result){done=true;valid=result;current=null;open.clear();seen.clear();}
        void step(int quantum,java.util.function.BooleanSupplier budget,java.util.function.Predicate<BlockPos> solid){
            for(int work=0;work<quantum&&!done&&budget.getAsBoolean();work++){
                if(current==null){current=open.poll();direction=0;if(current==null){finish(false);return;}
                    if(!solid.test(current)){current=null;continue;}
                    if(current.getY()<=floor){finish(true);return;}}
                BlockPos next=current.relative(DIRECTIONS[direction++]);if(direction==DIRECTIONS.length)current=null;
                if(seen.contains(next)||Math.abs(next.getX()-start.getX())>24||Math.abs(next.getZ()-start.getZ())>24||next.getY()>start.getY()+4)continue;
                if(++examined>MAX_SUPPORT_NODES){finish(false);return;}
                seen.add(next);if(solid.test(next))open.add(next);
            }
        }
    }
    private record SupportKey(Position position,int floor) {}
    private static final class Cell {
        final Key key;
        final int index;
        final BlockPos pos;
        Group group;
        BlockState observed;
        int phase, axis, depth;
        long born, grown, next;
        long nextRoute;
        BlockPos detourTarget;
        boolean scaffold;
        BlockPos anchor;
        int tipBehind, brushRadius=-1, brushCursor;
        double tangentX,tangentY,tangentZ; // Cosmetic frame; reconstructed from the saved endpoints after restart.
        Cell(Key key, int index, BlockState observed, long now) {
            this.key = key; this.index = index; this.pos = key.pos(index); this.observed = observed;
            this.born = now; this.next = now + HEAL_DELAY_TICKS;
            group = new Group(); group.size = 1;
        }
        Position position() { return new Position(key.dimension, pos.asLong()); }
        void interrupt(BlockState replacement,long now) {
            if(phase!=3&&phase!=4&&now>=group.root().nextAdaptation){group.root().trauma=Math.min(64,group.root().trauma+1);group.root().nextAdaptation=now+20;}
            if(phase!=4)phase=3;observed=replacement;
            // Repeated attacks must not keep postponing an already scheduled regrowth.
            next=Math.min(next,now+tendonGrowthInterval(group.root().diameter));
        }
    }
    private static final class Snapshot {
        final Key key;
        final ArrayList<BlockState> palette = new ArrayList<>();
        final IdentityHashMap<BlockState, Integer> ids = new IdentityHashMap<>();
        PalettedContainer<BlockState> initial; // Immutable first-contact terrain; never sample a moving baseline.
        char[] states; // Uniform sections require no 8 KiB index array.
        final BitSet known = new BitSet(4096);
        final BitSet edited = new BitSet(4096);
        final BitSet airDetours = new BitSet(4096);
        final BitSet healed = new BitSet(4096);
        final Int2ObjectOpenHashMap<Cell> damage = new Int2ObjectOpenHashMap<>();
        final SectionWitness witness = new SectionWitness();
        ServerLevel level;
        int cursor, knownCount, trauma;
        long nextAdaptation;
        long nextScan, scanOrder;
        boolean queued;
        long revision, saved, saving = -1, lastActive, lastSave, accounted;
        boolean failed, blockedDuringPass;
        CompletableFuture<Disk> loading;
        Snapshot(Key key) { this.key = key; palette.add(Blocks.AIR.defaultBlockState()); ids.put(palette.get(0), 0); }
        BlockState get(int i) { return palette.get(states == null ? 0 : states[i]); }
        void set(int i, BlockState state) {
            if(known.get(i))return; // First confirmed terrain state is immutable.
            if(!ids.containsKey(state)&&palette.size()>=8192) compact();
            int id = ids.computeIfAbsent(state, value -> { palette.add(value); return palette.size() - 1; });
            if (states == null && id != 0) states = new char[4096];
            if (states != null) states[i] = (char) id;
            if(!known.get(i)){known.set(i);knownCount++;}
            witness.verified=null;revision++;
        }
        void exclude(int i) {
            if(!known.get(i))set(i,Blocks.AIR.defaultBlockState());
            edited.set(i);healed.clear(i);witness.verified=null;revision++;
        }
        boolean healable(int i){return known.get(i)&&!edited.get(i)&&terrain(get(i));}
        void compact() {
            ArrayList<BlockState> before=new ArrayList<>(palette);
            palette.clear();ids.clear();
            palette.add(Blocks.AIR.defaultBlockState());ids.put(palette.get(0),0);
            if(states!=null)for(int i=0;i<4096;i++) {
                BlockState state=before.get(states[i]);
                states[i]=(char)(int)ids.computeIfAbsent(state,value->{palette.add(value);return palette.size()-1;});
            }
        }
        long bytes() { return 1536L + (initial==null?0:32768L) + witness.bytes() + (states == null ? 0 : 8192) + palette.size() * 96L + damage.size() * 512L; }
    }
    /** Exact packed bytes: unlike hashes or block counts, this also detects same-palette block swaps. */
    private static final class SectionWitness {
        byte[] verified, candidate;
        boolean begin(PalettedContainer<BlockState> states,FriendlyByteBuf scratch) {
            scratch.clear();states.write(scratch);
            if(matches(verified,scratch))return true;
            candidate=Arrays.copyOfRange(scratch.array(),scratch.arrayOffset(),scratch.arrayOffset()+scratch.writerIndex());
            return false;
        }
        void end(PalettedContainer<BlockState> states,FriendlyByteBuf scratch,boolean complete) {
            scratch.clear();states.write(scratch);
            verified=complete&&matches(candidate,scratch)?candidate:null;candidate=null;
        }
        static boolean matches(byte[] bytes,FriendlyByteBuf scratch) {
            return bytes!=null&&bytes.length==scratch.writerIndex()
                    &&Arrays.equals(bytes,0,bytes.length,scratch.array(),scratch.arrayOffset(),scratch.arrayOffset()+bytes.length);
        }
        long bytes(){return (verified==null?0:verified.length)+(candidate==null?0:candidate.length);}
    }
    private record Deferred(Key key, int index, BlockState state, boolean removal, long tick) {}
    private record ExplosionWork(String dimension, Iterator<BlockPos> positions) {}
    private record DiskCell(int index, String observed, int phase, int axis, int depth,
                            long age, long grownAge, long delay, int diameter, boolean detour, long target,boolean scaffold,boolean connected,
                            boolean anchored,long anchor,int tipBehind,boolean filling,int networkStarts,long networkAge) {}
    private record Disk(String[] palette, char[] states, long[] known, long[] edited,long[] healed, List<DiskCell> cells,int trauma) {}

    /** Incremental A*: no world access here, so route geometry can be verified independently. */
    private static final class DetourSearch {
        private record Node(BlockPos pos,int cost,int estimate,int side,long order) {}
        final BlockPos start, goal;
        final Direction side;
        final PriorityQueue<Node> open=new PriorityQueue<>(Comparator.comparingInt(Node::estimate)
                .thenComparingInt(Node::side).thenComparingLong(Node::order));
        final Map<BlockPos,Integer> costs=new HashMap<>();
        final Map<BlockPos,BlockPos> parents=new HashMap<>();
        final Set<BlockPos> closed=new HashSet<>();
        final Direction[] order;
        Node current;
        int direction;
        long sequence;
        boolean done;
        List<BlockPos> path;
        DetourSearch(BlockPos start,BlockPos goal,Direction side) {
            this.start=start.immutable();this.goal=goal.immutable();this.side=side;
            ArrayList<Direction> directions=new ArrayList<>();directions.add(side);
            for(Direction value:DIRECTIONS)if(!directions.contains(value))directions.add(value);
            order=directions.toArray(Direction[]::new);
            costs.put(this.start,0);open.add(node(this.start,0));
        }
        Node node(BlockPos pos,int cost) {
            int projection=(pos.getX()-start.getX())*side.getStepX()+(pos.getY()-start.getY())*side.getStepY()+(pos.getZ()-start.getZ())*side.getStepZ();
            double dx=goal.getX()-start.getX(),dy=goal.getY()-start.getY(),dz=goal.getZ()-start.getZ();
            double px=pos.getX()-start.getX(),py=pos.getY()-start.getY(),pz=pos.getZ()-start.getZ();
            double length2=dx*dx+dy*dy+dz*dz,dot=px*dx+py*dy+pz*dz;
            double offLine=length2==0?0:Math.max(0,px*px+py*py+pz*pz-dot*dot/length2);
            // Among equally short safe paths, follow the slanted line instead of exhausting one axis first.
            int alignment=(int)Math.min(1_000_000,Math.round(offLine*1024));
            return new Node(pos,cost,cost+pos.distManhattan(goal),alignment*32-projection,sequence++);
        }
        void step(int quantum,java.util.function.BooleanSupplier budget,java.util.function.Predicate<BlockPos> passable) {
            for(int work=0;!done&&work<quantum&&budget.getAsBoolean();work++) {
                if(current==null) {
                    current=open.poll();direction=0;
                    if(current==null){done=true;return;}
                    if(closed.contains(current.pos)||current.cost!=costs.get(current.pos)){current=null;continue;}
                    if(current.pos.equals(goal)) {
                        ArrayList<BlockPos> result=new ArrayList<>();BlockPos cursor=goal;
                        while(!cursor.equals(start)){result.add(cursor);cursor=parents.get(cursor);}
                        Collections.reverse(result);path=List.copyOf(result);done=true;return;
                    }
                    closed.add(current.pos);
                }
                BlockPos parent=current.pos;
                BlockPos next=parent.relative(order[direction++]);
                int cost=current.cost+1;
                if(direction==order.length){current=null;}
                if(closed.contains(next)||cost>=costs.getOrDefault(next,Integer.MAX_VALUE)
                        ||Math.abs(next.getX()-start.getX())>MAX_DETOUR_DISTANCE
                        ||Math.abs(next.getY()-start.getY())>MAX_DETOUR_DISTANCE
                        ||Math.abs(next.getZ()-start.getZ())>MAX_DETOUR_DISTANCE
                        ||cost+next.distManhattan(goal)>MAX_DETOUR_LENGTH)continue;
                if(!passable.test(next))continue;
                if(costs.size()>=MAX_DETOUR_NODES){done=true;return;}
                parents.put(next,parent);costs.put(next,cost);open.add(node(next,cost));
            }
        }
    }
    private record RouteKey(Position start,int side) {}
    private static final class RouteJob {
        final RouteKey key;
        final String dimension;
        final BlockPos goal;
        final Direction side;
        final Set<Position> held=new HashSet<>();
        Cell head;
        DetourSearch search;
        List<BlockPos> path;
        List<BlockPos> continuation=List.of();
        boolean span;
        int cursor, attempts;
        long next;
        long lastProgress;
        final long created;
        int steps;
        final ArrayDeque<Cell> tipTrail=new ArrayDeque<>();
        final BlockPos anchor;
        RouteJob(Cell source,BlockPos goal,Direction side,long now) {
            key=new RouteKey(source.position(),side.ordinal());dimension=source.key.dimension;
            this.goal=goal.immutable();this.side=side;head=source;next=now;created=now;lastProgress=now;anchor=source.anchor;tipTrail.add(source);
            search=new DetourSearch(source.pos,goal,side);
        }
    }
    private static final class SpanProbe {
        final Cell source;
        final Direction heading;
        int distance=1;
        BlockPos goal;
        final double dx,dy,dz;
        SpanProbe(Cell source,Direction heading){this.source=source;this.heading=heading;
            int variant=Math.floorMod(source.pos.asLong()^source.group.root().spanStarts,9);
            double yaw=Math.atan2(heading.getStepZ(),heading.getStepX())+(variant%5-2)*0.19;
            dx=Math.cos(yaw);dz=Math.sin(yaw);dy=heading==Direction.UP?0.35:(variant/3-1)*0.16;}
        BlockPos at(int d){return new BlockPos((int)Math.round(source.pos.getX()+dx*d),(int)Math.round(source.pos.getY()+dy*d),(int)Math.round(source.pos.getZ()+dz*d));}
    }

    private static final class Coverage {
        int cx, cz, offset, section;
        String dimension = "";
        List<int[]> offsets = List.of();
        int radius = -1;
        int lastMin,lastPy;
        int[] vertical=new int[0];
        boolean complete;
        long nextCycle;
        Key next(ServerLevel level, BlockPos player,long now) {
            int r = Math.max(0, Math.min(512, RADIUS_BLOCKS));
            int x = player.getX() >> 4, z = player.getZ() >> 4;
            String dim = level.dimension().identifier().toString();
            if (radius != r) {
                radius = r; ArrayList<int[]> list = new ArrayList<>(); int chunks = (r + 15) / 16;
                for (int dx=-chunks; dx<=chunks; dx++) for (int dz=-chunks; dz<=chunks; dz++)
                    if (Math.max(0, Math.abs(dx)*16-15)*Math.max(0, Math.abs(dx)*16-15)
                            + Math.max(0, Math.abs(dz)*16-15)*Math.max(0, Math.abs(dz)*16-15) <= r*r)
                        list.add(new int[]{dx,dz});
                list.sort(Comparator.comparingInt(a -> a[0]*a[0]+a[1]*a[1])); offsets = list;
                offset=0; section=0;complete=false;
            }
            if (x!=cx || z!=cz || !dim.equals(dimension)) {cx=x;cz=z;dimension=dim;offset=0;section=0;complete=false;}
            if(complete&&now<nextCycle)return null;
            complete=false;
            int min=level.getMinY() >> 4, count=(level.getMaxY()-level.getMinY()) >> 4;
            int[] delta=offsets.get(offset);
            int py=Math.max(min,Math.min(min+count-1,player.getY() >> 4));
            // Full-height coverage, closest vertical sections first; each appears once.
            if(vertical.length!=count||lastMin!=min||lastPy!=py) {
                vertical=new int[count];int index=0;
                for(int distance=0;index<count;distance++) {
                    if(py+distance<min+count)vertical[index++]=py+distance;
                    if(distance>0&&py-distance>=min)vertical[index++]=py-distance;
                }
                lastMin=min;lastPy=py;
            }
            int sy=vertical[section];
            if (++section>=count) {section=0;offset=(offset+1)%offsets.size();if(offset==0){complete=true;nextCycle=now+200;}}
            return new Key(dim,x+delta[0],sy,z+delta[1]);
        }
    }

    private static final class Runtime implements AutoCloseable {
        final MinecraftServer server;
        final Path directory;
        final ExecutorService io = Executors.newSingleThreadExecutor(r -> {Thread t=new Thread(r,"Backwoods terrain memory");t.setDaemon(true);return t;});
        final Map<Key, Snapshot> snapshots = new LinkedHashMap<>();
        final PriorityQueue<Snapshot> scans = new PriorityQueue<>(Comparator.comparingLong((Snapshot s)->s.nextScan).thenComparingLong(s->s.scanOrder));
        Snapshot scanning;
        final FriendlyByteBuf scratch = new FriendlyByteBuf(Unpooled.buffer(8192,65536));
        final Map<String,ServerLevel> levels = new HashMap<>();
        ServerLevel cachedChunkLevel;
        LevelChunk cachedChunk;
        int cachedChunkX,cachedChunkZ;
        long cachedChunkTick=-1, scanSequence;
        final Set<Key> backlog = new HashSet<>();
        final Map<Position, Cell> cells = new HashMap<>();
        final ArrayDeque<Cell> repairs = new ArrayDeque<>();
        final Set<Position> filledThisTick = new HashSet<>();
        final ArrayDeque<RouteJob> routes = new ArrayDeque<>();
        final ArrayDeque<SpanProbe> probes = new ArrayDeque<>();
        final Map<SupportKey,SupportSearch> supports=new LinkedHashMap<>();
        final ArrayDeque<SupportKey> supportQueue=new ArrayDeque<>();
        final Set<RouteKey> routeKeys = new HashSet<>();
        final Map<Position,Integer> routeHolds = new HashMap<>();
        final Map<UUID,Coverage> coverage = new HashMap<>();
        final ArrayDeque<Deferred> deferred = new ArrayDeque<>();
        final Map<Key, Map<Integer,BlockState>> accepted = new HashMap<>();
        final Set<Key> exclusionWrites = new HashSet<>();
        final Map<Key, Map<Integer,BlockState>> beforeCapture = new HashMap<>();
        final ArrayDeque<ExplosionWork> exemptExplosions = new ArrayDeque<>();
        final ConcurrentLinkedQueue<Runnable> finished = new ConcurrentLinkedQueue<>();
        long time, deadline, estimatedBytes, comparisons, placements, ticks, elapsedNanos, peakNanos;
        long activeSince; int placementLimit=1;
        int pendingIO, phase, playerCursor, queuedEdits, sectionChecks, discoveries;
        long lastWarning=-1200;
        boolean closed;
        Runtime(MinecraftServer server) { validateConfiguration();this.server=server;directory=server.getWorldPath(LevelResource.ROOT).resolve("data/backwoods_regeneration");refreshLevels(); }
        void refreshLevels(){levels.clear();for(ServerLevel level:server.getAllLevels())levels.put(level.dimension().identifier().toString(),level);}
        ServerLevel level(String dim) {
            return levels.get(dim);
        }
        boolean within(ServerLevel level, Key key) {
            int r=Math.max(0,Math.min(512,RADIUS_BLOCKS));
            for(Player player:level.players()) {
                
                int x=player.blockPosition().getX(), z=player.blockPosition().getZ();
                int dx=Math.max((key.x<<4)-x,Math.max(0,x-((key.x<<4)+15)));
                int dz=Math.max((key.z<<4)-z,Math.max(0,z-((key.z<<4)+15)));
                if((long)dx*dx+(long)dz*dz<=(long)r*r) return true;
            }
            return false;
        }
        LevelChunk loaded(ServerLevel level, Key key) {
            if(cachedChunkTick!=time||cachedChunkLevel!=level||cachedChunkX!=key.x||cachedChunkZ!=key.z) {
                cachedChunkTick=time;cachedChunkLevel=level;cachedChunkX=key.x;cachedChunkZ=key.z;
                cachedChunk=level.getChunkSource().getChunkNow(key.x,key.z);
            }
            return cachedChunk;
        }
        void scheduleScan(Snapshot snap,long when){if(!snap.queued){snap.nextScan=when;snap.queued=true;scans.add(snap);}}
        Path file(Key key) {return directory.resolve(key.dimension.replace(':','/')).resolve(key.x+"_"+key.z).resolve(key.y+".dat");}
        boolean budget() { return System.nanoTime()<deadline; }
        boolean naturalAt(ServerLevel level,BlockPos pos) {
            Key key=Key.of(level,pos);Snapshot snap=snapshots.get(key);
            if(snap==null||snap.loading!=null||snap.failed||!snap.known.get(index(pos))||snap.edited.get(index(pos))||loaded(level,key)==null)return false;
            BlockState original=snap.get(index(pos));if(!terrain(original))return false;
            comparisons++;return level.getBlockState(pos).equals(original);
        }
        int supportStatus(ServerLevel level,BlockPos pos,Group wound) {
            if(!budget()||comparisons>=MAX_COMPARISONS_PER_TICK-1)return 0;
            wound=wound.root();
            Key anchorKey=Key.of(level,pos);Snapshot anchorSnapshot=snapshots.get(anchorKey);
            if(loaded(level,anchorKey)==null)return 0;
            if(anchorSnapshot==null){request(level,anchorKey);return 0;}
            if(anchorSnapshot.loading!=null||!anchorSnapshot.known.get(index(pos)))return 0;
            if(!wound.bounds.present||!naturalAt(level,pos))return -1;
            SupportKey key=new SupportKey(new Position(level.dimension().identifier().toString(),pos.asLong()),Math.max(level.getMinY(),pos.getY()-8));
            SupportSearch search=supports.get(key);
            if(search!=null&&search.done&&search.expires<time){supports.remove(key);search=null;}
            if(search==null){
                if(supportQueue.size()>=16)return 0;
                if(supports.size()>=256){var iterator=supports.entrySet().iterator();while(iterator.hasNext()){if(iterator.next().getValue().done){iterator.remove();break;}}}
                if(supports.size()>=256)return 0;
                search=new SupportSearch(pos.immutable(),key.floor);supports.put(key,search);supportQueue.add(key);
            }
            return search.done?(search.valid?1:-1):0;
        }
        void supportStep(){SupportKey key=supportQueue.poll();if(key==null)return;SupportSearch search=supports.get(key);
            ServerLevel level=level(key.position.dimension);if(search==null)return;if(level==null){supports.remove(key);return;}
            search.step(16,()->budget()&&comparisons<MAX_COMPARISONS_PER_TICK-2,p->naturalAt(level,p));
            if(search.done)search.expires=time+40;else supportQueue.add(key);}
        boolean woundSpace(Group group,BlockPos pos){return group.root().bounds.contains(pos,4);}
        void account(Snapshot snap) {long bytes=snap.bytes();estimatedBytes+=bytes-snap.accounted;snap.accounted=bytes;}
        Snapshot request(ServerLevel level, Key key) {
            Snapshot found=snapshots.get(key); if(found!=null) return found;
            if(exclusionWrites.contains(key)||pendingIO>=MAX_IO || estimatedBytes+49152>Math.max(1_048_576,SNAPSHOT_CACHE_BYTES)) return null;
            if(loaded(level,key)==null) return null;
            Snapshot snap=new Snapshot(key);snap.level=level;snap.scanOrder=scanSequence++;
            snap.initial=loaded(level,key).getSections()[key.y-(level.getMinY()>>4)].getStates().copy();
            snap.lastActive=time; snapshots.put(key,snap);account(snap);
            pendingIO++;
            Path path=file(key);
            snap.loading=CompletableFuture.supplyAsync(() -> read(path),io);
            snap.loading.whenComplete((disk,error)->finished.add(()->{
                pendingIO--; snap.loading=null;
                if(error!=null) {snap.failed=true;LOG.error("Terrain snapshot unreadable; refusing to overwrite {}",path,error);return;}
                if(disk!=null) install(snap,disk);
                Map<Integer,BlockState> before=beforeCapture.remove(key);
                if(before!=null) {
                    queuedEdits-=before.size();
                    before.forEach((i,state)->{if(!snap.known.get(i)&&!snap.edited.get(i))snap.set(i,state);});
                }
                Map<Integer,BlockState> edits=accepted.remove(key);
                if(edits!=null) {
                    queuedEdits-=edits.size();
                    edits.forEach((i,state)->{
                        Cell cell=snap.damage.get(i);
                        if(cell!=null&&cell.phase!=0&&isRemoval(state)) {cell.interrupt(state,time);snap.revision++;}
                        else {snap.exclude(i);if(cell!=null)remove(cell);}
                    });
                }
                account(snap);
                scheduleScan(snap,time);
            }));
            return snap;
        }
        void capture(ServerLevel level, BlockPos pos, BlockState before) {
            Key key=Key.of(level,pos); if(!within(level,key)) return;
            Snapshot snap=request(level,key);
            // Preserve pre-event terrain while asynchronous disk history is unresolved.
            if(snap==null||snap.loading!=null)queueEdit(beforeCapture,key,index(pos),before);
            else if(!snap.failed && !snap.known.get(index(pos))&&!snap.edited.get(index(pos))) {snap.set(index(pos),before);account(snap);}
        }
        void queueEdit(Map<Key,Map<Integer,BlockState>> target,Key key,int i,BlockState state) {
            Map<Integer,BlockState> section=target.get(key);
            if(section!=null&&section.containsKey(i)) {if(target==accepted)section.put(i,state);return;}
            if(queuedEdits>=MAX_WOUND_CELLS) {
                if(time-lastWarning>=1200){lastWarning=time;LOG.warn("Regeneration pending edit capacity reached; some unrecorded edits cannot be attributed");}
                return;
            }
            target.computeIfAbsent(key,k->new HashMap<>()).put(i,state);queuedEdits++;
        }
        void defer(ServerLevel level, BlockPos pos, BlockState expected, boolean removal) {
            if(deferred.size()<MAX_WOUND_CELLS) deferred.add(new Deferred(Key.of(level,pos),index(pos),expected,removal,time));
        }
        void accept(ServerLevel level, BlockPos pos) {
            Key key=Key.of(level,pos);
            if(loaded(level,key)==null) return;
            Snapshot snap=snapshots.get(key);
            if(snap==null && !within(level,key)){queueEdit(accepted,key,index(pos),level.getBlockState(pos));return;}
            if(snap==null) snap=request(level,key);
            BlockState actual=level.getBlockState(pos);
            Cell owned=cells.get(new Position(key.dimension,pos.asLong()));
            if(owned!=null&&owned.phase!=0&&isRemoval(actual)) {
                owned.interrupt(actual,time);if(snap!=null)snap.revision++;
                return;
            }
            if(snap==null || snap.loading!=null) {
                queueEdit(accepted,key,index(pos),actual);
            } else if(!snap.failed) {snap.exclude(index(pos));account(snap);}
            Cell cell=cells.get(new Position(key.dimension,pos.asLong())); if(cell!=null) remove(cell);
        }
        void processDeferred() {
            ExplosionWork explosion=exemptExplosions.peek();
            if(explosion!=null) {
                ServerLevel sourceLevel=level(explosion.dimension);
                if(sourceLevel==null||!explosion.positions.hasNext()) {exemptExplosions.remove();return;}
                accept(sourceLevel,explosion.positions.next());return;
            }
            Deferred edit=deferred.peek();if(edit==null || edit.tick>=time)return;deferred.remove();
            ServerLevel level=level(edit.key.dimension); if(level==null||loaded(level,edit.key)==null)return;
            BlockPos pos=edit.key.pos(edit.index); BlockState current=level.getBlockState(pos);
            // Break events precede the edit. A refused/no-op break never changes the baseline.
            // Placement events precede cancellation rollback; accept only the expected placed state.
            if(edit.removal ? current.getBlock()!=edit.state.getBlock() : current.equals(edit.state)) accept(level,pos);
        }
        void flushExclusions() {
            if(pendingIO>=MAX_IO||accepted.isEmpty())return;
            for(var iterator=accepted.entrySet().iterator();iterator.hasNext();) {
                var entry=iterator.next();Key key=entry.getKey();
                if(snapshots.containsKey(key)||exclusionWrites.contains(key))continue;
                Map<Integer,BlockState> edits=entry.getValue();iterator.remove();queuedEdits-=edits.size();
                Map<Integer,String> encoded=encodedEdits(edits);exclusionWrites.add(key);pendingIO++;
                CompletableFuture.runAsync(()->mergeEdits(file(key),Map.of(),encoded,Set.of()),io).whenComplete((none,error)->finished.add(()->{
                    pendingIO--;exclusionWrites.remove(key);
                    if(error!=null){edits.forEach((i,state)->queueEdit(accepted,key,i,state));LOG.error("Cannot persist terrain exclusions {}",file(key),error);}
                }));
                return;
            }
        }
        void install(Snapshot snap, Disk disk) {
            try {
                snap.palette.clear();snap.ids.clear();
                Map<String,BlockState> decoded=new HashMap<>();
                for(String state:disk.palette) {BlockState parsed=decoded.computeIfAbsent(state,DimensionRegeneration::parse);snap.ids.put(parsed,snap.palette.size());snap.palette.add(parsed);}
                snap.trauma=disk.trauma;
                snap.states=disk.states;snap.known.or(BitSet.valueOf(disk.known));snap.knownCount=snap.known.cardinality();snap.edited.or(BitSet.valueOf(disk.edited));snap.healed.or(BitSet.valueOf(disk.healed));
                for(DiskCell saved:disk.cells) {
                    Cell cell=new Cell(snap.key,saved.index,decoded.computeIfAbsent(saved.observed,DimensionRegeneration::parse),time);
                    cell.phase=saved.phase;cell.axis=saved.axis;cell.depth=saved.depth;
                    cell.born=time-saved.age;cell.grown=time-saved.grownAge;cell.next=time+saved.delay;
                    cell.group.diameter=saved.diameter;
                    if(saved.detour)cell.detourTarget=BlockPos.of(saved.target);
                    cell.scaffold=saved.scaffold;cell.group.connected=saved.connected&&saved.anchored;
                    if(saved.anchored)cell.anchor=BlockPos.of(saved.anchor);
                    cell.tipBehind=saved.tipBehind;
                    if(cell.phase!=0&&cell.anchor==null)cell.phase=4; // Withdraw legacy ungrounded scaffolds.
                    attach(snap,cell);
                    Group restored=cell.group.root();restored.filling|=saved.filling;restored.spanStarts=Math.max(restored.spanStarts,saved.networkStarts);restored.networkBorn=Math.min(restored.networkBorn,time-saved.networkAge);
                    // Saved ownership must survive even when the runtime queue is capped.
                    if(saved.connected&&saved.anchored){Group root=cell.group.root();root.connected=true;root.connectedVolume=Math.max(root.connectedVolume,root.size);}
                }
                if(!disk.cells.isEmpty())snap.revision++; // Clean loaded terrain does not need a redundant rewrite.
            } catch(RuntimeException error) { snap.failed=true;LOG.error("Cannot decode terrain memory {}",file(snap.key),error); }
            account(snap);
        }
        void attach(Snapshot snap, Cell cell) {
            cell.group.trauma=Math.max(cell.group.trauma,snap.trauma);
            cell.group.networkBorn=Math.min(cell.group.networkBorn,cell.born);
            boolean wasConnected=cell.group.connected;
            if(!terrain(snap.get(cell.index)))cell.group.size=0; // Air-only detours do not increase wound thickness.
            else if(snap.healable(cell.index))cell.group.bounds.include(cell.pos);
            if(cell.anchor!=null)cell.group.groundAnchor=cell.anchor;
            if(snap.get(cell.index).isAir())snap.airDetours.set(cell.index);
            snap.damage.put(cell.index,cell);cells.put(cell.position(),cell);repairs.add(cell);
            for(Direction direction:DIRECTIONS) {
                Cell neighbor=cells.get(new Position(cell.key.dimension,cell.pos.relative(direction).asLong()));
                if(neighbor!=null)Group.join(cell.group,neighbor.group);
            }
            account(snap);
            Group root=cell.group.root();
            if(!wasConnected&&root.connected&&root.size>Math.max(64,root.connectedVolume*2))root.connected=false;
        }
        void remove(Cell cell) {
            if(cells.remove(cell.position(),cell)) {
                Snapshot snap=snapshots.get(cell.key);
                if(snap!=null){
                    snap.trauma=Math.max(snap.trauma,cell.group.root().trauma);
                    if(terrain(snap.get(cell.index))){Group group=cell.group.root();group.size=Math.max(0,group.size-1);}
                    snap.damage.remove(cell.index);snap.airDetours.clear(cell.index);snap.revision++;account(snap);
                }
            }
        }
        void discover() {
            if(discoveries++>=32)return;
            List<? extends Player> players=server.getPlayerList().getPlayers();if(players.isEmpty())return;
            playerCursor%=players.size();Player player=players.get(playerCursor);playerCursor=(playerCursor+1)%players.size();
            if(!(player.level() instanceof ServerLevel level) || !enabled(level))return;
            Key key=coverage.computeIfAbsent(player.getUUID(),id->new Coverage()).next(level,player.blockPosition(),time);
            if(key!=null&&within(level,key)) request(level,key);
        }
        void scan() {
            Snapshot snap=scanning;
            if(snap==null) {
                if(sectionChecks>=MAX_SECTION_CHECKS_PER_TICK||scans.isEmpty()||scans.peek().nextScan>time)return;
                snap=scans.poll();snap.queued=false;scanning=snap;sectionChecks++;
            }
            Key key=snap.key;ServerLevel level=snap.level;
            if(level==null||!enabled(level)||snap.loading!=null||snap.failed){endScan(snap,SECTION_CHECK_INTERVAL);return;}
            LevelChunk chunk=loaded(level,key);
            if(chunk==null||!within(level,key)) {endScan(snap,SECTION_CHECK_INTERVAL);return;}
            snap.lastActive=time;
            int sectionIndex=key.y-(level.getMinY()>>4);
            LevelChunkSection[] sections=chunk.getSections();if(sectionIndex<0||sectionIndex>=sections.length){endScan(snap,SECTION_CHECK_INTERVAL);return;}
            LevelChunkSection section=sections[sectionIndex];
            if(snap.cursor==0) {
                if(snap.witness.begin(section.getStates(),scratch)&&snap.knownCount==4096){endScan(snap,SECTION_CHECK_INTERVAL);return;}
                snap.blockedDuringPass=false;
            }
            if(snap.initial==null&&section.hasOnlyAir()&&snap.states==null&&snap.palette.get(0).isAir()&&snap.damage.isEmpty()) {
                if(snap.knownCount!=4096){snap.known.set(0,4096);snap.knownCount=4096;snap.revision++;}
                snap.witness.end(section.getStates(),scratch,true);endScan(snap,SECTION_CHECK_INTERVAL);return;
            }
            for(int n=0;n<(cells.isEmpty()?QUANTUM:32)&&comparisons<MAX_COMPARISONS_PER_TICK&&budget();n++) {
                int i=snap.cursor;snap.cursor=(i+1)&4095;comparisons++;
                BlockState current=section.getBlockState(i&15,i>>8,(i>>4)&15);
                if(snap.edited.get(i)) {
                    Cell excluded=snap.damage.get(i);if(excluded!=null&&excluded.phase==0)remove(excluded);
                } else {
                if(snap.knownCount!=4096&&!snap.known.get(i)){snap.set(i,snap.initial==null?current:snap.initial.get(i&15,i>>8,(i>>4)&15));if(snap.cursor!=0)continue;}
                BlockState original=snap.get(i);
                Cell cell=current==original&&!snap.airDetours.get(i)?null:snap.damage.get(i);
                if(cell!=null) {
                    if(current.equals(original)&&!(original.isAir()&&cell.detourTarget!=null&&cell.phase!=0))remove(cell);
                    // External changes to temporary Caro become fresh damage, never silently accepted.
                    else if(cell.phase!=0&&cell.phase!=3&&!current.is(tendon().getBlock())) {cell.interrupt(current,time);snap.revision++;}
                } else if(!original.equals(current)) {
                if(snap.healed.get(i)&&terrain(original)&&isRemoval(current)&&time>=snap.nextAdaptation){snap.trauma=Math.min(64,snap.trauma+1);snap.nextAdaptation=time+20;}
                snap.healed.clear(i);
                if(original.getBlock()==current.getBlock()) {
                    // Normal state updates do not rewrite the original orientation/properties.
                } else if(!terrain(original)||current.hasBlockEntity()||!isRemoval(current)&&!current.is(tendon().getBlock())) {
                    if(!isRemoval(current))snap.exclude(i);
                } else if(cells.size()>=MAX_WOUND_CELLS) {
                    snap.blockedDuringPass=true;
                    backlog.add(key); // Keep the baseline and retry when capacity frees up.
                } else {Cell damage=new Cell(key,i,current,time);attach(snap,damage);snap.revision++;}
                }
                }
                if(snap.cursor==0) {
                    if(!snap.blockedDuringPass)backlog.remove(key);
                    snap.witness.end(section.getStates(),scratch,!snap.blockedDuringPass&&snap.knownCount==4096);
                    endScan(snap,snap.blockedDuringPass&&cells.size()<MAX_WOUND_CELLS?1:SECTION_CHECK_INTERVAL);return;
                }
            }
            account(snap);
        }
        void endScan(Snapshot snap,int delay) {
            if(snap.knownCount==4096)snap.initial=null;
            scanning=null;account(snap);maintain(snap);
            if(snapshots.get(snap.key)==snap&&!snap.failed)scheduleScan(snap,time+delay);
        }
        void maintain(Snapshot snap) {
            if(snap.failed||snap.loading!=null)return;
            if(snap.revision!=snap.saved && snap.saving<0 && pendingIO<MAX_IO && (time-snap.lastSave>=100 || time-snap.lastActive>100)) save(snap);
            if(time-snap.lastActive>100 && snap.saved==snap.revision && snap.saving<0) {
                estimatedBytes-=snap.accounted;snapshots.remove(snap.key);if(snap.queued){scans.remove(snap);snap.queued=false;}
                backlog.remove(snap.key);
                for(Cell cell:snap.damage.values()) {
                    cells.remove(cell.position());
                    if(terrain(snap.get(cell.index))){Group group=cell.group.root();group.size=Math.max(0,group.size-1);}
                }
            }
        }
        void save(Snapshot snap) {
            long revision=snap.revision;
            Disk disk=detach(snap);
            snap.saving=revision;snap.lastSave=time;pendingIO++;
            CompletableFuture.runAsync(()->write(file(snap.key),disk),io).whenComplete((nothing,error)->finished.add(()->{
                pendingIO--;snap.saving=-1;
                if(error==null)snap.saved=revision;
                else {snap.failed=true;LOG.error("Cannot save terrain memory {}; keeping resident baseline",file(snap.key),error);}
            }));
        }
        Disk detach(Snapshot snap) {
            for(Cell cell:snap.damage.values())snap.trauma=Math.max(snap.trauma,cell.group.root().trauma);
            IdentityHashMap<BlockState,String> encoded=new IdentityHashMap<>();
            String[] palette=new String[snap.palette.size()];
            for(int i=0;i<palette.length;i++)palette[i]=encoded.computeIfAbsent(snap.palette.get(i),DimensionRegeneration::encode);
            ArrayList<DiskCell> damage=new ArrayList<>();
            for(Cell c:snap.damage.values())damage.add(new DiskCell(c.index,encoded.computeIfAbsent(c.observed,DimensionRegeneration::encode),c.phase,c.axis,c.depth,
                    Math.max(0,time-c.born),Math.max(0,time-c.grown),Math.max(0,c.next-time),c.group.root().diameter,
                    c.detourTarget!=null,c.detourTarget==null?0:c.detourTarget.asLong(),c.scaffold,c.group.root().connected,
                    c.anchor!=null,c.anchor==null?0:c.anchor.asLong(),c.tipBehind,c.group.root().filling,c.group.root().spanStarts,Math.max(0,time-c.group.root().networkBorn)));
            return new Disk(palette,snap.states==null?null:snap.states.clone(),snap.known.toLongArray(),snap.edited.toLongArray(),snap.healed.toLongArray(),damage,snap.trauma);
        }
        BlockState tendon() {return BuiltInRegistries.BLOCK.getOptional(Identifier.parse(TENDON_BLOCK)).orElseThrow(()->new IllegalStateException("Missing tendon block: "+TENDON_BLOCK)).defaultBlockState();}
        void withdraw(ServerLevel level,Snapshot snap,Cell cell,BlockState current) {
            Group wound=cell.group.root();
            if(current.is(tendon().getBlock())) {
                if(time<wound.nextRetraction||routeHolds.containsKey(cell.position()))return;
                Set<BlockPos> tissue=new HashSet<>();
                // Reverse the strand depth, and peel its surface without severing its remaining sleeve.
                for(int x=-1;x<=1;x++)for(int y=-1;y<=1;y++)for(int z=-1;z<=1;z++) {
                    if(x==0&&y==0&&z==0)continue;
                    BlockPos offset=new BlockPos(x,y,z),pos=cell.pos.offset(offset);
                    Cell neighbor=cells.get(new Position(cell.key.dimension,pos.asLong()));
                    if(neighbor==null||neighbor.phase==0)continue;
                    if(!budget()||comparisons>=MAX_COMPARISONS_PER_TICK||loaded(level,neighbor.key)==null)return;
                    comparisons++;
                    if(!level.getBlockState(pos).is(tendon().getBlock()))continue;
                    if(neighbor.group.root()==wound&&neighbor.depth>cell.depth)return;
                    tissue.add(offset);
                }
                if(!retractionLeavesConnected(tissue))return;
                if(!place(level,cell.pos,Blocks.AIR.defaultBlockState()))return;
                wound.nextRetraction=time+RETRACTION_INTERVAL_TICKS;
            }
            snap.healed.clear(cell.index);
            if(terrain(snap.get(cell.index))&&!snap.edited.get(cell.index)) {
                cell.phase=0;cell.scaffold=false;cell.anchor=null;cell.detourTarget=null;cell.brushRadius=-1;
                cell.next=time+HEAL_DELAY_TICKS;snap.revision++;
            } else remove(cell);
        }
        boolean restorationFront(ServerLevel level,Cell cell) {
            for(Direction side:DIRECTIONS) {
                BlockPos pos=cell.pos.relative(side);Snapshot snap=snapshots.get(Key.of(level,pos));
                if(snap==null||snap.loading!=null||!snap.known.get(index(pos))||snap.edited.get(index(pos))||loaded(level,snap.key)==null)continue;
                BlockState original=snap.get(index(pos));if(!terrain(original))continue;
                if(!budget()||comparisons>=MAX_COMPARISONS_PER_TICK)return false;
                comparisons++;if(!level.getBlockState(pos).equals(original))continue;
                if(snap.healed.get(index(pos))||supportStatus(level,pos,cell.group)==1)return true;
            }
            return false;
        }
        int networkTarget(Group group){return Math.min(MAX_WOUND_SPANS,Math.max(2,2+group.root().size/2048));}
        boolean gapPhase(Group group,Cell cell){
            group=group.root();
            if(group.filling)return true;
            long age=time-Math.min(cell.born,group.networkBorn);
            // Phase two starts after the beam network settles; bounded fallback also handles one-sided cavities.
            if(networkPhaseReady(age,time-group.lastGrowth,group.spansActive,group.spanStarts>=networkTarget(group)))group.filling=true;
            return group.filling;
        }
        int growthInterval(Cell cell){return adaptedInterval(Math.max(4,tendonGrowthInterval(cell.group.root().diameter)-Math.min(6,(int)(Math.max(0,time-cell.born)/600))),cell.group.root().trauma);}
        void repair() {
            // Do not heal a large woodbound blast before its incremental exemptions finish.
            if(!exemptExplosions.isEmpty())return;
            Cell cell=repairs.poll();if(cell==null)return;
            if(cells.get(cell.position())!=cell)return;
            repairs.add(cell);
            if(cell.next>time)return;
            cell.next=time+1; // Round-robin readiness; successful growth has its own age gate.
            Group wound=cell.group.root();
            // Edge growth and span planning share the same bounded scheduler.
            ServerLevel level=level(cell.key.dimension);if(level==null||!enabled(level)||!within(level,cell.key)||loaded(level,cell.key)==null)return;
            Snapshot snap=snapshots.get(cell.key);if(snap==null||snap.loading!=null||snap.failed)return;
            snap.revision++; // Persist relative timers and attained group thickness on the next checkpoint.
            BlockState original=snap.get(cell.index),current=level.getBlockState(cell.pos);
            if(cell.phase==4){withdraw(level,snap,cell,current);return;}
            if(snap.edited.get(cell.index)){remove(cell);return;}
            if(current.equals(original)&&!(original.isAir()&&cell.detourTarget!=null&&cell.phase!=0)){remove(cell);return;}
            if(current.hasBlockEntity()) {accept(level,cell.pos);return;}
            // Solid additions, including command-built structures, are obstacles rather than repair material.
            if(!isRemoval(current)&&!current.is(tendon().getBlock())){accept(level,cell.pos);return;}
            if(occupied(level,cell.pos))return;
            // Mature Caro is a temporary placeholder, including shell cells and interrupted spans.
            if(gapPhase(wound,cell)&&cell.phase!=0&&current.is(tendon().getBlock())&&snap.healable(cell.index)
                    &&time-cell.grown>=materialDelay(cell.pos.asLong())
                    &&!routeHolds.containsKey(cell.position())&&restorationFront(level,cell)) {
                if(placeRepair(level,cell,original)){snap.healed.set(cell.index);snap.revision++;remove(cell);}return;
            }
            if(cell.phase!=0&&cell.anchor==null){cell.phase=4;return;}
            if(cell.phase!=0) {
                int supported=supportStatus(level,cell.anchor,wound);
                if(supported==0)return;
                if(supported<0){
                    // Preserve existing tissue when its old anchor is damaged; find another surviving edge.
                    for(Direction side:DIRECTIONS){BlockPos candidate=cell.pos.relative(side);
                        if(supportStatus(level,candidate,wound)==1){cell.anchor=candidate;wound.groundAnchor=candidate;return;}}
                    return;
                }
            }
            if(cell.phase==3) {
                if(current.is(tendon().getBlock())||placeRepair(level,cell,tendon())) {
                    cell.phase=1;cell.grown=time;cell.group.root().lastGrowth=time;snap.revision++;
                }
                return;
            }
            if(cell.phase!=0) {
                if(!current.is(tendon().getBlock())) {cell.interrupt(current,time);snap.revision++;return;}
                if(cell.phase==1){planDetour(level,cell);if(cell.tipBehind>0)thicken(level,cell);}
                if(cell.phase==1&&cell.brushRadius>=0&&cell.brushCursor<(cell.brushRadius*2+1)*(cell.brushRadius*2+1)*(cell.brushRadius*2+1))return;
                if(!routeHolds.containsKey(cell.position())&&(!cell.scaffold||wound.spansActive==0&&(wound.connected||cell.detourTarget==null))
                        &&gapPhase(wound,cell)&&time-cell.grown>=materialDelay(cell.pos.asLong()) && placements<MAX_PLACEMENTS_PER_TICK && budget()) {
                    if(!terrain(original)){withdraw(level,snap,cell,current);return;}
                    if(wound.connected&&restorationFront(level,cell)&&placeRepair(level,cell,original)){snap.healed.set(cell.index);snap.revision++;remove(cell);}
                }
                return;
            }
            if(time-cell.born<HEAL_DELAY_TICKS)return;
            if(wound.size>=64&&(!wound.connected||wound.spansActive>0)) {
                if(!gapPhase(wound,cell)&&cell.nextRoute<=time&&time>=wound.nextSpan
                        &&wound.spanStarts<networkTarget(wound)&&wound.spansActive<Math.min(MAX_DETOUR_JOBS,2+wound.size/4096)&&routes.size()+probes.size()<MAX_DETOUR_JOBS) {
                    for(Direction wall:new Direction[]{Direction.NORTH,Direction.SOUTH,Direction.WEST,Direction.EAST,Direction.DOWN}) {
                        BlockPos anchor=cell.pos.relative(wall);Key anchorKey=Key.of(level,anchor);Snapshot edge=snapshots.get(anchorKey);
                        if(edge==null||edge.loading!=null||edge.failed||!edge.known.get(index(anchor))||edge.edited.get(index(anchor))
                                ||loaded(level,anchorKey)==null||!budget()||comparisons>=MAX_COMPARISONS_PER_TICK)continue;
                        if(supportStatus(level,anchor,wound)==1) {
                            cell.anchor=anchor;wound.groundAnchor=anchor;
                            probes.add(new SpanProbe(cell,wall.getOpposite()));wound.spansActive++;wound.nextSpan=time+20;
                            cell.nextRoute=time+200;routeHolds.merge(cell.position(),1,Integer::sum);break;
                        }
                    }
                }
                // A one-sided wound still heals after a grace period, but never by seeding floating blobs.
                // Continue filling from surviving edges while bridge candidates are explored.
            }
            boolean filling=gapPhase(wound,cell);
            Direction incoming=null;int depth=0;BlockPos anchor=null;Cell parentTip=null;
            int tissueNeighbors=0;
            for(Direction side:DIRECTIONS){Cell adjacent=cells.get(new Position(cell.key.dimension,cell.pos.relative(side).asLong()));if(adjacent!=null&&(adjacent.phase==1||adjacent.phase==2))tissueNeighbors++;}
            boolean gapFill=filling;
            if(!filling&&wound.size>=512)return; // Large wounds are built from planned spanning beams, never random walkers.
            for(Direction direction:DIRECTIONS) {
                BlockPos neighborPos=cell.pos.relative(direction);
                Key neighborKey=Key.of(level,neighborPos);if(loaded(level,neighborKey)==null)continue;
                Cell neighbor=cells.get(new Position(cell.key.dimension,neighborPos.asLong()));
                if(neighbor!=null) {
                    if((neighbor.phase==1||gapFill&&neighbor.phase==2) && time-neighbor.grown>=growthInterval(neighbor)
                            &&(gapFill||fiberSuccessor(level,neighbor,cell))) {
                        incoming=direction.getOpposite();depth=neighbor.depth+1;anchor=neighbor.anchor;parentTip=neighbor;break;
                    }
                } else {
                    Snapshot ns=snapshots.get(neighborKey);int ni=index(neighborPos);
                    if(ns!=null&&ns.loading==null&&ns.known.get(ni)&&!ns.edited.get(ni)&&terrain(ns.get(ni))&&level.getBlockState(neighborPos).equals(ns.get(ni))) {
                        Group group=cell.group.root();
                        // Sparse roots: each later wave adds strands, rather than coating every edge at once.
                        if(wound.size>=64&&!gapFill&&Math.floorMod(cell.pos.asLong()^(cell.pos.asLong()>>>19),97)!=Math.floorMod((time-cell.born-HEAL_DELAY_TICKS)/600,97))continue;
                        if(supportStatus(level,neighborPos,group)==1){incoming=direction.getOpposite();anchor=neighborPos;}
                        else if(group.groundAnchor!=null&&ns.healed.get(ni)&&supportStatus(level,group.groundAnchor,group)==1){incoming=direction.getOpposite();anchor=group.groundAnchor;}
                    }
                }
            }
            if(incoming!=null&&anchor!=null&&supportStatus(level,anchor,wound)==1) {
                if(placeRepair(level,cell,tendon())) {
                    if(!wound.connected){wound.connected=true;wound.connectedVolume=wound.size;}
                    cell.anchor=anchor;wound.groundAnchor=anchor;
                    cell.phase=gapFill?2:1;cell.axis=incoming.ordinal();cell.depth=depth;cell.grown=time;
                    Cell stem=parentTip;
                    for(int behind=1;stem!=null&&behind<=4;behind++){stem.tipBehind=Math.max(stem.tipBehind,behind);Snapshot owner=snapshots.get(stem.key);if(owner!=null)owner.revision++;stem=cells.get(new Position(stem.key.dimension,stem.pos.relative(DIRECTIONS[stem.axis].getOpposite()).asLong()));}
                    Group group=cell.group.root();group.seeded=true;group.lastGrowth=time;group.diameter=Math.max(group.diameter,tendonDiameter(group.size));snap.revision++;
                }
            }
        }
        boolean fiberSuccessor(ServerLevel level,Cell parent,Cell child) {
            Direction desired=extensionDirection(DIRECTIONS[parent.axis],parent.anchor==null?parent.pos.asLong():parent.anchor.asLong(),parent.depth);
            boolean branch=parent.depth>0&&parent.depth%12==0&&Math.floorMod(child.pos.asLong(),11)==0;
            if(!parent.pos.relative(desired).equals(child.pos)&&!branch)return false;
            int successors=0;
            for(Direction side:DIRECTIONS){Cell existing=cells.get(new Position(parent.key.dimension,parent.pos.relative(side).asLong()));
                if(existing!=null&&existing.phase==1&&existing.depth==parent.depth+1)successors++;}
            return successors<(branch?2:1);
        }
        void finishProbe(SpanProbe probe) {
            Group group=probe.source.group.root();group.spansActive=Math.max(0,group.spansActive-1);
            routeHolds.computeIfPresent(probe.source.position(),(key,count)->count<=1?null:count-1);
        }
        void probeSpan() {
            SpanProbe probe=probes.poll();if(probe==null)return;
            Cell source=probe.source;
            if(source.group.root().filling){finishProbe(probe);return;}
            ServerLevel level=level(source.key.dimension);
            if(level==null||cells.get(source.position())!=source||loaded(level,source.key)==null||!within(level,source.key)){finishProbe(probe);return;}
            for(int work=0;probe.goal==null&&work<16&&budget()&&comparisons<MAX_COMPARISONS_PER_TICK;work++) {
                if(probe.distance>MAX_SPAN_DISTANCE){finishProbe(probe);return;}
                BlockPos pos=probe.at(probe.distance);Key key=Key.of(level,pos);
                if(!woundSpace(source.group,pos)||!within(level,key)||loaded(level,key)==null){finishProbe(probe);return;}
                Snapshot snap=snapshots.get(key);
                if(snap==null){request(level,key);probes.add(probe);return;}
                if(snap.failed){finishProbe(probe);return;}
                if(snap.loading!=null||!snap.known.get(index(pos))){probes.add(probe);return;}
                comparisons++;BlockState current=level.getBlockState(pos),original=snap.get(index(pos));
                if(terrain(original)&&current.equals(original)&&!snap.edited.get(index(pos))) {
                    if(probe.distance<6){finishProbe(probe);return;}
                    int status=supportStatus(level,pos,source.group);
                    if(status==0){probes.add(probe);return;}
                    if(status==1){probe.goal=pos;break;}
                }
                probe.distance++;
            }
            if(probe.goal==null||placements>=MAX_PLACEMENTS_PER_TICK||!budget()){probes.add(probe);return;}
            if(source.group.root().spanGoals.contains(probe.goal.asLong())){finishProbe(probe);return;}
            List<BlockPos> spine=beamPath(source.pos,probe.goal,source.pos.asLong()^source.group.root().spanStarts);
            if(spine.size()>MAX_SPAN_DISTANCE*4||spine.stream().anyMatch(p->!woundSpace(source.group,p))){finishProbe(probe);return;}
            if(supportStatus(level,source.anchor,source.group)!=1){probes.add(probe);return;}
            comparisons++;BlockState current=level.getBlockState(source.pos);
            if(!isRemoval(current)){finishProbe(probe);return;}
            if(occupied(level,source.pos)||!place(level,source.pos,tendon())){probes.add(probe);return;}
            source.phase=1;source.axis=probe.heading.ordinal();source.scaffold=true;source.detourTarget=probe.goal;source.grown=time;
            Snapshot snap=snapshots.get(source.key);snap.revision++;
            Direction side=(source.pos.asLong()&1)==0?Direction.UP:Direction.DOWN;
            RouteJob job=new RouteJob(source,probe.goal,side,time);job.search=null;job.path=spine;job.span=true;source.group.root().spanStarts++;
            source.group.root().spanGoals.add(probe.goal.asLong());
            hold(job,source);routeKeys.add(job.key);routes.add(job);
            // Transfer the probe's group reservation to the growing span.
            routeHolds.computeIfPresent(source.position(),(key,count)->count<=1?null:count-1);
        }
        void hold(RouteJob job,Cell cell) {
            if(job.held.add(cell.position()))routeHolds.merge(cell.position(),1,Integer::sum);
        }
        void finishRoute(RouteJob job) {finishRoute(job,false,false);}
        void finishRoute(RouteJob job,boolean resume) {finishRoute(job,resume,false);}
        void finishRoute(RouteJob job,boolean resume,boolean connected) {
            if(job.span){Group group=job.head.group.root();group.spansActive=Math.max(0,group.spansActive-1);group.spanGoals.remove(job.goal.asLong());if(connected){group.connected=true;group.connectedVolume=group.size;}}
            routeKeys.remove(job.key);
            for(Position position:job.held) {
                int count=routeHolds.getOrDefault(position,1)-1;
                if(count>0){routeHolds.put(position,count);continue;}
                routeHolds.remove(position);
                Cell cell=cells.get(position);
                if(cell!=null) {
                    cell.detourTarget=resume?job.goal:null;
                    if(!resume&&!connected){
                        Snapshot owner=snapshots.get(cell.key);
                        if(owner!=null&&owner.healable(cell.index)){cell.scaffold=false;cell.detourTarget=null;cell.phase=1;}
                        else cell.phase=4;
                    }
                    Snapshot snap=snapshots.get(cell.key);if(snap!=null)snap.revision++;
                    if(!resume&&snap!=null&&snap.get(cell.index).isAir())cell.grown=time;
                }
            }
            if(!resume&&!connected&&!job.head.group.root().filling&&cells.get(job.head.position())==job.head){
                job.head.detourTarget=job.goal;job.head.scaffold=job.span;job.head.nextRoute=time+20;
                Snapshot owner=snapshots.get(job.head.key);if(owner!=null)owner.revision++;
            }
        }
        void planDetour(ServerLevel level,Cell source) {
            if(source.anchor==null||source.phase==4||source.nextRoute>time||routeKeys.size()+probes.size()>=MAX_DETOUR_JOBS||routeHolds.containsKey(source.position()))return;
            source.nextRoute=time+100;
            Direction forward=DIRECTIONS[source.axis];BlockPos goal=source.detourTarget;
            if(goal==null) {
                BlockPos ahead=source.pos.relative(forward);Key key=Key.of(level,ahead);
                if(loaded(level,key)==null||!budget()||comparisons>=MAX_COMPARISONS_PER_TICK)return;
                comparisons++;BlockState obstacle=level.getBlockState(ahead);
                if(isRemoval(obstacle)||isTemporary(level,ahead))return;
                // Seek recorded missing terrain beyond the obstacle, not an arbitrary point in open air.
                for(int distance=2;distance<=MAX_DETOUR_DISTANCE&&budget()&&comparisons<MAX_COMPARISONS_PER_TICK;distance++) {
                    BlockPos candidate=source.pos.relative(forward,distance);
                    Cell target=cells.get(new Position(source.key.dimension,candidate.asLong()));
                    if(target==null||target.phase!=0||target.group.root()!=source.group.root()||!woundSpace(source.group,candidate)||loaded(level,target.key)==null)continue;
                    comparisons++;
                    Snapshot snap=snapshots.get(target.key);
                    if(snap!=null&&terrain(snap.get(target.index))&&isRemoval(level.getBlockState(candidate))){goal=candidate;break;}
                }
            }
            if(goal==null||goal.equals(source.pos))return;
            if(source.scaffold&&source.group.root().spanGoals.contains(goal.asLong()))return;
            source.detourTarget=goal;
            Snapshot sourceSnapshot=snapshots.get(source.key);if(sourceSnapshot!=null)sourceSnapshot.revision++;
            Direction side=forward.getAxis()==Direction.Axis.Y?Direction.EAST:Direction.UP;
            Direction[] biases=source.scaffold?new Direction[]{side}:new Direction[]{side,side.getOpposite()};
            for(Direction bias:biases) {
                if(routeKeys.size()+probes.size()>=MAX_DETOUR_JOBS)break;
                RouteJob job=new RouteJob(source,goal,bias,time);
                if(source.scaffold){job.span=true;job.search=null;job.path=beamPath(source.pos,goal,source.pos.asLong());}
                if(routeKeys.add(job.key)){if(job.span){source.group.root().spansActive++;source.group.root().spanGoals.add(goal.asLong());}hold(job,source);routes.add(job);}
            }
        }
        boolean replan(RouteJob job,ServerLevel level) {
            if(++job.attempts>8)return false;
            if(job.path!=null) {
                for(int i=job.cursor;i<job.path.size()&&i<job.cursor+MAX_DETOUR_DISTANCE&&budget()&&comparisons<MAX_COMPARISONS_PER_TICK;i++) {
                    BlockPos waypoint=job.path.get(i);
                    if(job.head.pos.distManhattan(waypoint)>MAX_DETOUR_DISTANCE)break;
                    if(strandSpace(level,waypoint,job)||waypoint.equals(job.goal)&&woundSpace(job.head.group,waypoint)&&naturalAt(level,waypoint)) {
                        job.continuation=List.copyOf(job.path.subList(i+1,job.path.size()));
                        job.search=new DetourSearch(job.head.pos,waypoint,job.side);job.path=null;job.cursor=0;return true;
                    }
                }
                return false;
            }
            if(job.head.pos.distManhattan(job.goal)>MAX_DETOUR_DISTANCE)return false;
            job.search=new DetourSearch(job.head.pos,job.goal,job.side);return true;
        }
        boolean routeSpace(ServerLevel level,BlockPos pos,Group wound) {
            if(!budget()||comparisons>=MAX_COMPARISONS_PER_TICK)return false;
            if(!woundSpace(wound,pos))return false;
            if(pos.getY()<level.getMinY()||pos.getY()>=level.getMaxY())return false;
            Key key=Key.of(level,pos);
            if(!within(level,key)||loaded(level,key)==null)return false;
            Snapshot snap=snapshots.get(key);
            if(snap==null){request(level,key);return false;}
            if(snap.failed||snap.loading!=null||!snap.known.get(index(pos))||snap.edited.get(index(pos))||snap.get(index(pos)).hasBlockEntity())return false;
            comparisons++;BlockState current=level.getBlockState(pos);
            Cell owned=cells.get(new Position(key.dimension,pos.asLong()));
            return current.isAir()||owned!=null&&owned.phase!=0&&current.is(tendon().getBlock());
        }
        boolean strandSpace(ServerLevel level,BlockPos pos,RouteJob job){
            if(!routeSpace(level,pos,job.head.group))return false;
            Snapshot snap=snapshots.get(Key.of(level,pos));return !job.span||snap!=null&&snap.healable(index(pos));
        }
        boolean routeAnchor(ServerLevel level,BlockPos pos,Group wound) {
            return woundSpace(wound,pos)&&supportStatus(level,pos,wound)==1;
        }
        void route() {
            RouteJob job=routes.poll();if(job==null)return;
            if(job.head.group.root().filling||time-job.lastProgress>=STRAND_IDLE_TIMEOUT_TICKS){finishRoute(job,false,job.head.group.root().filling);return;}
            ServerLevel level=level(job.dimension);
            if(level==null){finishRoute(job);return;}
            if(time-job.created>MAX_ROUTE_TICKS||job.steps>MAX_SPAN_DISTANCE*4||job.anchor==null){finishRoute(job);return;}
            int grounded=supportStatus(level,job.anchor,job.head.group);
            if(grounded==0){routes.add(job);return;}if(grounded<0){finishRoute(job);return;}
            if(cells.get(job.head.position())!=job.head) {
                Cell previous=null;int distance=Integer.MAX_VALUE;
                for(Position position:job.held) {
                    Cell candidate=cells.get(position);
                    if(candidate==null||candidate.phase!=1)continue;
                    int gap=candidate.pos.distManhattan(job.head.pos);
                    if(gap<distance){previous=candidate;distance=gap;}
                }
                if(previous==null){finishRoute(job);return;}
                job.head=previous;if(!replan(job,level)){finishRoute(job);return;}
            }
            if(loaded(level,job.head.key)==null||!within(level,job.head.key)){
                // The persisted target lets the loaded strand re-plan without force-loading chunks.
                finishRoute(job,true);return;
            }
            if(job.search!=null) {
                job.search.step(32,()->budget()&&comparisons<MAX_COMPARISONS_PER_TICK,
                        pos->strandSpace(level,pos,job)||pos.equals(job.goal)&&woundSpace(job.head.group,pos)&&naturalAt(level,pos));
                if(!job.search.done){routes.add(job);return;}
                job.path=job.search.path;job.search=null;
                if(job.path==null||job.path.isEmpty()){finishRoute(job);return;}
                if(!job.continuation.isEmpty()){ArrayList<BlockPos> combined=new ArrayList<>(job.path);combined.addAll(job.continuation);job.path=List.copyOf(combined);job.continuation=List.of();}
            }
            if(job.next>time||placements>=MAX_PLACEMENTS_PER_TICK){routes.add(job);return;}
            for(Cell sleeve:job.tipTrail){
                if(sleeve.tipBehind==0)continue;
                int r=tipRadius(Math.min(MAX_TENDON_RADIUS,(sleeve.group.root().diameter-1)/2),sleeve.tipBehind);
                int width=r*2+1;
                if(sleeve.brushRadius!=r||sleeve.brushCursor<width*width*width){int before=sleeve.brushCursor;thicken(level,sleeve);
                    if(sleeve.brushCursor!=before)job.lastProgress=time;
                    routes.add(job);return;}
            }
            BlockPos next=job.path.get(job.cursor);
            if(!budget()||comparisons>=MAX_COMPARISONS_PER_TICK){routes.add(job);return;}
            if(!woundSpace(job.head.group,next)){finishRoute(job);return;}
            Snapshot nextSnapshot=snapshots.get(Key.of(level,next));
            if(job.span&&!next.equals(job.goal)&&nextSnapshot!=null&&nextSnapshot.known.get(index(next))&&!nextSnapshot.healable(index(next))){
                if(!replan(job,level)){finishRoute(job);return;}routes.add(job);return;
            }
            if(next.equals(job.goal)&&job.span){int status=supportStatus(level,next,job.head.group);
                if(status==0){routes.add(job);return;}finishRoute(job,false,status==1);return;}
            if(next.equals(job.goal)&&routeAnchor(level,next,job.head.group)){finishRoute(job,false,true);return;}
            if(!routeSpace(level,next,job.head.group)) {
                if(!budget()||comparisons>=MAX_COMPARISONS_PER_TICK){routes.add(job);return;}
                if(!replan(job,level)){finishRoute(job);return;}
                routes.add(job);return;
            }
            Key key=Key.of(level,next);Snapshot snap=snapshots.get(key);
            Cell step=cells.get(new Position(job.dimension,next.asLong()));
            if(step==null&&cells.size()>=MAX_WOUND_CELLS+MAX_ROUTE_EXTRA_CELLS){routes.add(job);return;}
            if(occupied(level,next)){routes.add(job);return;}
            BlockState current=level.getBlockState(next);
            if(!current.is(tendon().getBlock())&&!place(level,next,tendon())){routes.add(job);return;}
            if(step==null){step=new Cell(key,index(next),current,time);attach(snap,step);}
            Group.join(step.group,job.head.group);
            Direction heading=Direction.UP;
            for(Direction value:DIRECTIONS)if(job.head.pos.relative(value).equals(next)){heading=value;break;}
            BlockPos frameStart=job.cursor>=3?job.path.get(job.cursor-3):job.head.pos;
            BlockPos frameEnd=job.path.get(Math.min(job.path.size()-1,job.cursor+3));
            step.tangentX=frameEnd.getX()-frameStart.getX();step.tangentY=frameEnd.getY()-frameStart.getY();step.tangentZ=frameEnd.getZ()-frameStart.getZ();
            step.phase=1;step.axis=heading.ordinal();step.depth=job.head.depth+1;step.grown=time;
            step.scaffold=job.span;
            step.anchor=job.anchor;job.steps++;job.lastProgress=time;step.group.root().lastGrowth=time;
            step.detourTarget=job.goal;step.next=time+tendonGrowthInterval(step.group.root().diameter);
            job.tipTrail.add(step);
            int behind=job.tipTrail.size()-1;
            for(Cell tip:job.tipTrail)tip.tipBehind=behind--;
            while(job.tipTrail.size()>8){Cell tail=job.tipTrail.remove();tail.tipBehind=8;}
            snap.revision++;hold(job,step);job.head=step;
            job.next=time+Math.max(1,growthInterval(step)/8);
            if(++job.cursor==job.path.size()){finishRoute(job,false,!job.span);return;}
            routes.add(job);
        }
        void thicken(ServerLevel level, Cell core) {
            if(core.tangentX==0&&core.tangentY==0&&core.tangentZ==0){
                if(core.anchor!=null&&core.detourTarget!=null){core.tangentX=core.detourTarget.getX()-core.anchor.getX();core.tangentY=core.detourTarget.getY()-core.anchor.getY();core.tangentZ=core.detourTarget.getZ()-core.anchor.getZ();}
                else {Direction forward=DIRECTIONS[core.axis];core.tangentX=forward.getStepX();core.tangentY=forward.getStepY();core.tangentZ=forward.getStepZ();}
            }
            int maximum=Math.min(MAX_TENDON_RADIUS,(core.group.root().diameter-1)/2);
            int behind=core.tipBehind; // A stationary tip never inflates into a sphere.
            int radius=tipRadius(maximum,behind),width=radius*2+1,total=width*width*width;
            if(core.brushRadius!=radius){core.brushRadius=radius;core.brushCursor=0;}
            for(int work=0;work<64&&core.brushCursor<total&&budget()&&comparisons<MAX_COMPARISONS_PER_TICK;work++) {
                int i=core.brushCursor,x=i%width-radius,y=(i/width)%width-radius,z=i/(width*width)-radius;
                if((x==0&&y==0&&z==0)||!organicTube(core.anchor==null?0:core.anchor.asLong(),core.depth,x,y,z,core.tangentX,core.tangentY,core.tangentZ,radius)){core.brushCursor++;continue;}
                BlockPos pos=core.pos.offset(x,y,z);Key key=Key.of(level,pos);Snapshot snap=snapshots.get(key);
                if(!woundSpace(core.group,pos)||snap!=null&&snap.edited.get(index(pos))){core.brushCursor++;continue;}
                if(snap==null){request(level,key);return;}
                if(snap.loading!=null||!snap.known.get(index(pos))||loaded(level,key)==null)return;
                if(snap.failed||snap.get(index(pos)).hasBlockEntity()){core.brushCursor++;continue;}
                Cell shell=cells.get(new Position(core.key.dimension,pos.asLong()));
                if(shell!=null&&shell.phase!=0&&shell.phase!=4){core.brushCursor++;continue;}
                comparisons++;BlockState state=level.getBlockState(pos);
                if(!state.isAir()&&!state.is(tendon().getBlock())){core.brushCursor++;continue;}
                if(!snap.healable(index(pos))){core.brushCursor++;continue;}
                if(occupied(level,pos)){core.brushCursor++;continue;}
                if(placements>=MAX_PLACEMENTS_PER_TICK)return;
                if(shell==null&&cells.size()>=MAX_WOUND_CELLS+MAX_ROUTE_EXTRA_CELLS-8192)return;
                if(!state.is(tendon().getBlock())&&!placeFill(level,pos,tendon(),core.group))return;
                if(shell==null){shell=new Cell(key,index(pos),state,time);attach(snap,shell);}
                Group.join(shell.group,core.group);shell.phase=2;shell.anchor=core.anchor;shell.grown=time;
                shell.group.root().lastGrowth=time;
                shell.depth=core.depth;
                shell.axis=core.axis;shell.detourTarget=core.detourTarget;shell.scaffold=core.scaffold;shell.tipBehind=core.tipBehind;snap.revision++;
                if(core.detourTarget!=null)for(RouteJob job:routes)if(job.held.contains(core.position()))hold(job,shell);
                core.brushCursor++;return; // One voxel per brush visit, never a whole cross-section.
            }
        }
        boolean placeRepair(ServerLevel level,Cell cell,BlockState state){
            return placeFill(level,cell.pos,state,cell.group);
        }
        boolean placeFill(ServerLevel level,BlockPos pos,BlockState state,Group group){
            if(!group.root().filling)return place(level,pos,state);
            Position tile=new Position(level.dimension().identifier().toString(),fillTile(pos));
            if(filledThisTick.contains(tile))return false;
            if(!place(level,pos,state))return false;
            filledThisTick.add(tile);return true;
        }
        boolean place(ServerLevel level,BlockPos pos,BlockState state) {
            if(placements>=Math.min(MAX_PLACEMENTS_PER_TICK,placementLimit)||!budget())return false;
            boolean changed=level.setBlock(pos,state,3);
            if(changed)placements++;return changed;
        }
        boolean occupied(ServerLevel level,BlockPos pos) {return !level.getEntities((Entity)null,new AABB(pos),e->e.isAlive()&&!e.isSpectator()).isEmpty();}
        void tick() {
            if(closed)return;
            filledThisTick.clear();
            long start=System.nanoTime();time++;comparisons=0;placements=0;sectionChecks=0;discoveries=0;
            if(time%200==0)refreshLevels();
            if(time%20==0)flushExclusions();
            boolean active=!cells.isEmpty()||!routes.isEmpty()||!probes.isEmpty()||!supportQueue.isEmpty()||!deferred.isEmpty()||!exemptExplosions.isEmpty();
            if(!active)activeSince=time;
            placementLimit=healingRate(cells.size(),Math.max(0,time-activeSince));
            boolean recording=pendingIO>0||scanning!=null||!scans.isEmpty()&&scans.peek().knownCount<4096;
            deadline=start+(active?TICK_BUDGET_NANOS:recording?RECORDING_BUDGET_NANOS:QUIET_BUDGET_NANOS);
            int operations=0,repairChecks=0,routeChecks=0,deferredChecks=0;
            // Rotate all work classes, including IO completion and discovery, so a massive wound cannot starve recording.
            while(budget() && operations++<4096) {
                switch(phase) {
                    case 0 -> {Runnable ready=finished.poll();if(ready!=null)ready.run();else discover();}
                    case 1 -> {if(comparisons<MAX_COMPARISONS_PER_TICK)scan();}
                    case 2 -> {if(placements<MAX_PLACEMENTS_PER_TICK&&repairChecks++<256)repair();}
                    case 3 -> {if(deferredChecks++<128)processDeferred();}
                    case 4 -> {if(routeChecks++<16){if(!supportQueue.isEmpty()&&(routeChecks%3==0||routes.isEmpty()&&probes.isEmpty()))supportStep();
                        else if(!probes.isEmpty()&&(routeChecks%2==0||routes.isEmpty()))probeSpan();else route();}}
                    default -> {if(operations%10==4)discover();else if(comparisons<MAX_COMPARISONS_PER_TICK)scan();}
                }
                if(++phase==6)phase=0;
                if(operations%6==0) {
                    boolean scanReady=comparisons<MAX_COMPARISONS_PER_TICK&&sectionChecks<MAX_SECTION_CHECKS_PER_TICK
                            &&(scanning!=null||!scans.isEmpty()&&scans.peek().nextScan<=time);
                    boolean repairReady=repairChecks<256&&placements<MAX_PLACEMENTS_PER_TICK&&!repairs.isEmpty();
                    boolean routeReady=routeChecks<16&&(!routes.isEmpty()||!probes.isEmpty()||!supportQueue.isEmpty());
                    boolean editReady=deferredChecks<128&&(!exemptExplosions.isEmpty()||!deferred.isEmpty()&&deferred.peek().tick<time);
                    if(!scanReady&&!repairReady&&!routeReady&&!editReady&&finished.isEmpty()&&(discoveries>=32||server.getPlayerList().getPlayers().isEmpty()))break;
                }
                if(scanning==null&&scans.isEmpty()&&repairs.isEmpty()&&routes.isEmpty()&&probes.isEmpty()&&supportQueue.isEmpty()&&deferred.isEmpty()&&pendingIO==0&&server.getPlayerList().getPlayers().isEmpty())break;
            }
            long elapsed=System.nanoTime()-start;ticks++;elapsedNanos+=elapsed;peakNanos=Math.max(peakNanos,elapsed);
            if(time-lastWarning>=1200 && (estimatedBytes>=SNAPSHOT_CACHE_BYTES-16384||cells.size()>=MAX_WOUND_CELLS)) {
                lastWarning=time;LOG.warn("Regeneration capacity reached: {} snapshot bytes, {} wound cells; coverage waits for capacity",estimatedBytes,cells.size());
            }
            if(time%1200==0) {
                coverage.keySet().removeIf(id->server.getPlayerList().getPlayer(id)==null);
                if(LOG_METRICS)LOG.info("Regeneration: {} sections, {} cells, {} deferred sections, {} bytes, mean {} us/tick, peak {} us, {} IO tasks",snapshots.size(),cells.size(),backlog.size(),estimatedBytes,elapsedNanos/Math.max(1,ticks)/1000,peakNanos/1000,pendingIO);
            }
        }
        @Override public void close() {
            closed=true;
            time++;
            while(!deferred.isEmpty()||!exemptExplosions.isEmpty())processDeferred();
            try {
                io.submit(()->{}).get(60,TimeUnit.SECONDS);
                Runnable ready;while((ready=finished.poll())!=null)ready.run();
            }catch(Exception error){LOG.error("Cannot finish terrain memory loads before shutdown",error);}
            // Shutdown is outside the tick budget. Queue final immutable states after prior writes.
            for(Snapshot snap:snapshots.values()) if(!snap.failed&&snap.loading==null) {
                Disk disk=detach(snap);io.execute(()->{
                    try{write(file(snap.key),disk);}catch(RuntimeException error){LOG.error("Final terrain checkpoint failed: {}",file(snap.key),error);}
                });
            }
            // Sparse edits can wait outside the resident cache when it is full. Preserve them too.
            Set<Key> pending=new HashSet<>(beforeCapture.keySet());pending.addAll(accepted.keySet());
            for(Key key:pending) {
                Map<Integer,String> before=encodedEdits(beforeCapture.get(key)),after=encodedEdits(accepted.get(key));
                Set<Integer> removals=new HashSet<>();
                Map<Integer,BlockState> pendingAccepted=accepted.get(key);
                if(pendingAccepted!=null)pendingAccepted.forEach((i,state)->{if(isRemoval(state))removals.add(i);});
                io.execute(()->{
                    try{mergeEdits(file(key),before,after,Set.copyOf(removals));}catch(RuntimeException error){LOG.error("Final terrain edits could not be preserved: {}",file(key),error);}
                });
            }
            io.shutdown();
            try {if(!io.awaitTermination(60,TimeUnit.SECONDS))LOG.error("Terrain memory shutdown timed out; pending writes may be incomplete");}
            catch(InterruptedException ex){Thread.currentThread().interrupt();LOG.error("Interrupted saving terrain memory",ex);}
            scratch.release();
        }
    }

    private static Map<Integer,String> encodedEdits(Map<Integer,BlockState> edits) {
        if(edits==null)return Map.of();
        Map<Integer,String> encoded=new HashMap<>();edits.forEach((i,state)->encoded.put(i,encode(state)));return Map.copyOf(encoded);
    }
    private static boolean isRemoval(BlockState state) {
        return state.isAir()||state.canBeReplaced()||state.getBlock() instanceof LiquidBlock;
    }
    private static void mergeEdits(Path file,Map<Integer,String> before,Map<Integer,String> accepted,Set<Integer> removals) {
        Disk disk=read(file);
        ArrayList<String> palette=new ArrayList<>(disk==null?List.of("minecraft:air"):Arrays.asList(disk.palette));
        char[] states=disk==null||disk.states==null?new char[4096]:disk.states.clone();
        BitSet known=disk==null?new BitSet(4096):BitSet.valueOf(disk.known);
        BitSet edited=disk==null?new BitSet(4096):BitSet.valueOf(disk.edited);
        BitSet healed=disk==null?new BitSet(4096):BitSet.valueOf(disk.healed);
        Map<Integer,String> updates=new HashMap<>();
        before.forEach((i,state)->{if(!known.get(i))updates.put(i,state);});updates.putAll(accepted);
        ArrayList<DiskCell> cells=new ArrayList<>();
        if(disk!=null)for(DiskCell cell:disk.cells) {
            if(cell.phase!=0&&removals.contains(cell.index)) {
                updates.remove(cell.index); // A broken tendon retains its original solid terrain baseline.
                cells.add(new DiskCell(cell.index,accepted.get(cell.index),cell.phase==4?4:3,cell.axis,cell.depth,cell.age,cell.grownAge,
                        Math.min(cell.delay,tendonGrowthInterval(cell.diameter)),cell.diameter,cell.detour,cell.target,cell.scaffold,cell.connected,
                        cell.anchored,cell.anchor,cell.tipBehind,cell.filling,cell.networkStarts,cell.networkAge));
            } else if(!accepted.containsKey(cell.index))cells.add(cell);
        }
        for(var entry:updates.entrySet()) {
            int i=entry.getKey();
            if(accepted.containsKey(i)) {
                edited.set(i);healed.clear(i);if(!known.get(i)) {
                    int air=palette.indexOf("minecraft:air");if(air<0){air=palette.size();palette.add("minecraft:air");}
                    states[i]=(char)air;known.set(i);
                }
                continue;
            }
            if(known.get(i)||edited.get(i))continue;
            int id=palette.indexOf(entry.getValue());if(id<0){id=palette.size();palette.add(entry.getValue());}
            if(id>65535)throw new IllegalStateException("Terrain palette overflow while preserving edits");
            states[i]=(char)id;known.set(i);
        }
        write(file,new Disk(palette.toArray(String[]::new),states,known.toLongArray(),edited.toLongArray(),healed.toLongArray(),cells,disk==null?0:disk.trauma));
    }

    private static boolean terrain(BlockState state) {
        return !state.isAir() && !state.hasBlockEntity() && !(state.getBlock() instanceof LiquidBlock)
                && !(state.getBlock() instanceof LeavesBlock)
                && state.blocksMotion() && !state.is(BlockTags.LEAVES) && !state.is(BlockTags.FLOWERS)
                && !state.is(BlockTags.CROPS) && !state.canBeReplaced();
    }
    private static String encode(BlockState state) {
        StringBuilder result=new StringBuilder(BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
        for(Property<?> property:state.getProperties())result.append('|').append(property.getName()).append('=').append(stateValueName(state,property));
        return result.toString();
    }
    private static <T extends Comparable<T>> String stateValueName(BlockState state,Property<T> property) {return property.getName(state.getValue(property));}
    private static BlockState parse(String encoded) {
        String[] pieces=encoded.split("\\|");
        Block block=BuiltInRegistries.BLOCK.getOptional(Identifier.parse(pieces[0])).orElseThrow(()->new IllegalStateException("Unknown saved block "+pieces[0]));
        BlockState state=block.defaultBlockState();
        for(int i=1;i<pieces.length;i++) {
            String[] pair=pieces[i].split("=",2);Property<?> property=block.getStateDefinition().getProperty(pair[0]);
            if(property==null||pair.length!=2)throw new IllegalStateException("Unknown saved property "+pieces[i]);
            state=withValue(state,property,pair[1]);
        }
        return state;
    }
    private static <T extends Comparable<T>> BlockState withValue(BlockState state,Property<T> property,String value) {
        return state.setValue(property,property.getValue(value).orElseThrow(()->new IllegalStateException("Unknown saved value "+value)));
    }
    private static Disk read(Path file) {
        if(!Files.exists(file))return null;
        try(DataInputStream in=new DataInputStream(new BufferedInputStream(new GZIPInputStream(Files.newInputStream(file))))) {
            int version=in.readInt();
            if(version<1||version>FORMAT)throw new IOException("Unsupported terrain memory format");
            int count=in.readInt();if(count<1||count>65536)throw new IOException("Invalid palette length");
            String[] palette=new String[count];for(int i=0;i<count;i++)palette[i]=in.readUTF();
            char[] states=null;if(in.readBoolean()){states=new char[4096];for(int i=0;i<4096;i++){states[i]=in.readChar();if(states[i]>=count)throw new IOException("Invalid palette index");}}
            int length=in.readInt();if(length<0||length>64)throw new IOException("Invalid known mask");
            long[] known=new long[length];for(int i=0;i<length;i++)known[i]=in.readLong();
            int editLength=version>=3?in.readInt():0;if(editLength<0||editLength>64)throw new IOException("Invalid edit mask");
            long[] edited=new long[editLength];for(int i=0;i<editLength;i++)edited[i]=in.readLong();
            int healedLength=version>=4?in.readInt():0;if(healedLength<0||healedLength>64)throw new IOException("Invalid healed mask");
            long[] healed=new long[healedLength];for(int i=0;i<healedLength;i++)healed[i]=in.readLong();
            int trauma=version>=6?in.readInt():0;if(trauma<0||trauma>64)throw new IOException("Invalid adaptation level");
            int wounds=in.readInt();if(wounds<0||wounds>4096)throw new IOException("Invalid wound count");
            ArrayList<DiskCell> cells=new ArrayList<>();BitSet seen=new BitSet(4096);
            for(int i=0;i<wounds;i++) {
                int index=in.readInt();String observed=in.readUTF();int phase=in.readInt(),axis=in.readInt(),depth=in.readInt();
                long age=in.readLong(),grown=in.readLong(),wait=in.readLong();int diameter=in.readInt();
                boolean detour=version>=2&&in.readBoolean();long target=detour?in.readLong():0;
                boolean scaffold=version>=3&&in.readBoolean(),connected=version>=3&&in.readBoolean();
                boolean anchored=version>=4&&in.readBoolean();long anchor=anchored?in.readLong():0;int tipBehind=version>=4?in.readInt():0;
                boolean filling=version>=5&&in.readBoolean();int networkStarts=version>=5?in.readInt():0;long networkAge=version>=5?in.readLong():age;
                if(networkStarts<0||networkAge<0)throw new IOException("Invalid network phase");
                if(index<0||index>=4096||seen.get(index)||phase<0||phase>4||axis<0||axis>=6||depth<0||age<0||grown<0||wait<0||diameter<1||diameter>15||tipBehind<0)throw new IOException("Invalid wound metadata");
                seen.set(index);cells.add(new DiskCell(index,observed,phase,axis,depth,age,grown,wait,diameter,detour,target,scaffold,connected,anchored,anchor,tipBehind,filling,networkStarts,networkAge));
            }
            return new Disk(palette,states,known,edited,healed,cells,trauma);
        }catch(IOException ex){throw new CompletionException(ex);}
    }
    private static void write(Path file,Disk disk) {
        Path temporary=file.resolveSibling(file.getFileName()+".tmp");
        try {
            Files.createDirectories(file.getParent());
            try(DataOutputStream out=new DataOutputStream(new BufferedOutputStream(new GZIPOutputStream(Files.newOutputStream(temporary))))) {
                out.writeInt(FORMAT);out.writeInt(disk.palette.length);for(String state:disk.palette)out.writeUTF(state);
                out.writeBoolean(disk.states!=null);if(disk.states!=null)for(char value:disk.states)out.writeChar(value);
                out.writeInt(disk.known.length);for(long value:disk.known)out.writeLong(value);
                out.writeInt(disk.edited.length);for(long value:disk.edited)out.writeLong(value);
                out.writeInt(disk.healed.length);for(long value:disk.healed)out.writeLong(value);
                out.writeInt(disk.trauma);
                out.writeInt(disk.cells.size());for(DiskCell cell:disk.cells) {
                    out.writeInt(cell.index);out.writeUTF(cell.observed);out.writeInt(cell.phase);out.writeInt(cell.axis);out.writeInt(cell.depth);
                    out.writeLong(cell.age);out.writeLong(cell.grownAge);out.writeLong(cell.delay);out.writeInt(cell.diameter);
                    out.writeBoolean(cell.detour);if(cell.detour)out.writeLong(cell.target);
                    out.writeBoolean(cell.scaffold);out.writeBoolean(cell.connected);
                    out.writeBoolean(cell.anchored);if(cell.anchored)out.writeLong(cell.anchor);out.writeInt(cell.tipBehind);
                    out.writeBoolean(cell.filling);out.writeInt(cell.networkStarts);out.writeLong(cell.networkAge);
                }
            }
            try{Files.move(temporary,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
            catch(AtomicMoveNotSupportedException ex){Files.move(temporary,file,StandardCopyOption.REPLACE_EXISTING);}
        }catch(IOException ex){throw new CompletionException(ex);}
    }
}

// 26.1.2
