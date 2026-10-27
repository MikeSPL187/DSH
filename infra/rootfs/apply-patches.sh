#!/bin/sh
# Наложить Android-патчи на пакеты агента в образе.
#
# Патчи — обычные unified diff (`make-patches.py`) против пакетов ровно тех версий, что в
# `agent/package-lock.json`. Сдвинулся участок — `patch` падает, и сборка образа падает вместе
# с ним, а не выпускает образ «наполовину пропатченным».
set -eu

patches_dir=${PATCHES_DIR:-/patches/android}
root=${ROOT:-/}
count=0

for patch_file in "$patches_dir"/*.patch; do
    [ -f "$patch_file" ] || continue
    echo "applying $(basename "$patch_file")"
    patch -p1 -d "$root" --batch --forward --no-backup-if-mismatch < "$patch_file"
    count=$((count + 1))
done

if [ "$count" -eq 0 ]; then
    echo "no Android patches found under $patches_dir" >&2
    exit 1
fi

echo "applied $count Android patches"
