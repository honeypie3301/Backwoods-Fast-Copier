package net.mcreator.thebackwoods;

import java.util.Map;
import java.util.WeakHashMap;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Mob;
import org.joml.Quaternionf;

/** Renderer-only surface frame; model bones, animations and texture layers are unchanged. */
@net.neoforged.fml.common.EventBusSubscriber(modid = "the_backwoods", bus = net.neoforged.fml.common.EventBusSubscriber.Bus.MOD, value = net.neoforged.api.distmarker.Dist.CLIENT)
public final class LignumSurfaceRendering {
    private LignumSurfaceRendering() {}
    public record Frame(Quaternionf rotation, double pivot, double modelHalfHeight) {}
    private record Blend(Direction face, Quaternionf from, Quaternionf to, double at) {}
    private static final Map<Mob, Blend> FRAMES = new WeakHashMap<>();

    private static Quaternionf sample(Blend blend, double time) {
        float progress = (float)Math.max(0, Math.min(1, (time - blend.at) / 3.0));
        return new Quaternionf(blend.from).slerp(blend.to, progress).normalize();
    }

    public static Frame capture(Mob mob, float partialTick) {
        Direction face = mob instanceof LignumSurfaceNavigation.Crawler crawler ? crawler.surfaceFace() : Direction.UP;
        double time = mob.tickCount + Math.max(0, Math.min(1, partialTick));
        Blend blend = FRAMES.get(mob);
        Quaternionf target = LignumSurfaceNavigation.rotation(face);
        if (blend == null) blend = new Blend(face, target, target, time);
        else if (blend.face != face || time < blend.at)
            blend = new Blend(face, sample(blend, time), target, time);
        FRAMES.put(mob, blend);
        double pivot = (face.getAxis() == Direction.Axis.Y ? mob.getBbHeight() : mob.getBbWidth()) * 0.5;
        return new Frame(sample(blend, time), pivot, mob.getBbHeight() * 0.5);
    }

    public static void apply(PoseStack pose, Frame frame) {
        if (frame == null) return;
        pose.translate(0, frame.pivot, 0);
        pose.mulPose(frame.rotation);
        pose.translate(0, -frame.modelHalfHeight, 0);
    }

    @net.neoforged.bus.api.SubscribeEvent(priority = net.neoforged.bus.api.EventPriority.LOWEST)
    public static void registerRenderers(net.neoforged.neoforge.client.event.EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(net.mcreator.thebackwoods.init.TheBackwoodsModEntities.LIGNUM_VERMIS.get(), SurfaceVermisRenderer::new);
        event.registerEntityRenderer(net.mcreator.thebackwoods.init.TheBackwoodsModEntities.LIGNUM_TRILOBITA.get(), SurfaceTrilobitaRenderer::new);
        event.registerEntityRenderer(net.mcreator.thebackwoods.init.TheBackwoodsModEntities.PETRIFIED_LIGNUM_TRILOBITA.get(), SurfacePetrifiedTrilobitaRenderer::new);
    }

    public static final class SurfaceVermisRenderer extends net.mcreator.thebackwoods.client.renderer.LignumVermisRenderer {
        public SurfaceVermisRenderer(net.minecraft.client.renderer.entity.EntityRendererProvider.Context context) {
            super(context);
        }

        @Override protected void setupRotations(net.mcreator.thebackwoods.entity.LignumVermisEntity entity, PoseStack pose,
                float age, float yaw, float partial, float scale) {
            apply(pose, capture(entity, partial));
            super.setupRotations(entity, pose, age, yaw, partial, scale);
        }

    }

    public static final class SurfaceTrilobitaRenderer extends net.mcreator.thebackwoods.client.renderer.LignumTrilobitaRenderer {
        public SurfaceTrilobitaRenderer(net.minecraft.client.renderer.entity.EntityRendererProvider.Context context) {
            super(context);
        }

        @Override protected void setupRotations(net.mcreator.thebackwoods.entity.LignumTrilobitaEntity entity, PoseStack pose,
                float age, float yaw, float partial, float scale) {
            apply(pose, capture(entity, partial));
            super.setupRotations(entity, pose, age, yaw, partial, scale);
        }

    }

    public static final class SurfacePetrifiedTrilobitaRenderer extends net.mcreator.thebackwoods.client.renderer.PetrifiedLignumTrilobitaRenderer {
        public SurfacePetrifiedTrilobitaRenderer(net.minecraft.client.renderer.entity.EntityRendererProvider.Context context) {
            super(context);
        }

        @Override protected void setupRotations(net.mcreator.thebackwoods.entity.PetrifiedLignumTrilobitaEntity entity, PoseStack pose,
                float age, float yaw, float partial, float scale) {
            apply(pose, capture(entity, partial));
            super.setupRotations(entity, pose, age, yaw, partial, scale);
        }

    }
}
// 1.21.1
