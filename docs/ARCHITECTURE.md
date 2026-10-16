# Backwoods Mod — Architecture Reference

# Backwoods Mod — Ultimate Architecture Reference Guide

This document is the absolute permanent reference for the Backwoods Mod development across Minecraft versions **1.21.1** and **1.21.8**, covering combat AI, laser rendering pipelines, NeoForge porting rules, code-based world generation, and mandatory syntax patterns.

---



## Cross-System Operating Notes

The detailed subsystem documents are intentionally separated so the agent can consult only the relevant knowledge instead of loading the entire historical reference for every task.

- Laser/combat architecture: see `LASER_SYSTEM.md`.
- Version-specific architecture/API behavior: see `NEOFORGE_1.21.1.md`, `NEOFORGE_1.21.8.md`, and `NEOFORGE_26.1.2.md`.
- Procedural generation: see `WORLDGEN.md`.
- Performance, rendering, and resolved quality rules: see `PERFORMANCE.md`.

## Additional Architecture & Rendering Instructions (preserved from original AGENTS.md)

### Additional Instructions:
- Read the entire commit, including changed files, added files, removed files, and renamed files.
- Determine what has changed from a user's perspective, not just the source code.
- Update existing wiki pages when needed instead of always creating new ones.
- Create new wiki pages only if the feature is significant enough to deserve its own page.
- Remove or rewrite outdated information so the wiki always reflects the latest version of the mod.
- Preserve the existing writing style, formatting, and structure of the wiki unless there is a good reason to improve it.
- Do not invent features or mechanics that are not supported by the commit.
- If something is unclear from the code alone, explicitly mark it as needing verification instead of guessing.
- **NEVER wrap numbers, single variables, ranges, or dimensions in LaTeX or math delimiters** (do NOT use `\( ... \)`, `\[ ... \]`, or `$ ... $`).
- **ALWAYS write numbers, ranges, coordinates, and inline mathematical expressions as plain text** (e.g., write "from 5 down to 2 blocks", NOT "\(5\) down to \(2\) blocks").
- **Reserve Markdown code blocks (`...`) exclusively for multi-line code snippets or direct API/method calls.**

---

