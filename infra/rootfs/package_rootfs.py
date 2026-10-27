#!/usr/bin/env python3
"""Упаковать экспортированные tar образов в rootfs-*.bin и написать rootfs-manifest.json.

    python3 -I infra/rootfs/package_rootfs.py <dsh_version> <rootfs_version> <bridge_version> \
        <x86_64.tar> <arm64.tar> <repository owner/name> [dist]

Сжатие детерминированное (gzip mtime=0), SHA-256 — в манифесте. Манифест кладётся в APK
(app/src/main/assets/rootfs-manifest.json): приложение скачивает образ только по этим ссылкам и
только с этим SHA-256.
"""
import gzip
import hashlib
import json
import os
import shutil
import sys


def compress_tar(src_tar: str, dst_bin: str) -> tuple[str, int]:
    with open(src_tar, "rb") as fi, gzip.GzipFile(dst_bin, "wb", compresslevel=6, mtime=0) as fo:
        shutil.copyfileobj(fi, fo, 1024 * 1024)
    sha256 = hashlib.sha256()
    with open(dst_bin, "rb") as f:
        while chunk := f.read(1024 * 1024):
            sha256.update(chunk)
    size = os.path.getsize(dst_bin)
    print(f"{dst_bin}: {size} bytes, SHA-256 {sha256.hexdigest().upper()}")
    return sha256.hexdigest().upper(), size


def main():
    if len(sys.argv) < 7:
        sys.exit(__doc__)
    dsh_version, rootfs_version, bridge_version, x86_tar, arm64_tar, repository = sys.argv[1:7]
    out_dir = sys.argv[7] if len(sys.argv) > 7 else "dist"
    os.makedirs(out_dir, exist_ok=True)

    x86_sha, x86_size = compress_tar(x86_tar, os.path.join(out_dir, "rootfs-x86_64.bin"))
    arm64_sha, _ = compress_tar(arm64_tar, os.path.join(out_dir, "rootfs-arm64-v8a.bin"))
    tag = f"rootfs-v{rootfs_version}"
    base = f"https://github.com/{repository}/releases/download/{tag}"
    manifest = {
        "rootfsName": "dsh-rootfs",
        # Три части: образ, агент, плагин. Отстаёт только плагин — его можно обновить на месте.
        "runtimeVersion": f"{rootfs_version}-dsh{dsh_version}-bridge{bridge_version}",
        "releaseTag": tag,
        "bridgeVersion": bridge_version,
        "baseDistro": "debian-13-slim",
        "libc": "glibc",
        "pageSize": "16k-compatible",
        "archiveFormat": "tar.gz",
        "bundledPackages": [
            "node-24",
            "pnpm",
            f"@deepseek-ai/dsh@{dsh_version}",
            f"dsh-mobile-bridge-plugin@{bridge_version}",
            "python3",
            "git",
        ],
        "downloadUrls": {
            "x86_64": f"{base}/rootfs-x86_64.bin",
            "arm64-v8a": f"{base}/rootfs-arm64-v8a.bin",
        },
        "downloadSha256": {"x86_64": x86_sha, "arm64-v8a": arm64_sha},
        "archiveSizeBytes": x86_size,
        "prootLibrary": "libproot.so",
        # Патчи агента уже в образе: приложение не заменяет его файлы своими копиями.
        "androidPatched": True,
    }
    path = os.path.join(out_dir, "rootfs-manifest.json")
    with open(path, "w", encoding="utf-8") as f:
        json.dump(manifest, f, indent=2, ensure_ascii=False)
        f.write("\n")
    print(f"manifest: {path}")


if __name__ == "__main__":
    main()
