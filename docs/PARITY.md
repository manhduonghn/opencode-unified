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
| Storage access / share target | `opencode-termux` `StorageAccess.java`, `FileReceiverActivity.java`, `file_paths.xml` | `:workspace` | planned | Replace `root-path /` with scoped paths; keep `ACTION_SEND`. |
| GitHub device login / clone | `opencode-termux` `GitHubActivity.java` | `:workspace` | planned | Review hardcoded client id and OAuth scopes before reuse. |
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
