# Code-Based Procedural Generation in MCreator (NeoForge 1.21.1 & 26.1.2)

## Architectural Guide & Optimization Fixes

This guide documents the worldgen architecture, multi-chunk bounds safety, and performance optimization techniques for custom Java feature classes (`Feature<NoneFeatureConfiguration>`).

---

### 1. Eliminating "Detected setBlock in a far chunk" Errors in NeoForge 26.1.2

#### The Problem:
In NeoForge 26.1.2, `WorldGenRegion` strictly enforces chunk write boundaries. If a feature running in chunk `(chunkX, chunkZ)` attempts to set a block outside `[chunkMinX, chunkMinX + 15]` or `[chunkMinZ, chunkMinZ + 15]` (or its immediate 1-chunk region), Minecraft logs:
`Detected setBlock in a far chunk [X, Z], pos: MutableBlockPos...` and discards the block write.

#### The Solution (Intact Chunk Footprint Containment):
Calculate the building footprint bounds (minX, maxX, minZ, maxZ) so they are **100% contained within the local chunk**:

```java
int chunkMinX = origin.getX() & ~15;
int chunkMinZ = origin.getZ() & ~15;

// Building footprint: 5x5 to 10x10
int widthX = 5 + random.nextInt(6); // 5 to 10
int widthZ = 5 + random.nextInt(6); // 5 to 10

// Guaranteed: minX >= chunkMinX and maxX <= chunkMinX + 15
int minX = chunkMinX + random.nextInt(Math.max(1, 16 - widthX));
int maxX = minX + widthX - 1;

int minZ = chunkMinZ + random.nextInt(Math.max(1, 16 - widthZ));
int maxZ = minZ + widthZ - 1;
```

---

### 2. Eliminating Inter-Chunk Gaps (Edge-Anchored Overlapping)

When placing buildings randomly inside `[chunkMinX, chunkMinX + 15 - widthX]`, random offsets can leave 1 to 4 empty blocks on the chunk boundaries. When adjacent chunks both leave boundary blocks empty, a vertical gap/alley forms at chunk borders.

#### The Edge-Anchoring Fix:
In each chunk, explicitly anchor buildings to the 4 chunk edges before placing interior buildings:

```java
// 1. Min X edge tower
minX = chunkMinX;
maxX = minX + widthX - 1;

// 2. Max X edge tower
maxX = chunkMinX + 15;
minX = maxX - widthX + 1;

// 3. Min Z edge tower
minZ = chunkMinZ;
maxZ = minZ + widthZ - 1;

// 4. Max Z edge tower
maxZ = chunkMinZ + 15;
minZ = maxZ - widthZ + 1;
```

This guarantees $100\%$ gapless, seamless coverage across chunk borders with zero vertical alleys.

---

### 3. Performance Optimization Techniques

1. **Pre-Cached Static `BlockState` Constants**:
   ```java
   private static final BlockState PLANKS = Blocks.OAK_PLANKS.defaultBlockState();
   private static final BlockState AIR = Blocks.AIR.defaultBlockState();
   ```
   Avoids allocating new `BlockState` objects per block in generation loops.

2. **`BlockPos.MutableBlockPos` for Zero GC Pressure**:
   ```java
   BlockPos.MutableBlockPos mutablePos = new BlockPos.MutableBlockPos();
   mutablePos.set(x, y, z);
   level.setBlock(mutablePos, PLANKS, 2);
   ```
   Prevents GC spikes during deep foundation scans.

3. **Flag 2 `setBlock` (No Block Updates or Physics Cascades)**:
   Always use `level.setBlock(pos, state, 2)` (Flag 2 = `Block.UPDATE_CLIENTS`).

---

### 4. Required Directory & Data Pack Structure

```text
src/main/resources/data/<modid>/
├── worldgen/
│   ├── configured_feature/
│   │   └── monolithic_oak_wall.json     <-- Binds Java feature type
│   └── placed_feature/
│       └── monolithic_oak_wall_placed.json  <-- Placement rules
└── neoforge/
    └── biome_modifier/
        └── add_monolithic_oak_wall.json <-- Targets biomes (wood_plains, deep_backwoods, the_thicket)
```
