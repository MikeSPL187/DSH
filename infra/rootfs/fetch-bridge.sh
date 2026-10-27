#!/bin/sh
# Положить bridge-плагин в build/bridge-plugin — только если содержимое тега совпало с contentSha256
# из APK (app/src/main/assets/bridge-plugin.json). Тот же алгоритм, что проверяет приложение.
#   sh infra/rootfs/fetch-bridge.sh
set -eu
here=$(cd "$(dirname "$0")" && pwd)
repo_root=$(cd "$here/../.." && pwd)
manifest="$repo_root/app/src/main/assets/bridge-plugin.json"
field() { python3 -I -c 'import json,sys; print(json.load(open(sys.argv[1]))[sys.argv[2]])' "$manifest" "$1"; }
repository=$(field repository)
version=$(field version)
expected=$(field contentSha256)

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
git -c advice.detachedHead=false clone --quiet --depth 1 --branch "v$version" "$repository" "$work/src"
git -C "$work/src" archive --format=tar --prefix=package/ HEAD > "$work/plugin.tar"
actual=$(python3 -I "$repo_root/scripts/bridge-plugin-digest.py" "$work/plugin.tar" | cut -d' ' -f2)
if [ "$actual" != "$expected" ]; then
    echo "bridge $version: content SHA-256 $actual != $expected (APK)" >&2
    exit 1
fi
rm -rf "$here/build/bridge-plugin"
mkdir -p "$here/build"
tar -xf "$work/plugin.tar" -C "$work"
mv "$work/package" "$here/build/bridge-plugin"
echo "bridge $version verified: $actual"
