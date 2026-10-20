#!/usr/bin/env bash
set -euo pipefail
ATLAS_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
if [ -f "$ATLAS_ROOT/../../.tools/env.sh" ]; then
    source "$ATLAS_ROOT/../../.tools/env.sh"
fi
if [ -d "$ATLAS_ROOT/.tools/android-sdk" ]; then
    export ANDROID_HOME="$ATLAS_ROOT/.tools/android-sdk"
    export ANDROID_SDK_ROOT="$ANDROID_HOME"
fi
export GRADLE_USER_HOME="$ATLAS_ROOT/.tools/gradle-home"
export ANDROID_USER_HOME="$ATLAS_ROOT/.tools/android-home"
mkdir -p "$GRADLE_USER_HOME" "$ANDROID_USER_HOME" "$ATLAS_ROOT/logs"
cd "$ATLAS_ROOT"
if [ -n "${ANDROID_HOME:-}" ]; then
    printf 'sdk.dir=%s\n' "$ANDROID_HOME" > local.properties
fi
./gradlew "$@"
