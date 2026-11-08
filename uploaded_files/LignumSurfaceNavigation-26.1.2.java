package net.mcreator.thebackwoods;

import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.*;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** Shared six-face navigation. Goals keep their normal PathNavigation API. */
@net.neoforged.fml.common.EventBusSubscriber(modid = "the_backwoods")
public final class LignumSurfaceNavigation extends GroundPathNavigation {
    public interface Crawler {
        Direction surfaceFace();
        void surfaceFace(Direction face);
    }

    private record Key(BlockPos block, Direction face) {}
    private record Surface(Key key, Vec3 center) {}
    private record Waypoint(Vec3 center, Key support) {}
    private record Entry(Key key, double cost, double score) {}
    private final Map<Path, List<Waypoint>> plans = new IdentityHashMap<>();
    private List<Waypoint> route;
    private Key anchor;
    private Vec3 desired = Vec3.ZERO;
    private boolean controlling;
    private int stuckTicks, retryAt;
    private BlockPos lastGoal;
    private Path lastPlan;
    private int searchedAt = -100;
    private static long budgetTick = Long.MIN_VALUE;
    private static int remainingNodes;
    private static final double GAP = 0.015;

    public LignumSurfaceNavigation(Mob mob, Level level) {
        super(mob, level);
    }

    private Crawler crawler() { return (Crawler) mob; }

    public static Vec3 normal(Direction face) {
        return new Vec3(face.getStepX(), face.getStepY(), face.getStepZ());
    }

    public static Quaternionf rotation(Direction face) {
        Vec3 n = normal(face);
        return new Quaternionf().rotationTo(0, 1, 0, (float)n.x, (float)n.y, (float)n.z);
    }

    private static Vec3 halfSize(Mob mob, Direction face) {
        double w = mob.getBbWidth() * 0.5, h = mob.getBbHeight() * 0.5;
        return new Vec3(face.getAxis() == Direction.Axis.X ? h : w,
            face.getAxis() == Direction.Axis.Y ? h : w, face.getAxis() == Direction.Axis.Z ? h : w);
    }

    private static AABB box(Mob mob, Vec3 center, Direction face) {
        Vec3 half = halfSize(mob, face);
        return new AABB(center.x - half.x, center.y - half.y, center.z - half.z,
            center.x + half.x, center.y + half.y, center.z + half.z);
    }

    public static void updateBounds(Mob mob) {
        if (!(mob instanceof Crawler crawler)) return;
        Vec3 half = halfSize(mob, crawler.surfaceFace());
        mob.setBoundingBox(box(mob, mob.position().add(0, half.y, 0), crawler.surfaceFace()));
    }

    /** Rotate vanilla's thin eye slice with the body instead of using upright width on walls. */
    private static AABB surfaceEyeBox(Vec3 center, double eyeOffset, double width, Direction face) {
        Vec3 eye = center.add(normal(face).scale(eyeOffset));
        double checkWidth = width * 0.8;
        return AABB.ofSize(eye, face.getAxis() == Direction.Axis.X ? 1.0e-6 : checkWidth,
            face.getAxis() == Direction.Axis.Y ? 1.0e-6 : checkWidth,
            face.getAxis() == Direction.Axis.Z ? 1.0e-6 : checkWidth);
    }

    private static boolean surfaceInWall(Mob mob) {
        if (mob.noPhysics) return false;
        AABB eyes = surfaceEyeBox(mob.getBoundingBox().getCenter(),
            mob.getEyeHeight() - mob.getBbHeight() * 0.5, mob.getBbWidth(), ((Crawler)mob).surfaceFace());
        if (!loaded(mob.level(), eyes)) return false;
        var eyeShape = net.minecraft.world.phys.shapes.Shapes.create(eyes);
        return BlockPos.betweenClosedStream(eyes).anyMatch(pos -> {
            var state = mob.level().getBlockState(pos);
            return !state.isAir() && state.isSuffocating(mob.level(), pos)
                && net.minecraft.world.phys.shapes.Shapes.joinIsNotEmpty(
                    state.getCollisionShape(mob.level(), pos).move(pos.getX(), pos.getY(), pos.getZ()),
                    eyeShape, net.minecraft.world.phys.shapes.BooleanOp.AND);
        });
    }

    private void orient(Direction face) {
        if (crawler().surfaceFace() == face) return;
        Vec3 center = mob.getBoundingBox().getCenter();
        crawler().surfaceFace(face);
        Vec3 half = halfSize(mob, face);
        mob.setPos(center.x, center.y - half.y, center.z);
        updateBounds(mob);
    }

    private static boolean loaded(Level level, AABB box) {
        for (int x = Mth.floor(box.minX) >> 4; x <= Mth.floor(box.maxX) >> 4; x++)
            for (int z = Mth.floor(box.minZ) >> 4; z <= Mth.floor(box.maxZ) >> 4; z++)
                if (!level.hasChunkAt(new BlockPos(x << 4, Mth.floor(box.minY), z << 4))) return false;
        return true;
    }

    private Surface surface(Key key) {
        Vec3 n = normal(key.face), center = key.block.getCenter();
        Vec3 from = center.add(n.scale(0.65)), to = center.subtract(n.scale(0.55));
        if (!loaded(level, new AABB(from, to).inflate(mob.getBbWidth()))) return null;
        BlockHitResult hit = level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mob));
        if (hit.getType() != HitResult.Type.BLOCK || hit.getDirection() != key.face || !hit.getBlockPos().equals(key.block)) return null;
        Vec3 body = hit.getLocation().add(n.scale(mob.getBbHeight() * 0.5 + GAP));
        AABB bounds = box(mob, body, key.face).deflate(0.002);
        return clear(bounds) ? new Surface(key, body) : null;
    }

    private boolean clear(AABB bounds) {
        if (!loaded(level, bounds)) return false;
        for (var shape : level.getBlockCollisions(mob, bounds)) if (!shape.isEmpty()) return false;
        return true;
    }

    private Surface start() {
        Vec3 center = mob.getBoundingBox().getCenter();
        Surface best = anchor == null ? null : surface(anchor);
        double distance = best == null ? 1.0 : best.center.distanceToSqr(center);
        BlockPos base = BlockPos.containing(center);
        for (int x = -1; x <= 1; x++) for (int y = -1; y <= 1; y++) for (int z = -1; z <= 1; z++) {
            BlockPos block = base.offset(x, y, z);
            for (Direction face : Direction.values()) {
                Surface candidate = surface(new Key(block, face));
                if (candidate == null) continue;
                if (Math.abs(candidate.center.subtract(center).dot(normal(face))) > 0.20
                        || !clearSegment(center, candidate.center, face)) continue;
                double d = candidate.center.distanceToSqr(center);
                // Prefer the established face at seams instead of flipping each tick.
                if (face != crawler().surfaceFace()) d += 0.08;
                if (d < distance) { distance = d; best = candidate; }
            }
        }
        return best;
    }

    private Vec3 corner(Surface from, Surface to) {
        if (from.key.face == to.key.face) return null;
        if (from.key.block.equals(to.key.block)) {
            double radius = 0.5 + Math.max(mob.getBbWidth(), mob.getBbHeight()) * 0.5 + GAP;
            return from.key.block.getCenter().add(normal(from.key.face).scale(radius)).add(normal(to.key.face).scale(radius));
        }
        return from.key.block.relative(from.key.face).getCenter();
    }

    private boolean clearSegment(Vec3 from, Vec3 to, Direction face) {
        int steps = Math.max(1, Mth.ceil(from.distanceTo(to) / 0.12));
        for (int i = 0; i <= steps; i++) {
            AABB bounds = box(mob, from.lerp(to, (double)i / steps), face).deflate(0.002);
            if (!clear(bounds)) return false;
        }
        return true;
    }

    private boolean edge(Surface from, Surface to) {
        Vec3 turn = corner(from, to);
        return turn == null ? clearSegment(from.center, to.center, from.key.face)
            : clearSegment(from.center, turn, from.key.face) && clearSegment(turn, to.center, to.key.face)
                && clearSegment(turn, turn, to.key.face);
    }

    @Override public Path createPath(BlockPos target, int accuracy) {
        return createPath(Set.of(target), 8, false, accuracy, 1);
    }

    @Override public Path createPath(Entity target, int accuracy) {
        return createPath(target.blockPosition(), accuracy);
    }

    @Override public BlockPos getTargetPos() {
        return route != null ? lastGoal : super.getTargetPos();
    }

    @Override protected Path createPath(Set<BlockPos> targets, int padding, boolean up, int accuracy, float range) {
        if (targets.isEmpty() || level.isClientSide()) return null;
        BlockPos goal = targets.stream().min(Comparator.comparingDouble(p -> p.distSqr(mob.blockPosition()))).orElseThrow();
        if (lastGoal != null && lastGoal.distSqr(goal) <= 1 && lastPlan != null
                && (!lastPlan.isDone() || mob.tickCount - searchedAt < 5)) return lastPlan;
        if (mob.tickCount < retryAt) return null;
        Surface initial = start();
        if (initial == null) return crawler().surfaceFace() == Direction.UP
            ? super.createPath(targets, padding, up, accuracy, range) : null;
        long now = level.getGameTime();
        if (budgetTick != now) { budgetTick = now; remainingNodes = 2048; }
        if (remainingNodes <= 0) return null;
        Vec3 destination = goal.getCenter();
        PriorityQueue<Entry> open = new PriorityQueue<>(Comparator.comparingDouble(Entry::score));
        Map<Key, Surface> known = new HashMap<>();
        Map<Key, Key> parents = new HashMap<>();
        Map<Key, Double> costs = new HashMap<>();
        Set<Key> closed = new HashSet<>();
        known.put(initial.key, initial); costs.put(initial.key, 0.0);
        open.add(new Entry(initial.key, 0, initial.center.distanceTo(destination)));
        Key best = initial.key;
        double bestDistance = initial.center.distanceTo(destination);
        boolean reached = false;
        while (!open.isEmpty() && closed.size() < 256 && remainingNodes > 0) {
            Entry entry = open.poll();
            if (!closed.add(entry.key)) continue;
            remainingNodes--;
            Surface current = known.get(entry.key);
            double distance = current.center.distanceTo(destination);
            if (distance < bestDistance) { bestDistance = distance; best = current.key; }
            if (distance <= Math.max(0.75, accuracy + 0.5)) { best = current.key; reached = true; break; }
            for (Direction tangent : Direction.values()) {
                if (tangent.getAxis() == current.key.face.getAxis()) continue;
                Key[] nextKeys = {
                    new Key(current.key.block.relative(tangent), current.key.face),
                    new Key(current.key.block, tangent),
                    new Key(current.key.block.relative(current.key.face).relative(tangent), tangent.getOpposite())
                };
                for (Key key : nextKeys) {
                    if (closed.contains(key) || key.block.distSqr(initial.key.block) > 40 * 40) continue;
                    if (!known.containsKey(key)) known.put(key, surface(key));
                    Surface next = known.get(key);
                    if (next == null || !edge(current, next)) continue;
                    Vec3 turn = corner(current, next);
                    double cost = entry.cost + (turn == null ? current.center.distanceTo(next.center)
                        : current.center.distanceTo(turn) + turn.distanceTo(next.center) + 0.2);
                    if (cost >= costs.getOrDefault(key, Double.POSITIVE_INFINITY)) continue;
                    known.put(key, next); costs.put(key, cost); parents.put(key, current.key);
                    open.add(new Entry(key, cost, cost + next.center.distanceTo(destination)));
                }
            }
        }
        searchedAt = mob.tickCount; lastGoal = goal.immutable();
        if (best.equals(initial.key) && !reached) { retryAt = mob.tickCount + 5; return null; }
        LinkedList<Surface> surfaces = new LinkedList<>();
        for (Key key = best; key != null; key = parents.get(key)) surfaces.addFirst(known.get(key));
        List<Waypoint> waypoints = new ArrayList<>();
        Surface previous = null;
        for (Surface node : surfaces) {
            if (previous != null) {
                Vec3 turn = corner(previous, node);
                if (turn != null) {
                    waypoints.add(new Waypoint(turn, previous.key));
                    waypoints.add(new Waypoint(turn, node.key));
                }
            }
            waypoints.add(new Waypoint(node.center, node.key)); previous = node;
        }
        List<Node> nodes = new ArrayList<>();
        for (Waypoint point : waypoints) {
            Vec3 half = halfSize(mob, point.support.face);
            BlockPos pos = BlockPos.containing(point.center.add(0, -half.y, 0));
            nodes.add(new Node(pos.getX(), pos.getY(), pos.getZ()));
        }
        Path result = new Path(nodes, goal, reached);
        if (plans.size() >= 8) plans.clear();
        plans.put(result, waypoints); lastPlan = result;
        return result;
    }

    @Override public boolean moveTo(Path path, double speed) {
        if (path == null) { stop(); return false; }
        List<Waypoint> plan = plans.get(path);
        if (plan == null) { detach(); return super.moveTo(path, speed); }
        if (this.path != path) { this.path = path; route = plan; stuckTicks = 0; }
        speedModifier = speed;
        return !path.isDone();
    }

    @Override public void stop() {
        super.stop(); route = null; desired = Vec3.ZERO;
        lastPlan = null;
        if (crawler().surfaceFace() == Direction.UP) controlling = false;
    }

    @Override protected boolean canUpdatePath() { return true; }

    private Key currentSupport() {
        Direction face = crawler().surfaceFace();
        Vec3 center = mob.getBoundingBox().getCenter();
        Vec3 end = center.subtract(normal(face).scale(mob.getBbHeight() * 0.5 + 0.08));
        if (!loaded(level, new AABB(center, end))) return null;
        BlockHitResult hit = level.clip(new ClipContext(center, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mob));
        return hit.getType() == HitResult.Type.BLOCK && hit.getDirection() == face
            ? new Key(hit.getBlockPos().immutable(), face) : null;
    }

    @Override public void tick() {
        desired = Vec3.ZERO;
        if (!mob.isAlive() || mob.isNoAi() || mob.isPassenger() || mob.isInWater() || mob.isInLava()) {
            detach(); return;
        }
        Key contact = currentSupport();
        if (contact != null) anchor = contact;
        if (route == null || path == null || path.isDone()) {
            if (crawler().surfaceFace() == Direction.UP) { controlling = false; super.tick(); return; }
            Surface support = anchor == null ? start() : surface(anchor);
            if (support == null) { detach(); return; }
            anchor = support.key; controlling = true;
            if (contact == null) {
                // A goal may stop exactly on a corner. Finish settling onto the
                // established face instead of leaving the body suspended off it.
                Vec3 center = mob.getBoundingBox().getCenter();
                if (!clearSegment(center, support.center, support.key.face)) { detach(); return; }
                Vec3 correction = support.center.subtract(center);
                desired = correction.lengthSqr() <= 0.12 * 0.12 ? correction : correction.normalize().scale(0.12);
            }
            return;
        }
        Waypoint next = route.get(path.getNextNodeIndex());
        if (surface(next.support) == null || (anchor != null && surface(anchor) == null)) {
            stop(); retryAt = mob.tickCount + 5;
            if (anchor == null || surface(anchor) == null) detach();
            return;
        }
        controlling = true;
        Vec3 center = mob.getBoundingBox().getCenter();
        if (center.distanceToSqr(next.center) < 0.025 * 0.025) {
            orient(next.support.face); anchor = next.support; path.advance();
            if (path.isDone()) { stop(); return; }
            next = route.get(path.getNextNodeIndex());
        }
        if (crawler().surfaceFace() != next.support.face) {
            orient(next.support.face); anchor = next.support;
        }
        double speed = Math.min(0.6, Math.max(0, mob.getAttributeValue(Attributes.MOVEMENT_SPEED) * speedModifier));
        Vec3 difference = next.center.subtract(mob.getBoundingBox().getCenter());
        desired = difference.lengthSqr() <= speed * speed ? difference : difference.normalize().scale(speed);
    }

    /** Called from the entities' travel hook; vanilla upright gravity/jumping cannot fight attachment. */
    public boolean travel() {
        if (level.isClientSide() || !controlling) return false;
        Vec3 before = mob.position();
        mob.setDeltaMovement(desired);
        mob.move(MoverType.SELF, desired);
        updateBounds(mob);
        mob.setDeltaMovement(Vec3.ZERO);
        mob.setOnGround(false);
        mob.fallDistance = 0;
        if (desired.lengthSqr() > 0.0001) {
            Vector3f local = rotation(crawler().surfaceFace()).conjugate()
                .transform(new Vector3f((float)desired.x, (float)desired.y, (float)desired.z));
            if (local.x * local.x + local.z * local.z > 0.0001) {
                float yaw = (float)Math.toDegrees(Math.atan2(-local.x, local.z));
                mob.setYRot(yaw); mob.setYBodyRot(yaw); mob.setYHeadRot(yaw);
            }
            stuckTicks = mob.position().distanceToSqr(before) < 0.000001 ? stuckTicks + 1 : 0;
            if (stuckTicks >= 8) { stop(); retryAt = mob.tickCount + 10; }
        }
        return true;
    }

    private void detach() {
        stop(); controlling = false; anchor = null;
        orient(Direction.UP);
    }

    /** Camouflage follows the contacted surface rather than always reading below world Y. */
    public static BlockPos supportBlock(Mob mob) {
        if (!(mob instanceof Crawler crawler))
            return BlockPos.containing(mob.getX(), mob.getY() - 0.1, mob.getZ());
        Direction face = crawler.surfaceFace();
        Vec3 center = mob.getBoundingBox().getCenter();
        Vec3 inward = normal(face).scale(-(mob.getBbHeight() * 0.5 + 0.08));
        return BlockPos.containing(center.add(inward));
    }

    /** Install before worlds/entities are loaded, on both logical sides. No generated files are patched. */
    @net.neoforged.bus.api.SubscribeEvent
    public static void install(net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent event) {
        event.enqueueWork(() -> {
            replaceFactory(net.mcreator.thebackwoods.init.TheBackwoodsModEntities.LIGNUM_VERMIS.get(), SurfaceVermis::new);
            replaceFactory(net.mcreator.thebackwoods.init.TheBackwoodsModEntities.LIGNUM_TRILOBITA.get(), SurfaceTrilobita::new);
            replaceFactory(net.mcreator.thebackwoods.init.TheBackwoodsModEntities.PETRIFIED_LIGNUM_TRILOBITA.get(), SurfacePetrifiedTrilobita::new);
        });
    }

    private static <T extends Mob> void replaceFactory(net.minecraft.world.entity.EntityType<T> type,
            net.minecraft.world.entity.EntityType.EntityFactory<T> factory) {
        java.lang.reflect.Field selected = null;
        for (java.lang.reflect.Field field : net.minecraft.world.entity.EntityType.class.getDeclaredFields()) {
            if (java.lang.reflect.Modifier.isStatic(field.getModifiers())
                    || field.getType() != net.minecraft.world.entity.EntityType.EntityFactory.class) continue;
            if (selected != null) throw new IllegalStateException("Multiple entity factory fields; climbing was not installed");
            selected = field;
        }
        if (selected == null || !selected.trySetAccessible())
            throw new IllegalStateException("Cannot access entity factory; climbing was not installed");
        try {
            selected.set(type, factory);
            if (selected.get(type) != factory)
                throw new IllegalStateException("Lignum climbing factory override did not take effect");
            System.getLogger("the_backwoods").log(System.Logger.Level.INFO, "Installed Lignum climbing factory: " + type);
        } catch (IllegalAccessException exception) {
            throw new IllegalStateException("Cannot install Lignum climbing factory", exception);
        }
    }

    /** Inherits the generated entity's goals, procedures, animation states and attributes. */
    public static final class SurfaceVermis extends net.mcreator.thebackwoods.entity.LignumVermisEntity implements Crawler {
        private static final net.minecraft.network.syncher.EntityDataAccessor<Integer> FACE =
            net.minecraft.network.syncher.SynchedEntityData.defineId(SurfaceVermis.class, net.minecraft.network.syncher.EntityDataSerializers.INT);

        public SurfaceVermis(net.minecraft.world.entity.EntityType<net.mcreator.thebackwoods.entity.LignumVermisEntity> type, Level level) {
            super(type, level);
        }

        @Override protected void defineSynchedData(net.minecraft.network.syncher.SynchedEntityData.Builder builder) {
            super.defineSynchedData(builder);
            builder.define(FACE, Direction.UP.ordinal());
        }

        @Override protected net.minecraft.world.entity.ai.navigation.PathNavigation createNavigation(Level level) {
            return new LignumSurfaceNavigation(this, level);
        }

        @Override public Direction surfaceFace() {
            int id = getEntityData().get(FACE);
            return id >= 0 && id < Direction.values().length ? Direction.values()[id] : Direction.UP;
        }

        @Override public void surfaceFace(Direction face) {
            getEntityData().set(FACE, face.ordinal());
            updateBounds(this);
        }

        @Override public boolean isInWall() {
            return surfaceFace() == Direction.UP ? super.isInWall() : surfaceInWall(this);
        }

        @Override public void travel(Vec3 input) {
            if (getNavigation() instanceof LignumSurfaceNavigation navigation && navigation.travel()) return;
            super.travel(input);
        }

        @Override public void tick() {
            super.tick();
            updateBounds(this);
        }

        @Override public void addAdditionalSaveData(net.minecraft.world.level.storage.ValueOutput output) {
            super.addAdditionalSaveData(output);
            output.putInt("LignumSurfaceFace", surfaceFace().ordinal());
        }

        @Override public void readAdditionalSaveData(net.minecraft.world.level.storage.ValueInput input) {
            super.readAdditionalSaveData(input);
            getEntityData().set(FACE, input.getIntOr("LignumSurfaceFace", Direction.UP.ordinal()));
            updateBounds(this);
        }
    }

    /** Inherits the generated entity's goals, procedures, animation states and attributes. */
    public static final class SurfaceTrilobita extends net.mcreator.thebackwoods.entity.LignumTrilobitaEntity implements Crawler {
        private static final net.minecraft.network.syncher.EntityDataAccessor<Integer> FACE =
            net.minecraft.network.syncher.SynchedEntityData.defineId(SurfaceTrilobita.class, net.minecraft.network.syncher.EntityDataSerializers.INT);

        public SurfaceTrilobita(net.minecraft.world.entity.EntityType<net.mcreator.thebackwoods.entity.LignumTrilobitaEntity> type, Level level) {
            super(type, level);
        }

        @Override protected void defineSynchedData(net.minecraft.network.syncher.SynchedEntityData.Builder builder) {
            super.defineSynchedData(builder);
            builder.define(FACE, Direction.UP.ordinal());
        }

        @Override protected net.minecraft.world.entity.ai.navigation.PathNavigation createNavigation(Level level) {
            return new LignumSurfaceNavigation(this, level);
        }

        @Override public Direction surfaceFace() {
            int id = getEntityData().get(FACE);
            return id >= 0 && id < Direction.values().length ? Direction.values()[id] : Direction.UP;
        }

        @Override public void surfaceFace(Direction face) {
            getEntityData().set(FACE, face.ordinal());
            updateBounds(this);
        }

        @Override public boolean isInWall() {
            return surfaceFace() == Direction.UP ? super.isInWall() : surfaceInWall(this);
        }

        @Override public void travel(Vec3 input) {
            if (getNavigation() instanceof LignumSurfaceNavigation navigation && navigation.travel()) return;
            super.travel(input);
        }

        @Override public void tick() {
            super.tick();
            updateBounds(this);
        }

        @Override public void addAdditionalSaveData(net.minecraft.world.level.storage.ValueOutput output) {
            super.addAdditionalSaveData(output);
            output.putInt("LignumSurfaceFace", surfaceFace().ordinal());
        }

        @Override public void readAdditionalSaveData(net.minecraft.world.level.storage.ValueInput input) {
            super.readAdditionalSaveData(input);
            getEntityData().set(FACE, input.getIntOr("LignumSurfaceFace", Direction.UP.ordinal()));
            updateBounds(this);
        }
    }

    /** Inherits the generated entity's goals, procedures, animation states and attributes. */
    public static final class SurfacePetrifiedTrilobita extends net.mcreator.thebackwoods.entity.PetrifiedLignumTrilobitaEntity implements Crawler {
        private static final net.minecraft.network.syncher.EntityDataAccessor<Integer> FACE =
            net.minecraft.network.syncher.SynchedEntityData.defineId(SurfacePetrifiedTrilobita.class, net.minecraft.network.syncher.EntityDataSerializers.INT);

        public SurfacePetrifiedTrilobita(net.minecraft.world.entity.EntityType<net.mcreator.thebackwoods.entity.PetrifiedLignumTrilobitaEntity> type, Level level) {
            super(type, level);
        }

        @Override protected void defineSynchedData(net.minecraft.network.syncher.SynchedEntityData.Builder builder) {
            super.defineSynchedData(builder);
            builder.define(FACE, Direction.UP.ordinal());
        }

        @Override protected net.minecraft.world.entity.ai.navigation.PathNavigation createNavigation(Level level) {
            return new LignumSurfaceNavigation(this, level);
        }

        @Override public Direction surfaceFace() {
            int id = getEntityData().get(FACE);
            return id >= 0 && id < Direction.values().length ? Direction.values()[id] : Direction.UP;
        }

        @Override public void surfaceFace(Direction face) {
            getEntityData().set(FACE, face.ordinal());
            updateBounds(this);
        }

        @Override public boolean isInWall() {
            return surfaceFace() == Direction.UP ? super.isInWall() : surfaceInWall(this);
        }

        @Override public void travel(Vec3 input) {
            if (getNavigation() instanceof LignumSurfaceNavigation navigation && navigation.travel()) return;
            super.travel(input);
        }

        @Override public void tick() {
            super.tick();
            updateBounds(this);
        }

        @Override public void addAdditionalSaveData(net.minecraft.world.level.storage.ValueOutput output) {
            super.addAdditionalSaveData(output);
            output.putInt("LignumSurfaceFace", surfaceFace().ordinal());
        }

        @Override public void readAdditionalSaveData(net.minecraft.world.level.storage.ValueInput input) {
            super.readAdditionalSaveData(input);
            getEntityData().set(FACE, input.getIntOr("LignumSurfaceFace", Direction.UP.ordinal()));
            updateBounds(this);
        }
    }
}
// 26.1.2
