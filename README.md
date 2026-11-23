# Unified OpenCode for Android

One APK that combines:

- the **native OpenCode Android client** — Compose UI, pairing (including
  `opencode pair --remote` / OpenTunnel links), sessions, transcript, composer, settings, voice and
  devtools; and
- the **OpenCode for Termux runtime** — native OpenCode CLI installation, glibc loader patching,
  launcher repair, foreground service supervision and the Termux terminal libraries.

The Termux layer is the boot core and the native client is the chat surface. The Termux layer never
renders a WebView.

## Layout

```
app, core, server, feature/*, devtools   native client (kept from opencode-android)
runtime                                 installer + ELF patcher + daemon supervision
termux-ui                               real upstream Termux terminal UI (trimmed, non-exported)
termux-shared                           Termux utilities (copied library)
terminal-emulator, terminal-view        terminal primitives (copied libraries)
```
`app/src/main/kotlin/ai/opencode/app/UnifiedSetupActivity.kt` is the launcher and first screen. It
drives `runtime/.../UnifiedSetup.java` through bootstrap extraction, runtime installation, daemon
start and one-time pairing. `UnifiedHandoff.kt` redeems the local pair link with the native client's
own `OpenCodeClient`, saves the resulting server, then opens `MainActivity` straight into chat —
the native pairing screen is never shown. `UnifiedRuntime.kt` installs the process-wide Termux
plumbing from `OpenCodeApplication`.

See [docs/PARITY.md](docs/PARITY.md) for the integration checklist and [AGENTS.md](AGENTS.md) for
contributor rules.

## Build

CI builds and uploads a debug APK from `.github/workflows/ci.yml`. Locally:

```sh
./gradlew assembleDebug
# app/build/outputs/apk/debug/app-debug.apk
```

Requirements: JDK 17, Android SDK platform 37 (minor 1), build-tools 37, NDK `29.0.14206865`.
`scripts/check.sh` runs the full check suite used by CI.

> **Package name:** the unified app intentionally keeps `applicationId = com.termux`. Termux
> bootstrap binaries hardcode `/data/data/com.termux/files/usr`, so a different id breaks the local
> runtime. It will conflict with a stock Termux installation; back up data before installing.

## Status

The merge is complete. The app opens on a preparation screen: the Termux runtime installs and
starts the local `opencode` process, creates a one-time pair link and silently connects the native
client before opening the chat UI. Terminal, workspace (files, editor, GitHub), share-to-Downloads
and runtime settings surfaces are all present. See `docs/PARITY.md` for the full parity matrix and
the intentional non-goals/drops (WebView shell, `sharedUserId`, broad storage permissions).

### Crash reports

An uncaught exception writes `opencode-crash-<timestamp>.txt` to **public `Download/`** (via
`MediaStore`, no storage permission needed) and to the runtime home's `Downloads/` (visible in the
app's file browser). The fork's private `~/crash_log.md` still works too. If the app fails on
launch, attach the newest `opencode-crash-*.txt` from `Download/` to a bug report — the report
contains the device, app version, thread and full stack trace.

## Licensing

See [ATTRIBUTION.md](ATTRIBUTION.md) before publishing a build. The Termux-derived portions are
GPLv3; that obligation applies to the combined work.
