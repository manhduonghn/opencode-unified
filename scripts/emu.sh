#!/usr/bin/env bash
# Emulator workflow for the OpenCode_API_36 AVD (Pixel 8, android-36 google_apis_playstore;
# arm64 on Apple silicon, x86_64 on Intel/AMD).
#   scripts/emu.sh boot [port]     # headless, read-only (several agents can each boot one; even port 5554–5680)
#   scripts/emu.sh install [port]  # assembleDebug + install + launch
#   scripts/emu.sh shot <file.png> [port]
#   scripts/emu.sh kill [port]
# The session-lab fixture server on the host is http://10.0.2.2:4110 from the emulator.
# Never touch the Rebase_API_36 AVD.
set -euo pipefail
cd "$(dirname "$0")/.."
# Default to Android Studio's SDK location for the platform. On macOS Gradle runs on Homebrew's
# JDK 17; on Linux it runs on the system JDK and fetches JDK 17 for the build itself.
if [[ "$(uname -s)" == Darwin ]]; then
  export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
  export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17}"
else
  export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
fi
ADB="$ANDROID_HOME/platform-tools/adb"
EMULATOR="$ANDROID_HOME/emulator/emulator"
AVD=OpenCode_API_36
APP_ID=ai.opencode.mobilecode.debug

command=${1:-}
case "$command" in
  boot)
    port=${2:-5554}
    avd_home="${ANDROID_AVD_HOME:-${ANDROID_USER_HOME:-$HOME/.android}/avd}"
    gpu_mode=auto
    if [[ -f "$avd_home/$AVD.avd/config.ini" ]]; then
      gpu_mode=$(sed -n 's/^hw\.gpu\.mode[[:space:]]*=[[:space:]]*//p' "$avd_home/$AVD.avd/config.ini" | tr -d '\r')
    fi
    launcher=("$EMULATOR")
    if [[ "$gpu_mode" == host && "$(uname -s)" == Linux && -z "${DISPLAY:-}" ]]; then
      # Mesa needs an X display here even with -no-window. Each lane owns its Xvfb.
      launcher=(env LIBGL_ALWAYS_SOFTWARE=1 GALLIUM_DRIVER=llvmpipe xvfb-run -a "$EMULATOR")
    fi
    # 37.2.12 can load legacy SwiftShader despite a swangle AVD setting unless -gpu is explicit.
    nohup "${launcher[@]}" -avd "$AVD" -port "$port" -gpu "${gpu_mode:-auto}" -read-only -no-window -no-audio -no-boot-anim -no-snapshot-save \
      >"${TMPDIR:-/tmp}/opencode-emulator-$port.log" 2>&1 &
    "$ADB" -s "emulator-$port" wait-for-device
    until [[ "$("$ADB" -s "emulator-$port" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == 1 ]]; do sleep 2; done
    # SystemUI sizes its status bar before the emulator applies the display's final density
    # (63–74 px instead of 132 on this AVD), which clips the clock and shrinks every app's top
    # inset until the next configuration change. Once SystemUI has settled, a density round trip
    # makes it re-read its dimensions.
    status_bar() { "$ADB" -s "emulator-$port" shell dumpsys window | grep -m1 -oE 'type=statusBars frame=[^ ]*'; }
    density=$("$ADB" -s "emulator-$port" shell wm density | sed -n 's/^Physical density: //p' | tr -d '\r')
    for _ in 1 2 3; do
      [[ -n "$density" ]] || break
      sleep 6
      "$ADB" -s "emulator-$port" shell wm density $((density + 1)) >/dev/null
      sleep 2
      "$ADB" -s "emulator-$port" shell wm density reset >/dev/null
      sleep 3
      # Done once the height holds: SystemUI didn't resize it again after the round trip.
      after=$(status_bar)
      sleep 3
      [[ "$after" == "$(status_bar)" ]] && break
    done
    echo "emulator-$port booted"
    ;;
  install)
    port=${2:-5554}
    ./gradlew :app:assembleDebug
    "$ADB" -s "emulator-$port" install -r -t app/build/outputs/apk/debug/app-debug.apk
    "$ADB" -s "emulator-$port" shell am start -W -n "$APP_ID/ai.opencode.app.MainActivity"
    ;;
  shot)
    file=${2:?usage: emu.sh shot <file.png> [port]}
    port=${3:-5554}
    "$ADB" -s "emulator-$port" exec-out screencap -p >"$file"
    echo "$file"
    ;;
  kill)
    port=${2:-5554}
    "$ADB" -s "emulator-$port" emu kill
    ;;
  *)
    sed -n '2,10p' "$0"
    exit 2
    ;;
esac
