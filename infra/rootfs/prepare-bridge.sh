#!/bin/sh
# Положить bridge-плагин в build/bridge-plugin для сборки образа — из копии third_party/bridge-plugin и только
# если её содержимое совпало с contentSha256 из APK (app/src/main/assets/bridge-plugin.json).
# Тот же алгоритм, что проверяет приложение (BridgePluginArchive.contentDigest).
#   sh infra/rootfs/prepare-bridge.sh
set -eu
here=$(cd "$(dirname "$0")" && pwd)
repo_root=$(cd "$here/../.." && pwd)
source="$repo_root/third_party/bridge-plugin"
manifest="$repo_root/app/src/main/assets/bridge-plugin.json"
field() { python3 -I -c 'import json,sys; print(json.load(open(sys.argv[1]))[sys.argv[2]])' "$1" "$2"; }
version=$(field "$manifest" version)
expected=$(field "$manifest" contentSha256)
packaged=$(field "$source/package.json" version)
if [ "$packaged" != "$version" ]; then
    echo "bridge: в third_party версия $packaged, в APK $version" >&2
    exit 1
fi
actual=$(python3 -I "$repo_root/scripts/bridge-plugin-digest.py" "$source" | cut -d' ' -f2)
if [ "$actual" != "$expected" ]; then
    echo "bridge $version: content SHA-256 $actual != $expected (APK)" >&2
    exit 1
fi
rm -rf "$here/build/bridge-plugin"
mkdir -p "$here/build"
cp -R "$source" "$here/build/bridge-plugin"
echo "bridge $version verified: $actual"
