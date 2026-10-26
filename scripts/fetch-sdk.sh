#!/usr/bin/env bash
# Fetch the native UI SDK at the exact commit pinned in sdk.lock and verify it.
# Usage: scripts/fetch-sdk.sh [target-dir]   (default: ../dsh-mobile-plugin-sdk)
# Then build with: ./gradlew -PdshSdkRepo=<target-dir> :app:assembleDebug
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
repo="$(sed -n 's/^repository=//p' "$root/sdk.lock")"
commit="$(sed -n 's/^commit=//p' "$root/sdk.lock")"
target="${1:-$root/../dsh-mobile-plugin-sdk}"
if [ ! -d "$target/.git" ]; then
  git clone --filter=blob:none "$repo" "$target"
fi
git -C "$target" fetch --quiet origin "$commit"
git -C "$target" -c advice.detachedHead=false checkout --quiet "$commit"
actual="$(git -C "$target" rev-parse HEAD)"
if [ "$actual" != "$commit" ]; then
  echo "SDK checkout mismatch: expected $commit, got $actual" >&2
  exit 1
fi
echo "SDK pinned at $commit in $target"
