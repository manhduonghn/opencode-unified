# Unified OpenCode for Android

One APK that combines:

- the **native OpenCode Android client** — Compose UI, pairing (including
  `opencode pair --remote` / OpenTunnel links), sessions, transcript, composer, settings, voice and
  devtools; and
- the **OpenCode for Termux runtime** — native OpenCode CLI installation, glibc loader patching,
  launcher repair, foreground service supervision and the Termux terminal libraries.

The native client is the shell. The Termux layer runs underneath it and never renders a WebView.

## Layout

```
app, core, server, feature/*, devtools   native client (kept from opencode-android)
runtime                                 installer + ELF patcher + daemon supervision
termux-shared                           Termux utilities (copied library)
terminal-emulator, terminal-view        terminal primitives (copied libraries)
```

`app/src/main/kotlin/ai/opencode/app/UnifiedRuntime.kt` is the single startup bridge into the
runtime. See [docs/PARITY.md](docs/PARITY.md) for the integration checklist and
[AGENTS.md](AGENTS.md) for contributor rules.

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

This is an integration scaffold. The native client is complete; the runtime installer/daemon is
wired at startup. Terminal, workspace (files/GitHub/share) and settings surfaces are tracked in
`docs/PARITY.md` and are being ported incrementally.

## Licensing

See [ATTRIBUTION.md](ATTRIBUTION.md) before publishing a build. The Termux-derived portions are
GPLv3; that obligation applies to the combined work.
