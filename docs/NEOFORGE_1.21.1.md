# NeoForge / Minecraft 1.21.1 Reference

### 1. Dual-Version Editing Protocol — READ FIRST
You are editing two source trees for the same mod: one targeting **1.21.1** and one targeting **1.21.8**. Before touching any file:
1. **Identify which tree you're in.** Check the build file or folder path. Never assume.
2. **Never "fix" 1.21.1 code with 1.21.8 patterns or vice versa.** Old raw lookups will fail on 1.21.8, and new `Optional` getters will fail to compile on 1.21.1.
3. **When a fix is needed in both trees**, apply the 1.21.1-style fix to 1.21.1 and the 1.21.8-style unwrap to 1.21.8.

---


### 7. Level Height APIs (1.21.1 vs 1.21.8)
* **World Min/Max Height Methods**:
  * 1.21.1: `level.getMinBuildHeight()` and `level.getMaxBuildHeight()`
  * 1.21.8: `level.getMinY()` and `level.getMaxY()`

---

### 8. Block Tag & Block Class Checks (Torches, Redstone, Slabs & Stairs)
* **Vanilla BlockTags Gotchas**:
  * Minecraft does **NOT** have a `BlockTags.TORCHES` tag in vanilla `BlockTags`. Attempting to reference `BlockTags.TORCHES` results in compile-time symbol resolution failure.
* **Torch & Redstone Wire Identification**:
  * Check base class inheritance or explicit vanilla block instances:
  ```java
  // Correctly catches all standard, soul, and redstone torches (floor and wall variants), and redstone wire:
  if (block instanceof net.minecraft.world.level.block.TorchBlock 
      || block instanceof net.minecraft.world.level.block.WallTorchBlock 
      || block instanceof net.minecraft.world.level.block.RedStoneWireBlock 
      || block == Blocks.TORCH || block == Blocks.WALL_TORCH 
      || block == Blocks.SOUL_TORCH || block == Blocks.SOUL_WALL_TORCH 
      || block == Blocks.REDSTONE_TORCH || block == Blocks.REDSTONE_WALL_TORCH 
      || block == Blocks.REDSTONE_WIRE) {
      // Preserve block / skip infection
  }
  ```
* **Slabs & Stairs State Preservation**:
  * Slabs: `state.is(BlockTags.SLABS) || state.getBlock() instanceof net.minecraft.world.level.block.SlabBlock`
  * Stairs: `state.is(BlockTags.STAIRS) || state.getBlock() instanceof net.minecraft.world.level.block.StairBlock`
  * Always copy block state properties (`TYPE`, `FACING`, `HALF`, `SHAPE`, `WATERLOGGED`) using `state.hasProperty(...)` and `state.getValue(...)` when mutating into vanilla Oak variants.

---

### 9. Prosopagnosia & Client Visual Override Architecture (1.21.1)

#### A. Nametag Hiding (`RenderNameTagEvent`)
* In 1.21.1, `RenderNameTagEvent` directly supports `setCanRender(TriState)` and `setContent(Component)`:
```java
@SubscribeEvent
public static void onRenderNameTag(RenderNameTagEvent event) {
    Minecraft mc = Minecraft.getInstance();
    if (mc.player != null && hasProsopagnosia(mc.player)) {
        event.setCanRender(TriState.FALSE);
        event.setContent(Component.empty());
    }
}
```

#### B. Fog Distance & Spherical Fog Override (`ViewportEvent.RenderFog`)
* In 1.21.1, `ViewportEvent.RenderFog` is cancellable via `event.setCanceled(true)`. Cancelling is mandatory for custom near/far plane distances to override vanilla values:
```java
private static final float FOG_START = 56.0f;
private static final float FOG_END = 96.0f;

@SubscribeEvent
public static void onRenderFog(ViewportEvent.RenderFog event) {
    Minecraft mc = Minecraft.getInstance();
    if (mc.player == null || !hasProsopagnosia(mc.player)) {
        return;
    }
    // Preserve underwater, lava, and powder snow fog
    if (event.getType() != FogType.NONE) {
        return;
    }
    event.setNearPlaneDistance(FOG_START);
    event.setFarPlaneDistance(FOG_END);
    event.setFogShape(FogShape.SPHERE);
    event.setCanceled(true); // Required to take effect in 1.21.1
}
```

#### C. Sky Disc & Celestial Blackout (`RenderLevelStageEvent.AFTER_SKY`)
* In 1.21.1, custom geometry can be rendered directly into `RenderLevelStageEvent.Stage.AFTER_SKY` using the `Tesselator` and `BufferBuilder` immediately after the skybox is drawn:
```java
@SubscribeEvent
public static void onRenderLevelStage(RenderLevelStageEvent event) {
    if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_SKY) {
        return;
    }
    Minecraft mc = Minecraft.getInstance();
    if (mc.player == null || !hasProsopagnosia(mc.player)) {
        return;
    }
    Matrix4f mv = new Matrix4f(event.getModelViewMatrix());
    Matrix4fStack stack = RenderSystem.getModelViewStack();
    stack.pushMatrix();
    stack.identity();
    RenderSystem.applyModelViewMatrix();
    RenderSystem.disableDepthTest();
    RenderSystem.depthMask(false);
    RenderSystem.disableBlend();
    RenderSystem.disableCull();
    RenderSystem.setShader(GameRenderer::getPositionColorShader);
    BufferBuilder b = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
    float s = 50.0f;
    float[][] faces = {
        {-s, -s, -s, s, -s, -s, s, s, -s, -s, s, -s},
        {-s, -s, s, s, -s, s, s, s, s, -s, s, s},
        {-s, -s, -s, -s, -s, s, -s, s, s, -s, s, -s},
        {s, -s, -s, s, -s, s, s, s, s, s, s, -s},
        {-s, -s, -s, s, -s, -s, s, -s, s, -s, -s, s},
        {-s, s, -s, s, s, -s, s, s, s, -s, s, s}
    };
    for (float[] f : faces) {
        for (int i = 0; i < 12; i += 3) {
            b.addVertex(mv, f[i], f[i + 1], f[i + 2]).setColor(0, 0, 0, 255);
        }
    }
    BufferUploader.drawWithShader(b.buildOrThrow());
    RenderSystem.enableCull();
    RenderSystem.depthMask(true);
    RenderSystem.enableDepthTest();
    stack.popMatrix();
    RenderSystem.applyModelViewMatrix();
}
```

#### D. Humanoid Re-Texturing Buffer Delegation (`MultiBufferSource` Wrapper)
* In 1.21.1, entity textures are intercepted by wrapping `MultiBufferSource` inside `RenderPlayerEvent.Pre` and `RenderLivingEvent.Pre`:
```java
private static MultiBufferSource wrapBuffers(MultiBufferSource original, ResourceLocation texture) {
    RenderType splinterType = RenderType.entityCutoutNoCull(texture);
    return renderType -> {
        if (renderType != null) {
            String str = renderType.toString().toLowerCase(java.util.Locale.ROOT);
            if (str.contains("atlas") || str.contains("item") || str.contains("block") 
                || str.contains("glint") || str.contains("gui") || str.contains("crumbling")) {
                return original.getBuffer(renderType);
            }
            if (str.contains("entity") || str.contains("armor") || str.contains("player")) {
                return original.getBuffer(splinterType);
            }
        }
        return original.getBuffer(renderType);
    };
}
```

---



### 1. Multi-Version Support & File Conventions
* Minecraft `1.21.1` and `1.21.8` (1.21.4+) files are maintained in parallel in `/Fractus/`.
* The last line of `FractusLaserBeam` must strictly be `// 1.21.1` or `// 1.21.8`.

