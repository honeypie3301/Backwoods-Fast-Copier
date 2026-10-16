# Backwoods AI Agent Instructions

## 0. Execution Contract — READ FIRST

This file is the agent's high-priority operating contract. The detailed project knowledge is split into `docs/`. **Do not treat every document as relevant to every task.** Load only the document/section needed for the current request.

### Surgical Editing
- Make the smallest change that fully solves the requested problem.
- Do not rewrite large working files when a localized edit is sufficient.
- Preserve unrelated code, imports, comments, formatting, and existing architecture.
- Do not refactor, rename, reorganize, or “clean up” unrelated code unless explicitly required.
- Never regenerate an entire MCreator-generated file for a localized fix.
- If a small targeted change solves the problem, stop there.

### Scope Lock
Before editing, identify:
1. **TARGET** — the exact behavior requested.
2. **FILES** — the smallest set of files that must change.
3. **VERSION** — the exact Minecraft/NeoForge version involved.
4. **PRESERVE** — unrelated behavior that must remain unchanged.
5. **VERIFY** — the smallest meaningful verification needed.

Do not expand scope merely because another improvement is noticed.

### Efficient Investigation
- Search for the exact class, method, field, identifier, error, or behavior first.
- Read the surrounding code before reading unrelated files.
- Follow references only when required to understand or safely modify the target.
- Do not inspect the entire repository unless the task genuinely requires repository-wide knowledge.
- Do not run unrelated builds/tests or repeated unchanged commands “just to be safe.”

### Version Gating
- Identify whether the task targets NeoForge/Minecraft **1.21.1** or **1.21.8** before editing.
- Never apply a version-specific API/pattern to the other version without verifying it.
- Use the appropriate version document in `docs/`.

### Verification
- Tiny localized change: inspect the diff and perform the relevant syntax/bracket/reference check.
- Medium change: additionally verify affected references and behavior.
- Large or cross-file change: compile/test when appropriate.
- If verification fails, diagnose the actual failure before retrying. Do not blindly repeat the same action.

### Completion Rule
When the requested behavior is implemented and proportionally verified, stop. Do not add unsolicited features, UI, refactors, visual effects, or documentation changes.

## 1. Project Source of Truth

- **Wiki workspace:** `https://github.com/honeypie3301/Backwoods-Wiki`
  - Current workspace for wiki updates, styling, React components, and wiki page data.
- **Mod source of truth:** `https://github.com/honeypie3301/Backwoods-Test`
  - Exclusive source of truth for actual Java source code, MCreator procedures, and entity configurations. **NEVER CLONE.**
- **External reference library:** `https://github.com/honeypie3301/Mods-Source-C0de`
  - Separate mod projects used only for implementation/reference research and comparisons. **NEVER CLONE.**

## 2. Core Rules

- Never add unsolicited visual tabs, sidebars, terminal logs, status indicators, or AI playground modules.
- Keep design clean and professional.
- Never use mock data, fabricated parameters, placeholder statistics, or invented project facts.
- Respect the project's MCreator, Minecraft, Java, and NeoForge constraints.
- Treat each external-reference subdirectory as a separate project; do not assume it is one unified codebase.
- For wiki work, analyze the provided repository commit/diff and update the wiki from actual source information.

### Web Workspace & UI Troubleshooting (Blank White Screen Prevention)
If the web application ever renders as an empty/blank white screen, check these 3 requirements:
1. **Full-Stack Entrypoint:** `package.json` must run `"dev": "tsx server.ts"` and `"start": "node server.ts"` (never standalone `"vite --port=3000"`). The Express server mounts `/api/files` endpoints alongside Vite middlewares.
2. **Missing Dependencies:** Ensure `jszip` is installed (`install_applet_package` with `["jszip"]`). Without it, Vite fails client-side bundle imports when loading `src/App.tsx`.
3. **App.tsx Integrity:** Never overwrite `src/App.tsx` with default boilerplate (`<div></div>`). Always retain the full 21KB dashboard component.

## 3. Detailed Project Knowledge

Consult only when relevant:

- `docs/ARCHITECTURE.md` — cross-system architecture, responsibilities, and project-wide rendering/quality rules.
- `docs/NEOFORGE_1.21.1.md` — 1.21.1 APIs, version conventions, and porting constraints.
- `docs/NEOFORGE_1.21.8.md` — 1.21.8 APIs and version-specific fixes.
- `docs/NEOFORGE_26.1.2.md` — 26.1.2 APIs, modern Mojang mappings, and version-specific syntaxes.
- `docs/LASER_SYSTEM.md` — Fractus, laser, burst, targeting, damage, and orbital-ring systems.
- `docs/PERFORMANCE.md` — mandatory quality checks, shader rules, infection/terraforming optimizations, and performance fixes.
- `docs/WORLDGEN.md` — code-based procedural generation in MCreator/NeoForge 1.21.x.

### Relevance Rule
The documents below are **reference material, not instructions to execute wholesale**. Determine which section applies to the current task and use only that knowledge.

## LARGE-FILE / QUOTA-EFFICIENT INVESTIGATION

When locating code inside large files, do NOT read the entire file by
default.

Use this investigation order:

1. Identify the smallest likely subsystem from the user's request.
2. Search for exact identifiers, strings, method names, fields,
   persistent-data keys, event names, tags, and related references.
3. Inspect only the surrounding code needed to understand each match.
4. Follow references only when necessary.
5. Expand the inspected region gradually if the relevant logic is still
   unclear.
6. Do not modify files during the initial investigation.
7. Once the relevant code is identified, make the smallest surgical edit.
8. Do not repeat a full-file search after the relevant location has
   already been established.
9. Do not inspect unrelated large files "just in case."
10. Read the entire file only when targeted investigation cannot reliably
    establish the required behavior.

For large generated or MCreator files, preserve their existing structure
and avoid unnecessary regeneration or rewriting.
### Investigation Escalation

If targeted search does not provide enough information, expand
investigation incrementally:

TARGETED SEARCH
→ LOCAL CODE BLOCK
→ RELATED METHOD
→ RELATED FILE
→ WIDER SUBSYSTEM
→ ENTIRE FILE ONLY IF NECESSARY

Never jump directly to full-file inspection unless the task genuinely
requires it.