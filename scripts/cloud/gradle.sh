#!/usr/bin/env bash
# Gradle в облачном контейнере: SDK из setup.sh, SDK плагинов — third_party/plugin-sdk,
# проверка зависимостей lenient — ТОЛЬКО локально в песочнице (разрешение владельца).
# gradle/verification-metadata.xml не меняется и не коммитится.
#   bash scripts/cloud/gradle.sh test lintDebug :app:assembleDebug --continue -q
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
export ANDROID_HOME="${ANDROID_HOME:-/home/user/android-sdk}" ANDROID_SDK_ROOT="${ANDROID_HOME:-/home/user/android-sdk}"
PLUGIN_SDK="${DSH_PLUGIN_SDK:-$ROOT/third_party/plugin-sdk}"
cd "$ROOT"
set +e
./gradlew --dependency-verification lenient -PdshSdkRepo="$PLUGIN_SDK" "$@" 2>&1 \
  | grep -v '^Picked up\|dependency verification\|verification-metadata\|^\s*- On \|  - \(Artifact\|On artifact\)'
exit "${PIPESTATUS[0]}"
