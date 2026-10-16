# NeoForge / Minecraft 1.21.8 Reference

### 1. Dual-Version Editing Protocol — READ FIRST
You are editing two source trees for the same mod: one targeting **1.21.1** and one targeting **1.21.8**. Before touching any file:
1. **Identify which tree you're in.** Check the build file or folder path. Never assume.
2. **Never "fix" 1.21.1 code with 1.21.8 patterns or vice versa.** Old raw lookups will fail on 1.21.8, and new `Optional` getters will fail to compile on 1.21.1.
3. **When a fix is needed in both trees**, apply the 1.21.1-style fix to 1.21.1 and the 1.21.8-style unwrap to 1.21.8.

---


### 2. Compound Tag / NBT Reads (1.21.8 Only)
In 1.21.8, every `CompoundTag` getter returns `Optional<T>` instead of `T`. In 1.21.1, these return raw types.

| Getter | 1.21.1 (Raw Type) | 1.21.8 (Optional Wrapper) |
|---|---|---|
| `getBoolean` | `tag.getBoolean("key")` | `tag.getBoolean("key").orElse(false)` |
| `getInt` | `tag.getInt("key")` | `tag.getInt("key").orElse(0)` |
| `getString` | `tag.getString("key")` | `tag.getString("key").orElse("")` |
| `getFloat` | `tag.getFloat("key")` | `tag.getFloat("key").orElse(0.0f)` |
| `getDouble` | `tag.getDouble("key")` | `tag.getDouble("key").orElse(0.0)` |
| `getLong` | `tag.getLong("key")` | `tag.getLong("key").orElse(0L)` |
| `getPersistentData()` | `entity.getPersistentData().getInt("key")` | `entity.getPersistentData().getInt("key").orElse(0)` |

---

### 3. Registry Lookups (1.21.8 Only)
`BuiltInRegistries.X.get(ResourceLocation)` returns `Optional<Reference<T>>` in 1.21.8 instead of `T`. Unwrap using `.map(ref -> ref.value()).orElse(fallback)`.

| Lookup | 1.21.1 | 1.21.8 |
|---|---|---|
| Block | `BuiltInRegistries.BLOCK.get(rl)` | `BuiltInRegistries.BLOCK.get(rl).map(ref -> ref.value()).orElse(Blocks.AIR)` |
| SoundEvent | `BuiltInRegistries.SOUND_EVENT.get(rl)` | `BuiltInRegistries.SOUND_EVENT.get(rl).map(ref -> ref.value()).orElse(null)` |
| Item | `BuiltInRegistries.ITEM.get(rl)` | `BuiltInRegistries.ITEM.get(rl).map(ref -> ref.value()).orElse(Items.AIR)` |
| MobEffect | `BuiltInRegistries.MOB_EFFECT.get(rl)` | `BuiltInRegistries.MOB_EFFECT.get(rl).map(ref -> ref.value()).orElse(null)` |
| EntityType | `BuiltInRegistries.ENTITY_TYPE.get(rl)` | `BuiltInRegistries.ENTITY_TYPE.get(rl).map(ref -> ref.value()).orElse(EntityType.PIG)` |

---

### 4. Entity / Mob Methods (1.21.8 Only)
* **`doHurtTarget`** now requires `ServerLevel` as the first argument:
  * 1.21.1: `mob.doHurtTarget(target);`
  * 1.21.8: `mob.doHurtTarget((ServerLevel) mob.level(), target);`
* **`hurt(DamageSource, float)`** damage sources construction changed:
  * 1.21.1: `entity.hurt(DamageSource.FALL, 5.0f);`
  * 1.21.8: `entity.hurt(level.damageSources().fall(), 5.0f);`

---

### 5. Attribute Modifiers (1.21.8 Only)
Modifier IDs changed from `UUID` to `ResourceLocation`, and `Operation` constant names also changed:

```java
// 1.21.1
entity.getAttribute(Attributes.MOVEMENT_SPEED).addTransientModifier(
    new AttributeModifier(UUID.randomUUID(), "my_modifier", 0.1, AttributeModifier.Operation.ADDITION));

// 1.21.8
entity.getAttribute(Attributes.MOVEMENT_SPEED).addTransientModifier(
    new AttributeModifier(ResourceLocation.parse("mymod:my_modifier"), 0.1, AttributeModifier.Operation.ADD_VALUE));
```

| 1.21.1 Operation | 1.21.8 Operation |
|---|---|
| `ADDITION` | `ADD_VALUE` |
| `MULTIPLY_BASE` | `ADD_MULTIPLIED_BASE` |
| `MULTIPLY_TOTAL` | `ADD_MULTIPLIED_TOTAL` |

---

### 6. Render Events (1.21.8 Only)
* **`RenderLivingEvent.Pre`**: `event.getRenderer()` is the preferred accessor if `getEntity()` is missing.
* **`RenderLevelStageEvent`** replaces `RenderLevelLastEvent` in 1.21.8:
  ```java
  @SubscribeEvent
  public void onRenderStage(RenderLevelStageEvent event) {
      if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_ENTITIES) return;
      // Drawing code here
  }
  ```

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



### 1. Multi-Version Support & File Conventions
* Minecraft `1.21.1` and `1.21.8` (1.21.4+) files are maintained in parallel in `/Fractus/`.
* The last line of `FractusLaserBeam` must strictly be `// 1.21.1` or `// 1.21.8`.

