# Backwoods Fast Copier

Companion workspace and fast file copier for Backwoods mod development and wiki documentation.

## Architectural Guidelines for AI Assistants & Contributors

1. **Full-Stack Dev Entry Point**:
   - `server.ts` handles API routes and proxies Vite middleware.
   - Scripts in `package.json` run `dev: "tsx server.ts"` and `start: "node server.ts"`.

2. **Updated Indicator System**:
   - On initial clone or reload, all cloned files receive identical filesystem timestamps (`mtime`).
   - The `/api/files` endpoint in `server.ts` queries `git log -n 1 --name-only --format=%ct HEAD uploaded_files/` to automatically mark files modified in the latest commit as `isEdited: true`.
   - **Persistent Display**: Files flagged as `isEdited` display an amber border, glow, and an animated `UPDATED` badge.
   - **Strict Persistence Rule**: Selecting, inspecting, or viewing a file in the sidebar or right panel **MUST NEVER** clear the updated indicator.
   - **Clearing Rule**: The indicator clears **ONLY** when the user clicks the "Mark as Read" button or copies the file's content to the clipboard.

3. **Performance & Virtualization**:
   - Large dump files (10MB+) are streamed directly via `/api/files/:filename/content?raw=1`.
   - Previews in `RightPanelViewer.tsx` use virtualized row rendering to prevent browser freeze.

4. **GitDigest Delimiter Cleanliness**:
   - Never copy or export delimiter lines (`================================================` or `File: ...`) at the top of Java procedures or classes.
   - All copied content is automatically sanitized to prevent `illegal start of type` syntax errors in MCreator.

5. **Zero-Error Code Verification via `build.gradle` & Digest Files**:
   - Before writing or editing Minecraft mod/procedure code, ALWAYS analyze `build.gradle` and the digest files in `uploaded_files/` (`backwoods-1.21.1_digest.txt`, `backwoods-26.1.2_digest.txt`, `procedures_digest-*.txt`).
   - Use `build.gradle` to establish compiler ground truth: exact Minecraft version, NeoForge build (`21.1.232` vs `26.1.2.95`), Java toolchain version (`JavaLanguageVersion.of(21)` vs `of(25)`), and mapping channels.
   - Cross-reference existing procedure digests to verify real method signatures, Blaze3D vertex rendering calls, and event hooks to eliminate hallucinations and signature drift.
