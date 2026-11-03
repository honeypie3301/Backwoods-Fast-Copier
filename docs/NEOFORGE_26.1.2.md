# NeoForge / Minecraft 26.1.2 AI Reference

> **Status: corrected foundation; not yet an exhaustive, compile-verified API catalogue.** This guide targets Minecraft 26.1.2 with a specific NeoForge 26.1.2.x build and Java 25. It separates Java language syntax from Minecraft/NeoForge APIs. Exact NeoForge build number must be taken from the project's `gradle.properties`.
>
> **AI safety rule:** never invent a method, constructor, event, field, package, or generic signature. If the exact 26.1.2 dependency source/Javadoc cannot confirm it, label the example unverified and ask for the project's source/build files or omit the code.


## 1. Version Identity & Architecture Protocol — READ FIRST

NeoForge 26.1.2 incorporates modern Mojang mappings and modern engine architecture. Before writing or modifying any Java files in this version:

1. **Verify Your Target Version**: Check file names, directory trees, or comments. Files named with `26.1.2` or targeting the 26.x NeoForge toolchain follow this guide strictly.
2. **Never Backport 1.21.1 or 1.21.8 Syntax**:
   - Do not assume `ResourceLocation` was globally renamed. The 26.1 migration primer still uses `ResourceLocation` in rendering-pipeline examples, while `SavedDataType` and other APIs use `Identifier`. Use the exact parameter type shown by the target method signature; `Identifier` and `ResourceLocation` are not interchangeable unless an API explicitly converts them.
   - `CompoundTag` does NOT return `Optional<T>` like 1.21.8, nor raw unchecked getters like 1.21.1. It uses modern built-in fallback methods (`getDoubleOr`, `getBooleanOr`, etc.).
   - `SavedData.Factory` does NOT exist; 26.1.2 uses `SavedDataType<T>` backed by a Mojang `MapCodec` for the 26.1 API shape.
   - Entity rendering does NOT accept raw `Entity` parameters in `render(...)`. It uses the modernized decoupled pipeline: `EntityRenderState`, `SubmitNodeCollector`, and `CameraRenderState`.
   - Keyframe animations are NOT animated via `HierarchicalModel.animate(...)`; they are baked into `KeyframeAnimation` instances and invoked via `apply(...)` and `applyWalk(...)`.
3. **Bracketing & Formatting Disciplines**:
   - Always maintain zero bracket discrepancy (`{}` count matches `}`).
   - Never wrap numbers, variables, coordinates, or mathematical expressions in LaTeX or math delimiters (do NOT use math markers like dollar signs or escaped parentheses). Write all numbers and ranges as plain text.

---

## 2. The ResourceLocation to Identifier Migration

**Important correction:** do not treat this as a global class rename. The 26.1 primer demonstrates `Identifier` for `SavedDataType`, but still uses `ResourceLocation` for render-pipeline resource locations. Verify each API parameter type in the 26.1.2 sources/Javadocs. The examples below are examples of `Identifier` use only, not proof that `ResourceLocation` is removed.

* **Import Change**:
  ```java
  // Use only when the target API expects Identifier
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

> **Verification status: unverified method catalogue.** Treat the getter names and return types below as candidates until checked against the exact 26.1.2 NeoForge/Minecraft dependency. Do not assume this table is a complete list.

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

> **Verification status: illustrative only.** The lookup snippets below are not certified compile-ready; registry methods differ in return type and dynamic-registry access pattern.

Registry lookup return types vary by registry API and lookup method. Do not generalize every `BuiltInRegistries` lookup as `Optional<Holder.Reference<T>>`. Check the exact declared return type in the 26.1.2 source/Javadocs before using `.map`, `.value`, `.get`, or `.orElse`.

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

> **Verification status: illustrative only.** Confirm the `DamageSource` constructors, registry key, and holder lookup against the exact 26.1.2 dependency before copying.

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

> **Verification status: method signatures need checking.** The helper is ordinary Java around Minecraft calls; verify each called method and the intended world-boundary behavior against the target source.

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

In 26.1, `SavedData.Factory` is replaced by `SavedDataType<T>`. The migration primer shows a `MapCodec` in the type descriptor; do not assume a generic `Codec` is accepted.

```java
import com.mojang.serialization.MapCodec;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

public final class ExampleData extends SavedData {
    public static final SavedDataType<ExampleData> TYPE = new SavedDataType<>(
        Identifier.fromNamespaceAndPath("examplemod", "example/data"),
        ExampleData::new,
        MapCodec.unitCodec(ExampleData::new),
        null // Data fixer type; follow the loader/version convention for your project
    );

    public ExampleData() {}
}
```

This is a **minimal empty-data descriptor**, not a complete serializer for custom fields. For non-empty data, define a `MapCodec` that explicitly encodes/decodes every persisted field. Do not bridge `CompoundTag.CODEC` to `SavedData.save(...)` with a guessed `Codec` adapter: the 26.1 migration primer demonstrates `MapCodec.unitCodec(...)` for the empty case and `SavedDataStorage#computeIfAbsent(TYPE)` for retrieval. Global data belongs on `MinecraftServer#getDataStorage()`; dimension-scoped data belongs on `ServerLevel#getDataStorage()`.

* **Key Difference from 1.21.1**: `computeIfAbsent` accepts `TYPE` directly without passing the data name string as a second argument.

---

## 8. Rendering, animation, damage, entity, and client hooks — verification required

The previous draft contained extensive concrete examples for entity rendering, keyframe animation, damage events, nametag suppression, sky modification, fog replacement, and player/entity texture overrides. Those examples are removed from the compile-ready portion of this reference because their exact 26.1.2 signatures and behavior were not established from the matching NeoForge artifact.

Use these rules:

- **Entity rendering:** 26.1 uses a state-extraction/submission architecture. Do not copy older `render(...)` overrides or the earlier generic `MobRenderer` example. Verify renderer generic parameters, `createRenderState`, extraction method, submission method, feature-renderer constructors, and model interfaces against the exact client artifact.
- **Animation:** do not assume `ModelBase`, `ArmedModel`, `KeyframeAnimation#applyWalk`, or an animation-state field exists with the earlier signatures. Verify the actual animation and model classes in the target source.
- **Damage:** verify the exact NeoForge event class, event bus, cancellation contract, stage order, and getter/setter names in the exact NeoForge build. Never infer that `LivingDamageEvent.Pre` or `LivingIncomingDamageEvent` has a particular setter based on a different release.
- **Nametags:** use the public compile-time API exposed by the exact event. Do not use reflection to search for a setter or enum constant.
- **Sky/fog:** do not mutate renderer internals or replace private record fields reflectively. Use a documented event or public extension point.
- **Texture overrides:** verify current player/entity renderer hooks and cancellation events. Do not assume old `RenderPlayerEvent.Pre` / `RenderLivingEvent.Pre` events or `RenderType` helpers remain applicable.

No compile-ready code is supplied here intentionally. An omitted guessed snippet is safer for an AI reference than a plausible but non-compiling example.

## 9. Other API examples retained from the earlier draft

The identifier, NBT, registry, damage-source, world-height, attribute, messaging, and advancement examples elsewhere in this document are migration notes, not a complete verified API catalogue. Before using any one of them, validate its imports and exact signature against the project dependency. In particular:

- The identifier type is API-specific; `Identifier` and `ResourceLocation` are not globally interchangeable.
- Registry lookup methods have different return types; do not assume a universal `Optional<Holder.Reference<T>>` pattern.
- `DamageSource` constructors and `holderOrThrow` usage must be checked against the exact class signatures.
- NBT fallback getter names and return types must be checked against the exact `CompoundTag` class.
- `LevelAccessor` world-height and heightmap methods must be checked against the exact target API.
- Attribute modifier constructors, operation enum names, and attribute-instance methods must be checked against the exact target API.
- Messaging, advancement, and event APIs must be checked for both signature and logical-side correctness.

## 10. Removed policy: mandatory file-bottom version comments

Java does not require a version comment. Keep version identity in the Gradle configuration and project documentation. A version comment may be added when it helps an AI distinguish generated files, but it is not a language or NeoForge requirement.

## 11. Version comparison policy

Do not keep a static migration matrix unless every cell has been checked against the three exact versions. The former matrix contained unverified claims about action-bar messaging, damage event names, registry lookup, attribute IDs, rendering generics, nametag events, and fog/sky hooks. Those claims have been removed rather than presented as authoritative.


---

# 16. AI Reference Rules: How to Generate Correct 26.1.2 Code

This section takes precedence over any older, contradictory example above.

## 16.1 Exact version identity

There are two version numbers, and both must be recorded:

- **Minecraft:** `26.1.2`
- **NeoForge:** `26.1.2.<build>` (for example, a stable build such as `26.1.2.125`; use the project's actual `neo_version`)

Never treat `26.1.2` as a complete NeoForge artifact version. Different NeoForge builds may contain API changes. Before generating code, inspect `gradle.properties`, `build.gradle`/`build.gradle.kts`, `settings.gradle`, and the Gradle wrapper.

## 16.2 Java toolchain

Minecraft 26.1 uses **Java 25**. The official 26.1.2 NeoForge MDK sets:

```groovy
java.toolchain.languageVersion = JavaLanguageVersion.of(25)
```

Use the project's MDK as the authority for the NeoGradle/ModDevGradle plugin and wrapper versions. Do not copy a plugin version from a random tutorial. The 26.1 migration primer notes Java 25 and the return to deobfuscated Mojang names.

## 16.3 Mandatory verification procedure

For every non-JDK symbol in generated code:

1. Resolve the fully qualified class name from the 26.1.2 dependency/source set.
2. Confirm the member exists and is accessible (`public`, `protected`, package-private, or private).
3. Confirm the exact parameter order, parameter types, generic bounds, return type, and checked exceptions.
4. Confirm whether the call is common/server-safe or client-only.
5. Confirm the correct event bus and event distribution (logical client, logical server, or both).
6. Confirm whether the API is stable for the selected NeoForge build, not merely present in a nearby Minecraft release.
7. Compile with the project's Gradle wrapper. A code block is not “verified” merely because it looks plausible.

If any step cannot be completed, label the code **illustrative / unverified** and do not claim it compiles.

## 16.4 Java language syntax versus Minecraft APIs

Java syntax is defined by the Java language version; Minecraft classes and NeoForge hooks are separate APIs. Java 25 language features that an AI may encounter include:

- Classes, interfaces, enums, records, annotations, nested/local/anonymous classes.
- Primitive/reference types, arrays, generics, wildcards, type inference (`var`), casts, `instanceof` pattern matching.
- Methods, constructors, overloaded methods, access modifiers, `static`, `final`, `abstract`, `sealed`, `non-sealed`.
- Control flow: `if`, `switch` statements/expressions, `for`, enhanced `for`, `while`, `do`, `break`, `continue`, `return`, `yield`, `throw`, `try`/`catch`/`finally`, try-with-resources.
- Lambdas, method references, functional interfaces, streams, Optional, collections, iterators.
- Exceptions, annotations, modules, packages, imports, static imports.
- Text blocks, string concatenation, formatting, numeric literals, operators, casts, precedence.
- Records, sealed types, pattern matching, and other finalized language features supported by Java 25.
- Java 25 preview/incubator features only when the project's compiler explicitly enables the required preview/incubator flags. Never silently use preview syntax.

This is a category checklist, not a replacement for the Java Language Specification. Do not create a separate Minecraft-specific syntax for ordinary Java constructs.

### Java 25 constructor prologue note

Java 25 supports statements before an explicit `super(...)` or `this(...)` constructor invocation under the finalized flexible-constructor-body rules. Use this only when the compiler is configured for Java 25 and the statement is legal under the JLS; do not rewrite ordinary constructors unnecessarily.

## 16.5 Safe common Java patterns

These are Java language examples, not proof of any Minecraft API signature:

```java
// Pattern matching
if (entity instanceof LivingEntity living) {
    living.setNoAi(false); // API call must still be verified for the target version
}

// Records
public record SpawnSettings(int weight, float minScale, float maxScale) {}

// Switch expression
String label = switch (mode) {
    case PEACEFUL -> "peaceful";
    case NORMAL -> "normal";
    case HARD -> "hard";
};

// Try-with-resources
try (var input = Files.newInputStream(path)) {
    byte[] bytes = input.readAllBytes();
}

// Lambda / method reference
List<String> names = entries.stream()
    .map(Entry::name)
    .toList();
```

Do not copy the Minecraft API call in the first example without checking its signature; the surrounding Java syntax is standard Java.

---

# 17. 26.1 Migration Changes That Must Be Covered

The official NeoForge 1.21.11-to-26.1 primer is explicitly **high-level and non-exhaustive**. It is the correct migration source for many vanilla changes, but it is not a complete NeoForge API reference. The following items are confirmed by that primer and should be represented in an AI reference.

## 17.1 Loot and value-provider codec changes

- Loot pool entries, loot functions, loot conditions, NBT providers, number providers, score providers, integer providers, and float providers no longer use their former wrapper `*Type` objects in the same way.
- Registries now directly hold the relevant `MapCodec` for these types.
- `getType()` is replaced by `codec()` where documented; this is not necessarily a one-to-one rename.
- `IntProvider` and `FloatProvider` are interfaces in the new API shape; many implementations are records.
- `getMinValue` / `getMaxValue` have been replaced by `min` / `max` for float providers and `minInclusive` / `maxInclusive` for integer providers.
- Codec fields commonly move from `CODEC` to `MAP_CODEC`; consult the exact class because the migration is not a mechanical global rename.

Do not register the old `LootItemFunctionType`, `LootPoolEntryType`, `FloatProviderType`, or `IntProviderType` wrapper pattern for 26.1 code.

## 17.2 Saved data

- `SavedDataType<T>` uses an `Identifier`.
- The storage abstraction is `SavedDataStorage`, formerly `DimensionDataStorage`.
- The server has global data storage through `MinecraftServer#getDataStorage()` in addition to per-level storage through `ServerLevel#getDataStorage()`.
- Use server storage for data that is global to the world/server; use level storage for data scoped to a dimension.
- `computeIfAbsent(TYPE)` is the documented retrieval form.

## 17.3 Rendering

- Rendering changes are extensive; old render-layer assumptions are unsafe.
- Block and item models no longer choose their render layer in the old way. Layer selection is calculated from texture transparency when loading the model.
- `ItemBlockRenderTypes` is removed.
- The old tripwire render pipeline and tripwire chunk layer are removed.
- Render-state extraction and submission are central concepts. Use the exact 26.1.2 client API and NeoForge render hooks; do not port old `render(...)` overrides mechanically.

## 17.4 Entity interaction

`Entity#interactAt` has been removed. Interaction is consolidated into `Entity#interact`, which now receives the hit location as a `Vec3`:

```java
@Override
public InteractionResult interact(Player player, InteractionHand hand, Vec3 location) {
    // Handle the interaction using the supplied hit location.
    return super.interact(player, hand, location);
}
```

Use this override only in the appropriate `Entity` subclass and verify the exact signature in the 26.1.2 sources before use.

## 17.5 ChunkPos

`ChunkPos` is now a record. Migration changes documented by the primer include:

- `new ChunkPos(BlockPos)` -> `ChunkPos.containing(blockPos)`
- packed-long construction -> `ChunkPos.unpack(packed)`
- `toLong` / `asLong` -> `pack`

Verify exact overloads in the target class.

## 17.6 Networking buffer codecs

The migration primer documents that `FriendlyByteBuf#readVec3` and `writeVec3` are replaced by `Vec3#STREAM_CODEC`, and the length-prefixed variants by `Vec3#LP_STREAM_CODEC`. For NeoForge custom payloads, use the 26.1 `RegisterPayloadHandlersEvent` workflow and the matching stream codec APIs.

---

# 18. NeoForge Mod Architecture Topics Missing From This Reference

The original document was not comprehensive. An AI-facing reference should include the following sections, each with exact 26.1.2 signatures, imports, side restrictions, and compile-tested examples.

## 18.1 Project/toolchain and mod bootstrap

- MDK layout; Gradle wrapper; NeoGradle versus ModDevGradle; Java 25 toolchain.
- `neoforge.mods.toml`, mod id, version expansion, dependencies, loader version ranges.
- Main mod class, `@Mod`, mod event bus versus `NeoForge.EVENT_BUS`.
- Logging, lifecycle, common/client entry points, distribution-safe class loading.

## 18.2 Registries and data-driven content

- Deferred registration or the exact current 26.1 registration mechanism.
- Blocks, items, block items, entity types, effects, sounds, particles, menus, recipes, attachments, attributes, creative tabs, POIs, villager data, and custom registries.
- `ResourceKey`, `Holder`, `HolderLookup.Provider`, registry access, tags, datapack registries, and reload lifecycle.
- Distinguish static built-in registries from dynamic datapack registries.

## 18.3 Blocks and block entities

- Block properties, state definitions, default states, placement, rotation/mirroring, neighbor updates, survival, shape/collision/occlusion, interaction, drops, loot tables.
- Block entity type registration, ticker, save/load codecs, update tags/packets, menu opening, client sync, capabilities/attachments.
- Server/client mutation rules and chunk-loading safety.

## 18.4 Items and inventories

- Item properties, components/data components, item stack mutation, use/use-on/finish-use, food/equipment, durability, attributes, crafting remainder.
- Inventory/menu/container patterns, slots, quick-move, item handlers or current transfer API, sided access, capability lifecycle.
- Avoid legacy assumptions about NBT stored directly on ItemStack when a data component is the intended API.

## 18.5 Entities and AI

- EntityType registration, constructors, synchronized entity data, spawn rules, attributes, goals, navigation, pathfinding, sensors/brain/activity data, interaction, damage, death, drops, persistence, passengers, dimensions, and tracking range.
- Entity attachment/data attachment lifecycle and serialization.
- Player-specific versus generic LivingEntity behavior.

## 18.6 Events and lifecycle

- Event bus selection, registration annotations, static versus instance subscribers, priority, cancellation, result semantics.
- Common setup, client setup, data gathering, registry events, reload listeners, server lifecycle, player lifecycle, level/chunk events, interaction events, damage events.
- Exact event class names and method signatures must be drawn from the selected NeoForge build's Javadocs/source.

## 18.7 Networking

- Custom payload records, `CustomPacketPayload.Type`, `StreamCodec`, payload registration, handler registration, play/configuration phases, clientbound/serverbound direction.
- Thread handling and enqueueing work on the correct game thread.
- Validation, permission checks, bounds checks, and never trusting client-supplied positions/entities/items.
- Synchronizing attachments, block entities, entities, and menus.

## 18.8 Data generation and resources

- Block/item models, blockstates, loot tables, recipes, tags, advancements, language files, sounds, particles, biome modifiers, worldgen, reports.
- `GatherDataEvent`, providers, lookup providers, generated resources source set.
- Resource reload and pack priority.

## 18.9 World generation and dimensions

- Biomes, noise settings, density functions, placed/configured features, structures, structure sets, processor lists, carvers, dimension types, level stems, biome modifiers.
- Datapack JSON schemas and codecs; bootstrap context and registry lookups.
- Chunk generation versus runtime world mutation.

## 18.10 Rendering and client

- Entity render-state extraction, model layers, model setup, feature renderers, item/block entity renderers, render pipelines, buffers, pose stacks, camera state, particles, GUI/HUD, key mappings, screens, shaders, texture atlases.
- Client-only class isolation and dedicated-server crash prevention.
- Do not use old `RenderType`/`RenderLevelStageEvent` patterns unless the exact 26.1 API still exposes them.

## 18.11 Persistence and serialization

- `SavedDataType`, `MapCodec`, `Codec`, `StreamCodec`, NBT tags, data components, attachments, entity/block-entity persistence, player clone/death/dimension changes, server-global versus dimension-local state.
- Defaults, missing fields, schema evolution, malformed input handling, and data fixer policy.

## 18.12 Attributes, damage, effects, and combat

- Attribute registration, default attributes, modifier identifiers and operations, modifier lifecycle, damage type datapack definitions, `DamageSource`, damage event stages, effects, particles, knockback, invulnerability frames.
- Never assume event stage order or setter semantics without the 26.1.2 source.

## 18.13 Commands, recipes, loot, and advancements

- Brigadier command registration and permission levels.
- Recipe serializers/codecs and recipe input/assembly.
- Loot function/condition/provider MapCodec patterns for 26.1.
- Advancement definitions, criteria, triggers, and server award flow.

## 18.14 Testing, debugging, and performance

- GameTests, unit tests for pure logic, run configurations, registry dumps, logs, crash reports, profiling, allocation reduction, tick budgets, async boundaries, chunk tickets, and memory leaks.

---

# 19. Known Corrections and Warnings Against the Earlier Draft

The following earlier statements/examples must not be treated as reliable compile-ready syntax:

1. **Global `ResourceLocation` removal:** false as a universal rule. The 26.1 migration primer still uses `ResourceLocation` in rendering pipeline resource identifiers. Use the type required by the specific API.
2. **Registry lookup return type:** do not claim all `BuiltInRegistries` lookups return `Optional<Holder.Reference<T>>`. Verify each lookup.
3. **SavedData codec bridge:** the earlier `CompoundTag.CODEC.xmap(... save(...))` example is not a safe general template. 26.1 uses `SavedDataType` with `MapCodec`; a real serializer must encode all fields.
4. **Render-state sample:** the earlier `MobRenderer` generic, `ItemInHandLayer` casts, render-state fields, and `submit` override were not compile-verified. Do not copy blindly.
5. **Animation sample:** `ModelBase`, `ArmedModel`, `attackAnimationState`, and the shown animation calls were not verified as valid 26.1.2 symbols/signatures.
6. **Reflection-based nametag suppression:** remove this approach. Use the exact public setter/type if confirmed by the target Javadoc; never discover an enum setter at runtime as a compatibility strategy.
7. **Fog reflection/record replacement:** do not mutate private renderer fields through reflection. Use a supported NeoForge event or public extension point verified for 26.1.2.
8. **Sky blackout description:** do not assume arbitrary `SkyRenderState` fields can be mutated from the named event. Confirm the event exposes the state and that the relevant fields are public/mutable.
9. **Texture override description:** a custom `SubmitNodeCollector` and cancellation of old `RenderPlayerEvent.Pre` / `RenderLivingEvent.Pre` are not established by the original prose. Verify current hooks and renderer API before implementation.
10. **Vec3 field/accessor claim:** the earlier statement that both `v.x()` and `v.x` are valid is unsupported. Use the actual declared accessors/fields.
11. **Action-bar messaging:** do not infer that `ServerPlayer#sendSystemMessage(Component, boolean)` is the correct 26.1.2 action-bar method without checking its signature and semantics.
12. **Damage event stages:** do not assume the event classes, cancellation behavior, or damage amount setters based on older versions. Verify event Javadocs for the exact NeoForge build.

---

# 20. Reference Sources and Version Authority

Use these sources in this order:

1. **The actual project dependency and generated sources/Javadocs** for the exact `neo_version` in `gradle.properties`.
2. **Official NeoForge 26.1 documentation:** <https://docs.neoforged.net/docs/>
3. **Official 1.21.11 -> 26.1 migration primer:** <https://docs.neoforged.net/primer/docs/26.1/>. This is explicitly high-level and non-exhaustive; it covers vanilla migration changes, not every NeoForge API.
4. **Official 26.1.2 NeoForge MDK:** <https://github.com/NeoForgeMDKs/MDK-26.1.2-NeoGradle> and <https://github.com/NeoForgeMDKs/MDK-26.1.2-ModDevGradle>.
5. **Java 25 Language Specification / API documentation** for Java syntax and standard library behavior.
6. Versioned community Javadocs as a navigation aid only; resolve disagreements against the actual dependency sources.

## 20.1 Build identity record

At the top of every project-specific AI task, record:

```text
Minecraft: 26.1.2
NeoForge: <exact 26.1.2.<build> from gradle.properties>
Java toolchain: 25
Gradle wrapper: <exact distribution from gradle-wrapper.properties>
Gradle plugin: <exact plugin id and version from build.gradle>
Mappings: official Mojang names / deobfuscated 26.1 names
```

## 20.2 Compile-verification labels

Use exactly one label for each code sample:

- **Verified against exact build:** compiled successfully against the project's declared NeoForge artifact.
- **Verified against official 26.1 docs:** matches official docs, but not compiled in this project.
- **Migration-primer example:** copied/adapted from the official high-level vanilla migration primer.
- **Illustrative Java:** demonstrates Java syntax only; Minecraft/NeoForge calls are placeholders.
- **Unverified:** do not copy into production code until checked.

Never label a sample “correct 26.1.2 syntax” solely because it appears in a search result, a nearby version's docs, or an AI-generated answer.

---

# 21. Completion Checklist for This Reference

This document is a safer and broader foundation, but it is **not yet an exhaustive API catalogue**. To reach that standard, expand each topic in Section 18 with exact 26.1.2.XXX signatures and compiled examples. The reference is complete only when:

- Every relevant public NeoForge API area has an entry or an explicit “not applicable” note.
- Every code sample has a version/build label and import list.
- Every event documents its bus, side, timing, cancellation, and thread semantics.
- Every serializer documents its codec and data shape.
- Every client-only type is marked and isolated from dedicated-server loading.
- Every migration note distinguishes vanilla Minecraft changes from NeoForge-specific changes.
- Every uncertain API is explicitly marked unverified rather than guessed.
- A sample project using the exact pinned NeoForge build compiles all examples in CI.
