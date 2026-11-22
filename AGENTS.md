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
| `:termux-shared`, `:terminal-emulator`, `:terminal-view` | upstream Termux libraries | Shared utilities and terminal primitives for the runtime/terminal screens. |

`:app` depends on `:runtime`; `:runtime` depends on `:termux-shared`. No reverse dependency.
`UnifiedRuntime.kt` is the only startup bridge. `UnifiedApp.java` holds the application id and
launcher activity so the Java runtime does not need to depend on the Kotlin shell.

## Hard rules

- Do not reintroduce a WebView shell. Pairing belongs to `OpenCodePairingLink` / `AppFeaturePairing`.
- Only one daemon owner: `:runtime` installs and supervises `opencode`; `:server` owns requests.
- Keep `applicationId = "com.termux"` and **no** `sharedUserId`, and no build-type id suffix.
  The bootstrap binaries hardcode `/data/data/com.termux/files/usr`; changing the id breaks the
  runtime. This intentionally conflicts with a stock Termux install (see README).
- Prefix new resources from the Termux layer with `oc_runtime_` / `oc_terminal_` so they cannot
  collide with the client's 57-locale resource set. Do not put them in `values/strings*.xml`; the
  translation checker scans that pattern.
- Keep `minSdk 33`, `compileSdk 37`, JDK 17, NDK `29.0.14206865` in sync between
  `build-logic/src/main/kotlin/OpenCodePlugins.kt` and `.github/workflows/ci.yml`.
- The native client's logging rules still apply: log counts/statuses/timings through `AppLog`,
  never tokens, pairing codes, server addresses, prompts or file contents.

## Build

`scripts/check.sh` runs translation validation, assemble, lint and unit tests. CI uses it too.
Debug APK: `./gradlew assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk`.
