# Performance, Quality Checks & Rendering Rules

### 2. Mandatory Quality Checks & Resolved Syntax Errors
* **Mandatory Bracketing Check**: Always verify balance of `{}`, `()`, and `[]` across all Java files before and after edits (`diff == 0`).
* **Resolved Issues Reference & NeoForge Compatibility**:
  * `error: variable partialTicks is already defined`: Redundant duplicate declaration of `partialTicks` in `FractusLaserBeam.java` removed; declared once at method start.
  * `FractusAI1.21.8.txt` Bracketing Imbalance: Removed rogue duplicate block in `setBurstLaserState` around line 496 that prematurely closed class scope.
  * **Ghost Laser Burst after Burst Attack**: Removed `BURST_FADE_END_TIMES` cache and tightened `isBurst` condition to active server state so no lingering ghost beam appears after burst completion or during post-burst delays.
  * **NeoForge 1.21.8 RenderLivingEvent Generics**: `RenderLivingEvent.Pre` and `RenderLivingEvent.Post` require 3 type parameters (`<?, ?, ?>`) in 1.21.8 vs 2 (`<?, ?>`) in 1.21.1.
  * **NeoForge 1.21.8 PersistentData Optional API**: `entity.getPersistentData().getInt/getBoolean/Double` returns `Optional<T>`. Access via `.orElse(...)` or dedicated `pDataInt`, `pDataBool`, `pDataDouble` helpers.
  * **NeoForge 1.21.8 ServerPlayer Level Method**: `player.serverLevel()` is replaced by `(ServerLevel) player.level()`.
  * **Burst Block Breaking & Particle Spawning Performance**:
    * Raycast block progress evaluation uses quick `BURST_THERMAL_PROGRESS` map lookup short-circuits.
    * Thermal degradation runs every 2 ticks; maximum 3 blocks broken per tick pass.
    * Particle spawning in `clearBurstBlockDamage` is capped at a maximum of 1-2 particles per pass with 20% probabilistic sampling to guarantee 0 TPS/FPS lag.
  * **Helper Method Scope Nesting in 1.21.1 (`FractusLaserBeam.java`)**:
    * Nested helpers like `rotateToward` must be defined strictly inside the inner static class `FractusLaserClientRenderer` rather than at the bottom of the outer file to prevent compilation `cannot find symbol` errors in 1.21.1 where `FractusLaserServerHandler` closes scope early.
  * **Frame-Rate Independent Client Beam Rotation (`FractusLaserBeam.java`)**:
    * Render-loop updates previously calculated a continuous angular step per frame using raw `partialTicks`. On high-FPS monitors (60+ Hz), this caused extreme rotation speed-up and snapping, inducing massive client raycast bottlenecks.
    * Replaced with a strict tick-synchronized `AimState` mechanism. Directions advance exactly once per tick using standard server turn rates (`0.035` - `0.120`), then render frames smoothly interpolate between `prevAim` and `currentAim` using `partialTicks`.
  * **Localized Bounding-Box Scanning inside `destroyBurstBlocksInLaserPath`**:
    * Scanning a global 3D Axis-Aligned Bounding Box (AABB) along diagonal laser beams up to 80 blocks long resulted in a volumetric explosion of up to 680,000 block lookups per check, causing severe server thread spikes.
    * Replaced with a highly optimized localized stepper that samples coordinate coordinates along the beam's vector at `2.0` block intervals. Tracks unique checked blocks using a fast `visited` HashSet, capping overall evaluations to fewer than 20,000 blocks while avoiding gaps.
  * **Minimizing Server-Side Thermal Lava Particles**:
    * Reduced `degradeBurstBlockThermal` particles on block vaporization down to a single sparse particle with a strict `15%` probability check.
  * **Ultra-Performance Allocation-Free math (`FractusPrimeAI` / `FractusAI`)**:
    * Replaced the object-allocating vector math of `squareDistanceToLineSegment` in the high-frequency block processing loop with primitive-only math, reducing memory/GC allocations inside the inner loops to absolute zero.
  * **Target-Locked Split Beam ID Keys (`FractusLaserBeam`)**:
    * Swapped array index keying (`_i`) with target entity ID keying (`_t.getId()`) in client rendering fallback loops. This stabilizes targeting keys, preventing visual beam snapping/jittering as multiple targets are sorted dynamically.
  * **Burst Laser Deactivation Snap Resolution**:
    * Root cause: `K_BURST_DEACT_PLAY` was previously hardcoded to 10 ticks (0.5s), cutting off the actual animations in `fractus_anims.java` (Standard Fractus `laser_burst_deactivate` is 35 ticks / 1.75s; Fractus Prime `laser_burst_prime_deactivate` is 110 ticks / 5.5s). Furthermore, `resetLaser` cleared active deactivation whenever targets died or reset, causing the entity model to snap abruptly back to its idle pose.
    * Fixed with exact duration matching (`BURST_DEACT_DURATION_TICKS` = 35 for Fractus, 110 for Prime), zero-delay handshake at `burstTicks == 1`, deceleration hover shielding during closing keyframes (`currentVel.x * 0.70`, `currentVel.y * 0.85`, `currentVel.z * 0.70`), and a protection guard in `resetLaser` while `burstDeactPlay > 0`.
  * **NeoForge LivingTickEvent Compilation Failure**:
    * Attempting to subscribe to `net.neoforged.neoforge.event.entity.living.LivingEvent.LivingTickEvent` in NeoForge 1.21.x will fail compilation because `LivingTickEvent` is not a nested class under `LivingEvent`. Avoid using this event on client-side rendering classes. Instead, perform high-frequency client-side updates (such as disabling frustum culling via reflection) inside standard rendering events like `RenderLivingEvent.Pre`, which execute safely on the client thread as entities become visible.
  * **WARPED_STEM / CRIMSON_STEM BlockTags Missing Variable Resolution**:
    * In certain NeoForge/Minecraft versions, `BlockTags.WARPED_STEM` and `BlockTags.CRIMSON_STEM` are named differently or absent because they are registered under standard wood/stem tag structures (like `BlockTags.WARPED_STEMS` or `BlockTags.LOGS`).
    * Solution: Replaced direct BlockTag static references with a highly robust name fallback filter: checking `BlockTags.LEAVES` and `BlockTags.LOGS` first, then querying the block's registry path string `BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath().contains("stem") || getPath().contains("hyphae")`. This is 100% compile-safe and fully supports modded stems/hyphae on both 1.21.1 and 1.21.8.
  * **VerdantEngineGravityBeam BlockTags / Blocks Missing Symbol Errors**:
    * On certain versions, the nested client renderer inside `VerdantEngineGravityBeam` lacked imports for `net.minecraft.tags.BlockTags` and `net.minecraft.world.level.block.Blocks`, causing "cannot find symbol" compile failures.
    * Solution: Explicitly imported `net.minecraft.tags.BlockTags` and `net.minecraft.world.level.block.Blocks` at the top of the `VerdantEngineGravityBeam` outer class to ensure the symbol is visible globally across all inner and static classes.
  * **Verdant Engine Terraforming BlockState Scope Resolution (`VerdantAI` / `VerdantEngineOnEntityTickUpdateProcedure`)**:
    * Root cause: In `triggerPlanetaryPound` (burst carve loop) and `performSlowKineticTerraforming`, `st` (the `BlockState` at the iterated vertical column position) was referenced outside/before the vertical `dy` block loop where `st = level.getBlockState(cPos)` is evaluated. This caused multiple `cannot find symbol: variable st` compiler errors across `isBedrock`, `isIndestructible`, `recordMaterialEncounter`, and bedrock cracking checks.
    * Solution: Relocated `shatterBedrock` and `crackBedrock` evaluations inside the inner `dy` loop immediately after `BlockState st = level.getBlockState(...)` is fetched for each specific block coordinate. Material adaptation memory (`recordMaterialEncounter`) and dynamic bedrock penetration thresholds are evaluated per concrete block state, eliminating all uninitialized variable references across both 1.21.1 and 1.21.8.
  * **Single-Layer Stable Orbital Ring Layout (`FractusAI` / `FractusPrimeAI`)**:
    * Replaced the dual-layer (inner/outer) ring shuffling logic in `getVerdantOrbitalRingPosition` with a single, stable horizontal orbital ring orbiting around the lower-middle section of the Verdant Engine hitbox (`verdant.getY() + verdantBb.getYsize() * 0.35`).
    * Slot assignments are deterministic based on entity ID (`Math.abs(entity.getId()) % 16`), ensuring zero position shuffling or jumping when Fractus entities join, leave, or die.
  * **High-Entity Count Super-Optimization Architecture ($O(1)$ Caching & Throttled Scanning)**:
    * **15-Tick Verdant Engine Target Caching**: `findNearestVerdantEngine` caches the nearest Verdant Engine ID in persistent entity data for 15 ticks (0.75s), eliminating redundant 96-block entity queries every tick across all Fractus entities.
    * **Staggered Idle Target Scanning**: `findTarget` checks for new combat targets only once every 10 ticks (0.5s) when the mob has no target or retaliation target, using modulo staggering `(self.tickCount + self.getId()) % 10 == 0`. Reduces idle entity target scanning overhead by 90%.
  * **Conditional Idle Despawning Logic (`checkFractusDespawn`)**:
    * Entities that are purely idle (no active targets, not angry, not summoning, not in burst/laser deactivation, and not guarding a Verdant Engine) automatically despawn if no players are nearby (>128 blocks) or after an idle timeout when >32 blocks from players (>1200 idle ticks, or probabilistically between 600-1200 ticks).
    * Active combat, summoning, guarding, and destroying fire mode lasers prevent despawning.
  * **Biome & Guard Suppression for Destroying Fire Mode & Verdant Engine Immunity**:
    * Fractus entities strictly suppress destroying fire mode when inside the `wood_plains` biome or while guarding a Verdant Engine.
    * Verdant Engine is excluded from targeting in `canTargetNormally` and laser raycasting, preventing lasers from pointing at guarded Verdant Engines.
  * **1.21.8 Procedure Syntax & Entity Type Conversions**:
    * **`faceTarget` Method Signature**: `faceTarget(Entity entity, Entity target)` must accept `Entity` (not `LivingEntity`) for the target parameter so it can target incoming projectiles (`Entity`) as well as mobs (`LivingEntity`).
    * **1.21.8 Persistent Data `Optional` Return Types**: In 1.21.8, `entity.getPersistentData().getBoolean(...)` and `getInt(...)` return `Optional<Boolean>` / `Optional<Integer>`. Direct assignment to primitive `boolean` or `int` causes `incompatible types: Optional cannot be converted to boolean/int` compiler errors. Always use `.orElse(false)` / `.orElse(0)` or helper methods (`persistentBoolean` / `persistentInt`).
  * **Projectile Interception & Anti-Dogpiling Rules**:
    * **Strict Laser Sequence Execution**: Fractus units MUST strictly follow their standard laser charge sequence (`chargeTicks` -> `setLaserState(entity, 2)` -> firing state `3`). Instant 0-tick point-defense firing bypasses are strictly forbidden.
    * **Strict Engagement & Detection Ranges**: Detection and search bounding boxes are strictly capped at **15.0 blocks** radius for Standard Fractus and **23.0 blocks** radius for Fractus Prime. No range inflation or expanded detection radius is permitted.
    * **Strict Activation Requirements**: Fractus units can ONLY intercept projectiles if they meet at least one of these two conditions:
      1. They are at **critical HP (<= 35% health)**.
      2. They are **guarding a Verdant Engine** (a Verdant Engine exists within 256 blocks detection range).
    * **Strict Anti-Dogpiling & Nearest-Ally Priority ("oh, he's got it, it's fine")**: Units claim incoming projectiles by setting `persistentInt(self, "intercept_proj_id", candidate.getId())`. When multiple Fractus units are in range, the unit closest to the projectile takes priority to claim it; farther units defer to the closer ally (`hasCloserAlly`) and remain in their guard positions without dogpiling or playing activation animations.
    * **Laser Beam Surface Truncation**: Lasers must never visually pass through intercepted projectiles or non-living targets. The laser endpoint raycast is dynamically truncated at `truncatedEnd` (the exact position of the intercepted projectile or target), terminating the visual beam and impact particles cleanly at the projectile surface.

### 3. Shader-Compatible Custom Rendering Rules (`FractusLaserBeam` & `VerdantEngineGravityBeam`)
* **`BeaconRenderer` Shader Bypass Delegate (`compatibleBeamBufferSource`)**:
  * Iris, Oculus, and Optifine shader packs hook into `RenderType.beaconBeam`, frequently culling or disabling beacon beam passes drawn outside standard vanilla beacon tile entities.
  * Wrap `MultiBufferSource` in a delegate `ignoredRenderType -> delegate.getBuffer(RenderType.entityTranslucent(texture))` when calling `BeaconRenderer.renderBeaconBeam`. This forces vanilla's beacon rendering algorithm to output into `RenderType.entityTranslucent`, preserving geometry calculations while guaranteeing shader compatibility.
* **Direct Translucent Buffers for Custom Geometry**:
  * Custom 3D meshes (helicities, geodesic shells, rings, ground shockwave disks) must request `bufferSource.getBuffer(RenderType.entityTranslucent(texture))` instead of `RenderType.beaconBeam(texture, true)` to ensure correct depth sorting and alpha blending in shader passes.
* **Event Hook (`RenderLevelStageEvent.AFTER_ENTITIES`)**:
  * Avoid relying solely on `RenderLivingEvent.Pre`/`Post` for custom world geometry when third-party shader packs are present, as shaders frequently cull or shadow-pass `RenderLivingEvent` calls.
  * Use `RenderLevelStageEvent.AFTER_ENTITIES` (or `RenderLevelStageEvent` filtering for `Stage.AFTER_ENTITIES`) to execute custom rendering in world space immediately after living entity passes.
* **Camera-Relative World Coordinates**:
  * Retrieve camera coordinates directly via `mc.gameRenderer.getMainCamera().getPosition()` (`camPos`).
  * Translate geometry relative to camera: `renderStartX = origin.x - camPos.x`, `renderStartY = origin.y - camPos.y`, `renderStartZ = origin.z - camPos.z`.
* **Buffer Source Flushing & Translucent Layers**:
  * Explicitly call `bufferSource.endBatch(RenderType.entityTranslucent(texture))` and `bufferSource.endBatch(RenderType.beaconBeam(texture, ...))` at the conclusion of custom stage rendering to force pipeline flushing.
* **Solid Core Alpha**:
  * Keep central beam core alpha solid (255) so the core remains sharp and vibrant regardless of shader depth/bloom pipelines, while dynamic opacity controls corona and ring layers.
* **1.21.8 `EntityRenderState` Spatial Entity Resolution**:
  * In 1.21.8, `RenderLivingEvent` passes `EntityRenderState` which no longer retains direct `LivingEntity` references.
  * Resolve source entities by comparing `renderState.entityType` and interpolated state coordinates `(renderState.x, renderState.y, renderState.z)` against candidate entities in `mc.level.entitiesForRendering()`.
* **Single Master Fallback Flag**:
  * `MASTER_ENABLED` handles both client beam rendering and server particle fallback. When `MASTER_ENABLED = true`, custom beam rendering runs and server particle beams are suppressed; when `MASTER_ENABLED = false`, custom rendering stops and server particle fallbacks execute automatically. Separate redundant fallback flags like `FALLBACK_PARTICLES` are removed.

