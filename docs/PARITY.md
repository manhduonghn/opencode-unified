# Parity matrix — unified app

Goal: one APK that keeps the **native OpenCode Android client** as the primary shell while
reusing the **OpenCode for Termux** runtime and device-integration features.

Legend: `done` · `wired` (code present, needs CI verification) · `planned` · `drop` (intentionally
not carried over).

## Source layers

| Area | Upstream source | Target module | Status | Notes |
| --- | --- | --- | --- | --- |
| Native client UI, sessions, chat, settings, pair, OpenTunnel | `anomalyco/opencode-android` `app`, `core`, `server`, `feature:*` | kept as-is | done | Base of the new project. |
| OpenCode runtime installer / self-heal / ELF patcher | `opencode-termux` `app/src/main/java/com/termux/app/OpenCodeBootstrap.java`, `ElfPatch.java`, `OpenCodeInstallState.java`, `app/src/main/cpp/*` | `:runtime` | wired | `OpenCodeWebActivity` coupling replaced by `UnifiedApp`; no `sharedUserId`. |
| Termux bootstrap extraction | `opencode-termux` `TermuxInstaller.java` + `libtermux-bootstrap` | `:runtime` | wired | Called from `MainActivity` through `UnifiedRuntime`; requires `applicationId = com.termux`. |
| Daemon lifecycle (start/stop/pair) | `opencode-termux` `OpenCodeService.java`, `OpenCodeForegroundService.java` | `:runtime` | wired | Service declared in runtime manifest; startup bridged by `UnifiedRuntime.kt`. Native client still owns server connection. |
| Terminal emulator | `opencode-termux` `terminal-emulator`, `terminal-view` | `:feature:terminal` | wired | New Compose `AndroidView` host + `TerminalActivity`, reachable from Settings. Fork had deleted its terminal Activity. |
| File browser / properties | `opencode-termux` `FileManagerActivity.java`, `FileManagerUtils.java` | `:feature:workspace` | wired | New Compose browser rooted at runtime home/prefix/shared storage with path validation and properties dialog. Reachable from Settings via `opencode://workspace/files`. |
| Code editor / viewer | `opencode-termux` `CodeViewerActivity.java` | `:feature:workspace` | planned | Still to port: text view/edit, atomic save, undo caps and search/goto. |
| Directory picker / write operations | `opencode-termux` `DirectoryPickerBottomSheet.java`, `FileManagerUtils.java` | `:feature:workspace` | planned | Still to port: create/rename/copy/move/delete and ZIP with zip-slip checks. Keep all protected-path invariants. |
| Storage permission / symlink setup | `opencode-termux` `StorageAccess.java`, `SettingsActivity.java` storage block | `:runtime` + `:feature:settings` | wired | `StorageAccess` and `TermuxInstaller.setupStorageSymlinks` exposed through `RuntimeStorage`; Settings opens system app details. No legacy storage flags or `root-path /`. |
| Scoped file provider | `opencode-termux` `file_paths.xml` | `app/src/main/res/xml/workspace_file_paths.xml` | wired | Authority `com.termux.workspace.files`; only `files-path home/` and `cache-path workspace-share/`. |
| Share target | `opencode-termux` `FileReceiverActivity.java`, `file_paths.xml` | `:feature:system/share` | replaced | Native `ShareActivity` already handles `ACTION_SEND`/`SEND_MULTIPLE`. Add “save to ~/Downloads” only if needed; never add a second exported receiver or a broad provider. |
| GitHub device login / clone | `opencode-termux` `GitHubActivity.java` | `:workspace` | planned | Do not reuse the hardcoded client id or broad scopes; prefer `gh auth login` device flow with argument arrays and scoped paths. |
| Crash report UI | `opencode-termux` `ReportActivity` manifest entries | `:runtime` | wired | Declared so `TermuxCrashUtils` notifications can open the shared report screen. |
| Backup exclusions | `opencode-termux` Termux home/prefix | `app/src/main/res/xml/data_extraction_rules.xml` | wired | Exclude `usr/`, `home/` (gh/ssh/opencode configs) and `Downloads/` from cloud backup and device transfer. |
| `TermuxApplication` init | `opencode-termux` `TermuxApplication.java` | `UnifiedRuntime.kt` | wired | Crash handler, log config, bootstrap variant, shell manager, Termux-am socket, shell environment and directory checks are installed from `OpenCodeApplication`. Theme/dynamic-color hooks stay dropped: the Compose shell owns appearance. |
| Runtime settings controls | `opencode-termux` `SettingsActivity.java` | `:feature:settings` | wired | Storage status, CLI update check and battery-optimization rows call a public runtime façade; theme/wake-lock rows stay dropped as duplicates. |
| WebView shell / pair cookie flow | `opencode-termux` `OpenCodeWebActivity.java` | — | drop | Native client pair flow replaces it. |
| `sharedUserId` | `opencode-termux` manifest | — | drop | No shared user id in the unified app. |
| `applicationId` | `opencode-termux` bootstrap paths | `app/build.gradle.kts` | kept | Must stay `com.termux`: the bootstrap binaries hardcode `/data/data/com.termux/files/usr`. Launcher shortcuts were retargeted accordingly. |

## Explicit non-goals

- Do not ship two daemon owners. The native client's `OpenCodeClient`/server contract stays
  authoritative; the runtime layer only installs and supervises the local process.
- Do not load the web UI in a WebView. Pairing is done by the native connection screen via
  `opencode pair --remote` / manual server entry.
- Do not copy the three `termux-shared` branding edits globally; keep shared Termux constants
  parameterised instead.

## Security decisions

- Never port the fork's `root-path /` / `external-path .` provider or `MANAGE_EXTERNAL_STORAGE`.
  The workspace provider declares only `files-path home/` and `cache-path workspace-share/`, and
  deep-link paths are canonicalized and rejected outside the runtime roots.
- Do not reuse the fork's GitHub OAuth client id or scopes; keep tokens in `gh`'s credential store
  and exclude `home/.config/gh`, `home/.ssh` and `home/.opencode` from backup.
- Preserve the fork's file-manager safety invariants (protected paths, symlink handling, zip-slip
  checks) when reimplementing its behaviour in Compose.
- Extend the native share sheet instead of adding a second exported `ACTION_SEND` activity.
