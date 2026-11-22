# Parity matrix — unified app

Goal: one APK that keeps the **native OpenCode Android client** as the primary shell while
reusing the **OpenCode for Termux** runtime and device-integration features.

Legend: `done` · `wired` (code present, needs CI verification) · `planned` · `drop` (intentionally
not carried over).

## Source layers

| Area | Upstream source | Target module | Status | Notes |
| --- | --- | --- | --- | --- |
| Native client UI, sessions, chat, settings, pair, OpenTunnel | `anomalyco/opencode-android` `app`, `core`, `server`, `feature:*` | kept as-is | done | Base of the new project. |
| OpenCode runtime installer / self-heal / ELF patcher | `opencode-termux` `app/src/main/java/com/termux/app/OpenCodeBootstrap.java`, `ElfPatch.java`, `OpenCodeInstallState.java`, `app/src/main/cpp/*` | `:runtime` | done | `OpenCodeWebActivity` coupling replaced by `UnifiedApp`; no `sharedUserId`. |
| Termux bootstrap extraction | `opencode-termux` `TermuxInstaller.java` + `libtermux-bootstrap` | `:runtime` | done | First step of `UnifiedSetupActivity` → `UnifiedSetup`; requires `applicationId = com.termux`. |
| Daemon lifecycle (start/stop/pair) | `opencode-termux` `OpenCodeService.java`, `OpenCodeForegroundService.java` | `:runtime` | done | `UnifiedSetup` starts the foreground service and creates the one-time pair link; `UnifiedHandoff` redeems it with the native `OpenCodeClient`. |
| Terminal emulator | `opencode-termux` `terminal-emulator`, `terminal-view` | `:feature:terminal` | done | New Compose `AndroidView` host + `TerminalActivity`, reachable from Settings. Fork had deleted its terminal Activity. |
| File browser / properties | `opencode-termux` `FileManagerActivity.java`, `FileManagerUtils.java` | `:feature:workspace` | done | New Compose browser rooted at runtime home/prefix/shared storage with path validation and properties dialog. Reachable from Settings via `opencode://workspace/files`. |
| Code editor / viewer | `opencode-termux` `CodeViewerActivity.java` | `:feature:workspace` | done | Compose editor with wrap/text-size controls, match count, 4 MiB limit, 30-snapshot undo cap, atomic save and POSIX mode preservation. |
| Directory picker / write operations | `opencode-termux` `DirectoryPickerBottomSheet.java`, `FileManagerUtils.java` | `:feature:workspace` | done | `WorkspaceFiles` implements safe names, protected paths, symlink-as-link copy/delete and ZIP create/extract with zip-slip checks. The browser exposes new file/folder, rename, copy/paste, delete and zip. A dedicated picker sheet remains optional. |
| Storage permission / symlink setup | `opencode-termux` `StorageAccess.java`, `SettingsActivity.java` storage block | `:runtime` + `:feature:settings` | done | `StorageAccess` and `TermuxInstaller.setupStorageSymlinks` exposed through `RuntimeStorage`; Settings opens system app details. No legacy storage flags or `root-path /`. |
| Scoped file provider | `opencode-termux` `file_paths.xml` | `devtools/src/main/res/xml/devtools_file_paths.xml` | done | The app's single `FileProvider` (authority `${applicationId}.devtools.files`) now also exposes `files-path home/` and `cache-path workspace-share/`; no `root-path`. |
| Share target / save to Downloads | `opencode-termux` `FileReceiverActivity.java`, `file_paths.xml` | `:feature:system/share` + `:runtime` | done | Native `ShareActivity` stays the only exported `ACTION_SEND`/`SEND_MULTIPLE` receiver. Added a “Save to Downloads” action that copies staged files, images and received text into `~/Downloads` through `RuntimeStorage.downloads()`, with sanitised unique names. No second receiver or broad provider. |
| GitHub login / clone | `opencode-termux` `GitHubActivity.java` | `:feature:workspace` | done | `gh` owns credentials: a pasted token is piped to `gh auth login --with-token` and never saved in app state. Clone uses argument arrays with `GIT_TERMINAL_PROMPT=0` and validates HTTPS GitHub URLs and home-relative destinations. No client id or scopes are embedded. |
| Crash report UI | `opencode-termux` `ReportActivity` manifest entries | `:runtime` | done | Declared so `TermuxCrashUtils` notifications can open the shared report screen. |
| Backup exclusions | `opencode-termux` Termux home/prefix | `app/src/main/res/xml/data_extraction_rules.xml` | done | Exclude `usr/`, `home/` (gh/ssh/opencode configs) and `Downloads/` from cloud backup and device transfer. |
| `TermuxApplication` init | `opencode-termux` `TermuxApplication.java` | `UnifiedRuntime.kt` | done | Crash handler, log config, bootstrap variant, shell manager, Termux-am socket, shell environment and directory checks are installed from `OpenCodeApplication`. Theme/dynamic-color hooks stay dropped: the Compose shell owns appearance. |
| Runtime settings controls | `opencode-termux` `SettingsActivity.java` | `:feature:settings` | done | Storage status, CLI update check and battery-optimization rows call a public runtime façade; theme/wake-lock rows stay dropped as duplicates. |
| WebView shell / pair cookie flow | `opencode-termux` `OpenCodeWebActivity.java` | — | drop | Replaced by the silent handoff: `UnifiedSetupActivity` prepares and pairs, then opens the native chat directly. |
| Silent auto-pair handoff | `opencode-termux` WebView pair URL | `app/UnifiedSetupActivity.kt`, `app/UnifiedHandoff.kt`, `runtime/UnifiedSetup.java` | done | Local one-time link is redeemed through `OpenCodeClient.redeemPairing`, saved with `remember()` before `MainActivity` builds its store, so no native pairing UI appears. |
| `sharedUserId` | `opencode-termux` manifest | — | drop | No shared user id in the unified app. |
| `applicationId` | `opencode-termux` bootstrap paths | `app/build.gradle.kts` | kept | Must stay `com.termux`: the bootstrap binaries hardcode `/data/data/com.termux/files/usr`. Launcher shortcuts were retargeted accordingly. |

## Explicit non-goals

- Do not ship two daemon owners. The native client's `OpenCodeClient`/server contract stays
  authoritative; the runtime layer only installs and supervises the local process.
- Do not load the web UI in a WebView. The boot core pairs silently with the native client; the
  native connection screen remains only for adding other, remote servers.
- Do not copy the three `termux-shared` branding edits globally; keep shared Termux constants
  parameterised instead.

## Security decisions

- Never port the fork's `root-path /` / `external-path .` provider or `MANAGE_EXTERNAL_STORAGE`.
  The workspace provider declares only `files-path home/` and `cache-path workspace-share/`, and
  deep-link paths are canonicalized and rejected outside the runtime roots.
- Do not reuse the fork's GitHub OAuth client id or scopes; the Compose screen pipes the token to
  `gh auth login --with-token`, keeps it out of saved state, and relies on `gh`'s credential store.
  `home/` (including `.config/gh`, `.ssh` and `.opencode`) is excluded from backup.
- Preserve the fork's file-manager safety invariants (protected paths, symlink handling, zip-slip
  checks) when reimplementing its behaviour in Compose.
- Extend the native share sheet instead of adding a second exported `ACTION_SEND` activity. The
  “Save to Downloads” action reuses `ShareActivity`'s staged copies and writes only into the runtime
  home's `Downloads/` folder.
