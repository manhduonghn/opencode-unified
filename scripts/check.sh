#!/usr/bin/env bash
# The one check: translations, then assemble every module, Android lint, and JVM unit tests.
#   scripts/check.sh            # everything
#   scripts/check.sh :feature:composer   # one module (+ what it depends on)
set -euo pipefail
cd "$(dirname "$0")/.."
python3 scripts/translations.py all
# macOS defaults to Homebrew's JDK 17; elsewhere Gradle runs on the `java` on PATH and
# provisions the JDK 17 toolchain itself.
if [[ "$(uname -s)" == Darwin ]]; then
  export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17}"
fi
if [[ $# -gt 0 ]]; then
  tasks=()
  for module in "$@"; do tasks+=("$module:assembleDebug" "$module:lintDebug" "$module:testDebugUnitTest"); done
  exec ./gradlew "${tasks[@]}"
fi
exec ./gradlew assembleDebug lintDebug testDebugUnitTest
