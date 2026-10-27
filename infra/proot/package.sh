#!/bin/sh
# Достать из .deb Termux библиотеки для app/src/main/jniLibs/<abi>/ и привести имена к правилу APK
# (Android распаковывает из APK только lib*.so):
#   proot                    -> libproot.so         (DT_NEEDED libtalloc.so.2 -> libtalloc.so)
#   libexec/proot/loader     -> libproot-loader.so
#   libtalloc.so.2           -> libtalloc.so        (SONAME libtalloc.so)
#   libandroid-shmem.so      -> libandroid-shmem.so
# RUNPATH на /data/data/com.termux убирается: библиотеки берутся из nativeLibraryDir (LD_LIBRARY_PATH).
#   sh infra/proot/package.sh <папка с .deb> <abi> <папка вывода>
set -eu
debs=$1
abi=$2
out=$3
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
for name in proot libtalloc libandroid-shmem; do
    deb=$(ls "$debs"/${name}_*.deb | head -1)
    dpkg-deb -x "$deb" "$work/root"
done
prefix="$work/root/data/data/com.termux/files/usr"
mkdir -p "$out/$abi"
cp "$prefix/bin/proot" "$out/$abi/libproot.so"
cp "$prefix/libexec/proot/loader" "$out/$abi/libproot-loader.so"
cp "$(readlink -f "$prefix/lib/libtalloc.so.2")" "$out/$abi/libtalloc.so"
cp "$prefix/lib/libandroid-shmem.so" "$out/$abi/libandroid-shmem.so"
patchelf --replace-needed libtalloc.so.2 libtalloc.so "$out/$abi/libproot.so"
patchelf --set-soname libtalloc.so "$out/$abi/libtalloc.so"
for lib in libproot.so libtalloc.so libandroid-shmem.so; do
    patchelf --remove-rpath "$out/$abi/$lib"
done
# 16 КБ страницы (Android 15+): каждый LOAD выровнен не меньше чем на 0x4000.
for lib in "$out/$abi"/*.so; do
    readelf -lW "$lib" | python3 -I -c '
import sys
aligns = [int(line.split()[-1], 16) for line in sys.stdin if line.split()[:1] == ["LOAD"]]
sys.exit(0 if aligns and min(aligns) >= 0x4000 else sys.argv[1] + ": LOAD alignment < 16 KB")
' "$lib"
done
readelf -d "$out/$abi/libproot.so" | grep -E 'NEEDED|RUNPATH' || true
