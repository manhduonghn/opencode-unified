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
| File manager, code editor, picker, properties | `opencode-termux` `FileManagerActivity.java`, `FileManagerUtils.java`, `CodeViewerActivity.java`, bottom sheets | `:workspace` | planned | Prefer porting behaviour to Compose to avoid resource-name collisions. |
| Storage permission / symlink setup | `opencode-termux` `StorageAccess.java`, `SettingsActivity.java` storage block | `:runtime` + `:feature:settings` | partial | `StorageAccess.java` is already in `:runtime` but package-private. Do **not** port legacy storage flags or `root-path /`; expose a runtime façade and use SAF/scoped paths. |
| Share target | `opencode-termux` `FileReceiverActivity.java`, `file_paths.xml` | `:feature:system/share` | replaced | Native `ShareActivity` already handles `ACTION_SEND`/`SEND_MULTIPLE`. Add “save to ~/Downloads” only if needed; never add a second exported receiver or a broad provider. |
| GitHub device login / clone | `opencode-termux` `GitHubActivity.java` | `:workspace` | planned | Do not reuse the hardcoded client id or broad scopes; prefer `gh auth login` device flow with argument arrays and scoped paths. |
| Crash report UI | `opencode-termux` `ReportActivity` manifest entries | `:runtime` | wired | Declared so `TermuxCrashUtils` notifications can open the shared report screen. |
| Backup exclusions | `opencode-termux` Termux home/prefix | `app/src/main/res/xml/data_extraction_rules.xml` | wired | Exclude `usr/`, `home/` (gh/ssh/opencode configs) and `Downloads/` from cloud backup and device transfer. |
| `TermuxApplication` init | `opencode-termux` `TermuxApplication.java` | `UnifiedRuntime.kt` | planned | Crash/log setup, shell manager, Termux-am socket and directory checks are not yet mirrored; decide each explicitly. |
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
  A future workspace provider uses only `files-path home/` and `cache-path workspace-share/`.
- Do not reuse the fork's GitHub OAuth client id or scopes; keep tokens in `gh`'s credential store
  and exclude `home/.config/gh`, `home/.ssh` and `home/.opencode` from backup.
- Preserve the fork's file-manager safety invariants (protected paths, symlink handling, zip-slip
  checks) when reimplementing its behaviour in Compose.
- Extend the native share sheet instead of adding a second exported `ACTION_SEND` activity.
