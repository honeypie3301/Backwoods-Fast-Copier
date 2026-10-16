# NeoForge / Minecraft 26.1.2 Comprehensive Reference

## 1. Version Identity & Architecture Protocol — READ FIRST

NeoForge 26.1.2 incorporates modern Mojang mappings and modern engine architecture. Before writing or modifying any Java files in this version:

1. **Verify Your Target Version**: Check file names, directory trees, or comments. Files named with `26.1.2` or targeting the 26.x NeoForge toolchain follow this guide strictly.
2. **Never Backport 1.21.1 or 1.21.8 Syntax**:
   - `ResourceLocation` does NOT exist in 26.1.2; it is renamed to `Identifier`.
   - `CompoundTag` does NOT return `Optional<T>` like 1.21.8, nor raw unchecked getters like 1.21.1. It uses modern built-in fallback methods (`getDoubleOr`, `getBooleanOr`, etc.).
   - `SavedData.Factory` does NOT exist; 26.1.2 uses `SavedDataType<T>` backed by Mojang `Codec`.
   - Entity rendering does NOT accept raw `Entity` parameters in `render(...)`. It uses the modernized decoupled pipeline: `EntityRenderState`, `SubmitNodeCollector`, and `CameraRenderState`.
   - Keyframe animations are NOT animated via `HierarchicalModel.animate(...)`; they are baked into `KeyframeAnimation` instances and invoked via `apply(...)` and `applyWalk(...)`.
3. **Bracketing & Formatting Disciplines**:
   - Always maintain zero bracket discrepancy (`{}` count matches `}`).
   - Never wrap numbers, variables, coordinates, or mathematical expressions in LaTeX or math delimiters (do NOT use math markers like dollar signs or escaped parentheses). Write all numbers and ranges as plain text.

---

## 2. The ResourceLocation to Identifier Migration

Mojang renamed `net.minecraft.resources.ResourceLocation` to `net.minecraft.resources.Identifier`.

* **Import Change**:
  ```java
  // 1.21.1 & 1.21.8 (OLD - WILL NOT COMPILE IN 26.1.2)
  import net.minecraft.resources.ResourceLocation;

  // 26.1.2 (CORRECT)
  import net.minecraft.resources.Identifier;
  ```

* **Instantiation Syntax**:
  ```java
  // Parsing a string identifier:
  Identifier id = Identifier.parse("the_backwoods:rot");

  // Constructing from namespace and path:
  Identifier id = Identifier.fromNamespaceAndPath("the_backwoods", "textures/entities/rotskin.png");
  ```

* **TagKey Creation**:
  ```java
  TagKey<Block> myTag = TagKey.create(Registries.BLOCK, Identifier.parse("the_backwoods:rot_immune"));
  ```

---

## 3. CompoundTag / PersistentData NBT System (`*Or` Methods)

In 26.1.2, Mojang standardized `CompoundTag` reads using direct, type-safe fallback methods (`*Or`). Do NOT use raw getters (1.21.1) or `.orElse(...)` on Optionals (1.21.8).

### Comparison of NBT Read Methods

| Type | 1.21.1 (Raw Type) | 1.21.8 (Optional Wrapper) | 26.1.2 (Mojang *Or Built-in) |
|---|---|---|---|
| `boolean` | `tag.getBoolean("key")` | `tag.getBoolean("key").orElse(false)` | `tag.getBooleanOr("key", false)` |
| `double` | `tag.getDouble("key")` | `tag.getDouble("key").orElse(0.0)` | `tag.getDoubleOr("key", 0.0)` |
| `int` | `tag.getInt("key")` | `tag.getInt("key").orElse(0)` | `tag.getIntOr("key", 0)` |
| `float` | `tag.getFloat("key")` | `tag.getFloat("key").orElse(0.0f)` | `tag.getFloatOr("key", 0.0f)` |
| `long` | `tag.getLong("key")` | `tag.getLong("key").orElse(0L)` | `tag.getLongOr("key", 0L)` |
| `String` | `tag.getString("key")` | `tag.getString("key").orElse("")` | `tag.getStringOr("key", "")` |
| `CompoundTag` | `tag.getCompound("key")` | `tag.getCompound("key").orElse(new CompoundTag())` | `tag.getCompoundOrEmpty("key")` |

### Writing to NBT (Identical to Standard Mappings)
```java
tag.putBoolean("key", true);
tag.putDouble("key", 100.0);
tag.putInt("key", 5);
tag.putString("key", "active");
tag.put("key", childCompoundTag);
tag.putLongArray("key", longArray);
```

---

## 4. Registry Lookups & Dynamic Holder Retrieval

In 26.1.2, registry lookups via `BuiltInRegistries` return `Optional<Holder.Reference<T>>`.

```java
// EntityType Lookup
EntityType<?> rotType = BuiltInRegistries.ENTITY_TYPE.get(Identifier.parse("the_backwoods:rot"))
    .map(h -> h.value())
    .orElse(null);

// MobEffect Lookup
BuiltInRegistries.MOB_EFFECT.get(Identifier.parse("mowziesmobs:frozen")).ifPresent(frozenHolder -> {
    if (mob.hasEffect(frozenHolder)) {
        mob.removeEffect(frozenHolder);
    }
});

// Item Lookup
Item item = BuiltInRegistries.ITEM.get(Identifier.parse("minecraft:totem_of_undying"))
    .map(h -> h.value())
    .orElse(Items.AIR);

// SoundEvent Lookup
SoundEvent sound = BuiltInRegistries.SOUND_EVENT.get(Identifier.parse("entity.warden.sonic_boom"))
    .map(h -> h.value())
    .orElse(null);

// Get Entity Registry Path
String entityPath = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString().toLowerCase();
```

---

## 5. Custom Damage Sources & DamageType Holders

In 26.1.2, creating a custom `DamageSource` uses the level holder directly via `level.holderOrThrow`:

```java
// Correct DamageSource instantiation in 26.1.2
public static DamageSource getBackwoodsDamage(LevelAccessor world, String id, Entity directEntity) {
    Level level = (world instanceof Level l) ? l : null;
    if (level == null) return null;
    String path = id.startsWith("the_backwoods:") ? id : "the_backwoods:" + id;
    
    // level.holderOrThrow with ResourceKey and Identifier
    var holder = level.holderOrThrow(ResourceKey.create(Registries.DAMAGE_TYPE, Identifier.parse(path)));
    return directEntity != null ? new DamageSource(holder, directEntity) : new DamageSource(holder);
}
```

* **Built-in Vanilla Damage Sources**:
  ```java
  DamageSource fallSource = level.damageSources().fall();
  DamageSource genericSource = level.damageSources().generic();
  DamageSource mobAttackSource = livingAttacker.damageSources().mobAttack(livingAttacker);
  DamageSource explosionSource = level.damageSources().explosion(null);
  DamageSource witherSource = level.damageSources().wither();
  ```

---

## 6. World Height & Spatial APIs

* **Minimum and Maximum Level Coordinates**:
  * 1.21.1: `level.getMinBuildHeight()` and `level.getMaxBuildHeight()`
  * 26.1.2: `level.getMinY()` and `level.getMaxY()`

* **Heightmap Lookups**:
  ```java
  int surfaceY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, blockX, blockZ);
  ```

* **Ground Finding Helper in 26.1.2**:
  ```java
  private static double findGroundY(LevelAccessor world, Entity entity) {
      BlockPos pos = entity.blockPosition();
      int minY = world.getMinY();
      int startY = pos.getY();
      int maxDist = Math.max(64, startY - minY + 2);
      for (int i = 0; i < maxDist; i++) {
          BlockPos checkPos = pos.below(i);
          BlockState state = world.getBlockState(checkPos);
          if (!state.isAir() && !state.canBeReplaced() && (state.isSolid() || state.blocksMotion())) {
              return checkPos.getY() + 1.0;
          }
      }
      return minY;
  }
  ```

---

## 7. SavedData & World State Persistence (`SavedDataType<T>`)

In 26.1.2, `SavedData.Factory` was superseded by `SavedDataType<T>` backed by a Mojang `Codec`.

```java
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.minecraft.resources.Identifier;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.core.HolderLookup;
import com.mojang.serialization.Codec;

public class MyModSavedData extends SavedData {
    public static final String DATA_NAME = "my_mod_world_data";

    // 1. Codec bridging NBT to SavedData
    public static final Codec<MyModSavedData> CODEC = CompoundTag.CODEC.xmap(
        tag -> load(tag, null),
        data -> data.save(new CompoundTag(), null)
    );

    // 2. SavedDataType descriptor
    public static final SavedDataType<MyModSavedData> TYPE = new SavedDataType<>(
        Identifier.parse(DATA_NAME),
        MyModSavedData::new,
        CODEC,
        null
    );

    public MyModSavedData() {}

    public static MyModSavedData load(CompoundTag tag, HolderLookup.Provider provider) {
        MyModSavedData data = new MyModSavedData();
        if (tag != null) {
            // Read fields using *Or methods
            double customVal = tag.getDoubleOr("CustomValue", 0.0);
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider provider) {
        tag.putDouble("CustomValue", 10.0);
        return tag;
    }

    // 3. Retrieval from Overworld DimensionDataStorage
    public static MyModSavedData get(LevelAccessor world) {
        if (world instanceof ServerLevel serverLevel) {
            ServerLevel overworld = serverLevel.getServer().getLevel(Level.OVERWORLD);
            if (overworld != null) {
                return overworld.getDataStorage().computeIfAbsent(TYPE);
            }
        }
        return null;
    }
}
```

* **Key Difference from 1.21.1**: `computeIfAbsent` accepts `TYPE` directly without passing the data name string as a second argument.

---

## 8. Modern Entity Rendering Pipeline (`EntityRenderState`)

In 26.1.2, entity rendering was restructured to decouple rendering from live game world entities using `EntityRenderState`.

### Core Class Declaration
```java
public static class CustomMobRenderer extends MobRenderer<MyMobEntity, MyMobRenderState, CustomMobRenderer.AnimatedModel> {
    private final Identifier entityTexture = Identifier.parse("the_backwoods:textures/entities/mob.png");

    public CustomMobRenderer(EntityRendererProvider.Context context) {
        super(context, new AnimatedModel(context.bakeLayer(MyModel.LAYER_LOCATION)), 0.7f);
        // Add layers such as held items
        this.addLayer((RenderLayer) new ItemInHandLayer((RenderLayerParent) this));
    }

    // 1. Instantiate the RenderState
    @Override
    public MyMobRenderState createRenderState() {
        return new MyMobRenderState();
    }

    // 2. Extract values from live entity into RenderState
    @Override
    public void extractRenderState(MyMobEntity entity, MyMobRenderState state, float partialTick) {
        super.extractRenderState(entity, state, partialTick);
        ArmedEntityRenderState.extractArmedEntityRenderState(entity, state, this.itemModelResolver, partialTick);
        state.isPhasing = entity.getPersistentData().getBooleanOr("rot_phase_active", false);
        state.customScale = entity.getPersistentData().getFloatOr("custom_scale", 1.0f);
    }

    // 3. Submit rendering calls
    @Override
    public void submit(MyMobRenderState state, PoseStack poseStack,
            net.minecraft.client.renderer.SubmitNodeCollector collector,
            net.minecraft.client.renderer.state.level.CameraRenderState camera) {
        super.submit(state, poseStack, collector, camera);
    }

    // 4. State-based scale and texture hooks
    @Override
    protected void scale(MyMobRenderState state, PoseStack poseStack) {
        poseStack.scale(state.customScale, state.customScale, state.customScale);
    }

    @Override
    public Identifier getTextureLocation(MyMobRenderState state) {
        return entityTexture;
    }
}
```

### RenderState Class Definition
```java
public static class MyMobRenderState extends LivingEntityRenderState implements ArmedEntityRenderState {
    public boolean isPhasing = false;
    public float customScale = 1.0f;
    public HumanoidArm mainArm = HumanoidArm.RIGHT;

    // ArmedEntityRenderState implementations
    @Override
    public HumanoidArm getMainArm() {
        return this.mainArm;
    }
}
```

---

## 9. Animation Architecture (`KeyframeAnimation` Baking)

In 26.1.2, animation definitions are baked once into `KeyframeAnimation` instances against the model root, avoiding per-frame hierarchy traversal.

```java
import net.minecraft.client.animation.KeyframeAnimation;
import net.minecraft.client.animation.AnimationDefinition;

public static final class AnimatedModel extends ModelBase implements ArmedModel {
    private final ModelPart root;
    private final KeyframeAnimation walkAnim;
    private final KeyframeAnimation attackAnim;

    private KeyframeAnimation safeBake(AnimationDefinition def) {
        if (def == null) return null;
        try {
            return def.bake(this.root);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public AnimatedModel(ModelPart root) {
        super(root);
        this.root = root;
        this.walkAnim = safeBake(ModAnimations.mob_walk);
        this.attackAnim = safeBake(ModAnimations.mob_attack);
    }

    @Override
    public void setupAnim(MyMobRenderState state) {
        super.setupAnim(state);
        this.root().getAllParts().forEach(ModelPart::resetPose);

        // 1. Apply walk animations with limb swing parameters:
        if (this.walkAnim != null && state.walkAnimationSpeed > 0.01f) {
            this.walkAnim.applyWalk(state.walkAnimationPos, state.walkAnimationSpeed, 2.0f, 500.0f);
        }

        // 2. Apply action animations using AnimationState:
        if (this.attackAnim != null) {
            this.attackAnim.apply(state.attackAnimationState, state.ageInTicks);
        }
    }
}
```

---

## 10. Damage Events & Lifecycle Handling

In NeoForge 26.1.2, `LivingDamageEvent.Pre` replaces legacy `LivingHurtEvent` / `LivingDamageEvent`.

```java
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.bus.api.SubscribeEvent;

@EventBusSubscriber
public class EntityDamageHandler {

    @SubscribeEvent
    public static void onLivingDamagePre(LivingDamageEvent.Pre event) {
        DamageSource source = event.getSource();
        float originalAmount = event.getOriginalDamage();
        float currentAmount = event.getNewDamage();

        // Modifying damage:
        if (source.is(DamageTypes.FALL)) {
            event.setNewDamage(0.0f); // Nullify fall damage
        } else {
            event.setNewDamage(currentAmount * 0.85f); // 15% reduction
        }
    }

    @SubscribeEvent
    public static void onLivingIncomingDamage(LivingIncomingDamageEvent event) {
        // Earliest hook before armor or absorption calculations
        if (event.getEntity() instanceof RotEntity rot && rot.getPersistentData().getBooleanOr("rot_phase_active", false)) {
            event.setCanceled(true); // Completely evade hit
        }
    }
}
```

* **Damage Getter & Setter Names**:
  * 1.21.1: `event.getAmount()`, `event.setAmount(float)`
  * 26.1.2: `event.getNewDamage()`, `event.setNewDamage(float)`, `event.getOriginalDamage()`

---

## 11. Entity Conversions & Package Moves

* **Villager Entity Package**:
  * 1.21.1: `net.minecraft.world.entity.npc.Villager`
  * 26.1.2: `net.minecraft.world.entity.npc.villager.Villager`

* **Mob Superclass**:
  * Cast targets to `net.minecraft.world.entity.Mob` when manipulating pathfinding or navigation:
  ```java
  if (entity instanceof Mob mob) {
      mob.getNavigation().moveTo(target, 1.2);
  }
  ```

* **Delta Movement Accessors**:
  * Both record-style accessors (`v.x()`, `v.y()`, `v.z()`) and direct public fields (`v.x`, `v.y`, `v.z`) are valid on `Vec3`.

* **Advancement Access**:
  ```java
  if (player instanceof ServerPlayer serverPlayer) {
      AdvancementHolder adv = serverPlayer.server.getAdvancements().get(Identifier.parse("the_backwoods:defeat_boss"));
      if (adv != null) {
          AdvancementProgress progress = serverPlayer.getAdvancements().getOrStartProgress(adv);
          if (!progress.isDone()) {
              for (String criterion : progress.getRemainingCriteria()) {
                  serverPlayer.getAdvancements().award(adv, criterion);
              }
          }
      }
  }
  ```

* **Player Messaging & Action Bar (Overlay)**:
  * In 26.1.2, `displayClientMessage(...)` is removed from `Player`. Use `ServerPlayer.sendSystemMessage(Component, boolean overlay)`:
  ```java
  if (player instanceof ServerPlayer serverPlayer) {
      // Second boolean parameter (true) sends to Action Bar (overlay):
      serverPlayer.sendSystemMessage(Component.literal("§cUnknown location."), true);
  } else {
      player.sendSystemMessage(Component.literal("§cUnknown location."));
  }
  ```

* **ResourceKey Identifier Access**:
  * In 26.1.2, `ResourceKey.location()` is renamed to `ResourceKey.identifier()`:
  ```java
  // 1.21.1
  ResourceLocation dimLoc = level.dimension().location();

  // 26.1.2
  Identifier dimLoc = level.dimension().identifier();
  ```

---

## 12. Attribute Modifiers & Operations

```java
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.resources.Identifier;

// 26.1.2 Syntax
AttributeModifier speedBuff = new AttributeModifier(
    Identifier.parse("the_backwoods:speed_buff"),
    0.25,
    AttributeModifier.Operation.ADD_VALUE
);

// Applying to entity
living.getAttribute(Attributes.MOVEMENT_SPEED).addTransientModifier(speedBuff);
```

### Operation Constant Names
* `AttributeModifier.Operation.ADD_VALUE` (replaces legacy `ADDITION`)
* `AttributeModifier.Operation.ADD_MULTIPLIED_BASE` (replaces legacy `MULTIPLY_BASE`)
* `AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL` (replaces legacy `MULTIPLY_TOTAL`)

---

## 13. Prosopagnosia & Client Visual Override Architecture (26.1.2)

### A. Nametag Hiding (`RenderNameTagEvent.CanRender`)
In modern NeoForge (26.1.x), `RenderNameTagEvent` is split into nested sub-events (`RenderNameTagEvent.CanRender` and `RenderNameTagEvent.DoRender`).
- `event.setContent(...)` does NOT exist on `RenderNameTagEvent.CanRender`. Calling it causes a compile-time `cannot find symbol` error.
- Use `RenderNameTagEvent.CanRender` as the method parameter.
- Use enum-safe reflection to invoke `setCanRender(...)` with the `FALSE` constant to guarantee compatibility regardless of which package holds `TriState`:

```java
@SubscribeEvent
public static void onRenderNameTag(RenderNameTagEvent.CanRender event) {
    Minecraft mc = Minecraft.getInstance();
    if (mc.player != null && hasProsopagnosia(mc.player)) {
        for (java.lang.reflect.Method m : event.getClass().getMethods()) {
            if (m.getName().equals("setCanRender") && m.getParameterCount() == 1 && m.getParameterTypes()[0].isEnum()) {
                for (Object constant : m.getParameterTypes()[0].getEnumConstants()) {
                    if (((Enum<?>) constant).name().equals("FALSE")) {
                        try {
                            m.invoke(event, constant);
                        } catch (Throwable ignored) {
                        }
                        return;
                    }
                }
            }
        }
    }
}
```

### B. LevelRenderState & Sky Blackout Pipeline
In 26.1.2, Minecraft separates state collection from rendering. Direct sky quad drawing via `RenderLevelStageEvent.AFTER_SKY` no longer exists.
Instead, sky dome colors and celestial bodies are configured inside `SkyRenderState` on `LevelRenderState`:
1. **`ExtractLevelRenderStateEvent` Hook**:
   Fires immediately after the engine populates `LevelRenderState` for the frame and before any drawing starts.
2. **`SkyRenderState` Modification**:
   - Set skybox enum to `NONE` (skips sun, moon, stars, and dome).
   - Set dome/horizon color integers to solid black (`0xFF000000`).
   - Clear sunrise/sunset tints to 0.
   - Zero out brightness, alpha, and intensity float fields.

```java
@SubscribeEvent
public static void onExtractLevelRenderState(ExtractLevelRenderStateEvent event) {
    Minecraft mc = Minecraft.getInstance();
    if (mc.player != null && hasProsopagnosia(mc.player)) {
        blackenSky(mc);
    }
}
```

### C. FogParameters Immutable Record Patching
In 26.1.2, `FogRenderer` stores active fog configuration in an immutable `FogParameters` record.
To alter fog distances and color:
1. Access the `parameters` field on `mc.gameRenderer.fogRenderer`.
2. Inspect the record components via `RecordComponent[]`.
3. Construct a replacement `FogParameters` instance with:
   - `red = 0.0f`, `green = 0.0f`, `blue = 0.0f`
   - `renderStart = 56.0f`
   - `renderEnd = 96.0f`
4. Set the new record instance back into `fogRenderer.parameters`.

### D. Modern Entity & Player Texture Overriding
In 26.1.2, entity models render via `SubmitNodeCollector` and `CameraRenderState`:
- Implement a custom `SubmitNodeCollector` delegating to `RenderTypes.entityCutoutNoCull(texture)`, `RenderTypes.entityTranslucent(texture)`, or `RenderTypes.entitySolid(texture)`.
- Submit through `AvatarRenderer.submit(...)` or `LivingEntityRenderer.submit(...)`.
- Cancel `RenderPlayerEvent.Pre` / `RenderLivingEvent.Pre` to suppress the original entity drawing pass.

---

## 14. File Header & Version Comment Rule
Every Java source/procedure file must include a single-line version tag comment at the very bottom of the file (e.g. `// 26.1.2` or `// 1.21.1`).

---

## 15. Comprehensive Version Mapping Matrix

| Feature / Domain | 1.21.1 | 1.21.8 | 26.1.2 |
|---|---|---|---|
| **Resource Identifier** | `ResourceLocation` | `ResourceLocation` | `Identifier` |
| **ResourceKey Accessor**| `key.location()` | `key.location()` | `key.identifier()` |
| **Action Bar Message** | `player.displayClientMessage(comp, true)` | `player.displayClientMessage(comp, true)` | `serverPlayer.sendSystemMessage(comp, true)` |
| **NBT Reads** | Raw type (`getBoolean`) | `Optional<T>` (`.orElse(false)`) | Built-in fallback (`getBooleanOr`) |
| **NBT Empty Compound** | `getCompound("k")` | `getCompound("k").orElse(...)` | `getCompoundOrEmpty("k")` |
| **World Min Height** | `level.getMinBuildHeight()` | `level.getMinY()` | `level.getMinY()` |
| **World Max Height** | `level.getMaxBuildHeight()` | `level.getMaxY()` | `level.getMaxY()` |
| **SavedData Storage** | `SavedData.Factory` | `SavedData.Factory` | `SavedDataType<T>` with `Codec` |
| **Damage Event** | `LivingDamageEvent` | `LivingDamageEvent` | `LivingDamageEvent.Pre` |
| **Damage Amount Access**| `getAmount()` / `setAmount()` | `getAmount()` / `setAmount()` | `getNewDamage()` / `setNewDamage()` |
| **Damage Source Lookup**| `registryOrThrow(DAMAGE_TYPE)`| `damageSources().source()` | `level.holderOrThrow(ResourceKey)` |
| **Attribute Modifier ID**| `UUID` | `ResourceLocation` | `Identifier` |
| **Attribute Operation** | `ADDITION` | `ADD_VALUE` | `ADD_VALUE` |
| **Villager Package** | `world.entity.npc.Villager` | `world.entity.npc.Villager` | `world.entity.npc.villager.Villager` |
| **Entity Renderer** | `MobRenderer<Entity, Model>`| `MobRenderer<Entity, State, Model>`| `MobRenderer<Entity, State, Model>` |
| **Model Render Call** | `render(...)` | `render(...)` | `submit(...)` via `SubmitNodeCollector` |
| **Animation System** | `HierarchicalModel.animate` | `HierarchicalModel.animate` | `KeyframeAnimation.bake` & `.apply` |
| **Nametag Event** | `RenderNameTagEvent` | `RenderNameTagEvent` | `RenderNameTagEvent.CanRender` |
| **Nametag Suppression** | `setCanRender` / `setContent` | `setCanRender` / `setContent` | Reflection `setCanRender(FALSE)` |
| **Fog Distance Override**| `ViewportEvent.RenderFog` (cancel)| `ViewportEvent.RenderFog` | `FogParameters` record swap |
| **Sky Override Mechanism**| `RenderLevelStageEvent.AFTER_SKY` | `RenderLevelStageEvent.AFTER_SKY` | `SkyRenderState` on `LevelRenderState` |
