# Unified OpenCode Android — agent rules

This repository merges two products into one APK:

- the native OpenCode Android client (Compose UI, pairing, sessions, server contract), and
- the OpenCode for Termux runtime (bootstrap, glibc loader patching, local service supervision).

The native client remains the **shell**. The Termux layer is a **runtime dependency**, not a second
UI. See [docs/PARITY.md](docs/PARITY.md) for what has been carried over and what is still planned.

## Modules

| Module | Origin | Role |
| --- | --- | --- |
| `:app`, `:core`, `:server`, `:feature:*`, `:devtools` | `anomalyco/opencode-android` | Native client. Keep its store/dependency/shared-key architecture. |
| `:runtime` | custom `opencode-termux` `app` classes + `cpp` | Installer, ELF patcher, launcher scripts, daemon supervision. |
| `:termux-ui` | upstream `termux-app` app module (terminal UI only) | Real Termux Activity/Service/drawer/extra-keys toolbar, ported with plugin/bootstrap/report/storage code removed. |
| `:feature:terminal` | previous Compose host | Kept only as a bridge; `opencode://terminal` now routes to `:termux-ui`. |
| `:feature:workspace` | `opencode-termux` file manager, code viewer, GitHub activity | Compose file browser, editor and `gh`-backed clone surface. The file browser's GitHub button is the only visible GitHub entry; clone destinations come from the open folder. |
| `:termux-shared`, `:terminal-emulator`, `:terminal-view` | upstream Termux libraries | Shared utilities and terminal primitives for the runtime/terminal screens. |

`:app`, `:feature:settings`, `:feature:workspace` and `:feature:system` (share) depend on `:runtime`;
`:runtime` depends on `:termux-shared`. No reverse dependency.
`UnifiedRuntime.kt` is the only startup bridge. `RuntimeStorage.java` and `RuntimeShell.java` are
the only public façades from `:runtime` into Compose features; `TermuxInstaller`, `StorageAccess`
and `TermuxShell` stay package-private. `UnifiedApp.java` holds the application id and launcher
activity so the Java runtime does not need to depend on the Kotlin shell.

## Hard rules

- Do not reintroduce a WebView shell. Pairing belongs to `OpenCodePairingLink` / `AppFeaturePairing`.
- Only one daemon owner: `:runtime` installs and supervises `opencode`; `:server` owns requests.
- Keep `applicationId = "com.termux"` and **no** `sharedUserId`, and no build-type id suffix.
  The bootstrap binaries hardcode `/data/data/com.termux/files/usr`; changing the id breaks the
  runtime. This intentionally conflicts with a stock Termux install (see README).
- Prefix new resources from the Termux layer with `oc_runtime_`, `oc_terminal_`, `oc_workspace_` or
  `oc_share_` so they cannot collide with the client's 57-locale resource set. Do not put them in
  `values/strings*.xml`; the translation checker scans that pattern.
- Keep `minSdk 33`, `compileSdk 37`, JDK 17, NDK `29.0.14206865` in sync between
  `build-logic/src/main/kotlin/OpenCodePlugins.kt` and `.github/workflows/ci.yml`.
- **Never raise `TARGET_SDK` above 28.** Android 10's W^X rule blocks `execve` of files under
  `/data/data/com.termux/files` for apps targeting API 29+, so apt/bash/dpkg and every glibc
  launcher fail instantly and bootstrap can never finish. The fork targets 28 for this reason.
- Never call Guava's `com.google.common.io.MoreFiles` / `RecursiveDeleteOption` from shared code.
  Gradle resolves Guava's **Android** variant on the app runtime classpath; that jar omits those
  JRE-only classes even though javac accepts them from the `-jre` variant. Use the plain-Java
  symlink-safe helpers in `termux-shared` `FileUtils` instead.
- The native client's logging rules still apply: log counts/statuses/timings through `AppLog`,
  never tokens, pairing codes, server addresses, prompts or file contents.

## Build

`scripts/check.sh` runs translation validation, assemble, lint and unit tests. CI uses it too.
Debug APK: `./gradlew assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk`.
