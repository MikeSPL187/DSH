#!/usr/bin/env python3
"""Диффы Android-патчей агента для сборки образа.

Источник правды — полные файлы `app/src/main/assets/rootfs-patches/*` (ревизия, которую APK сейчас ставит
поверх агента). Исходные файлы берутся из пакетов npm ровно тех версий, что в `agent/package-lock.json`
(с проверкой integrity). Итог — `patches/android/*.patch`, которые `apply-patches.sh` накладывает в образе.

    python3 -I infra/rootfs/make-patches.py
"""
import base64, hashlib, io, json, os, subprocess, sys, tarfile, tempfile, urllib.request

ROOT = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(os.path.dirname(ROOT))
PATCHES = {
    "dsh-app-boot-index.js": ("dsh-app-boot", "lib/index.js"),
    "dsh-spill-local-index.js": ("dsh-spill-local", "lib/index.js"),
    "dsh-atomic-write-index.js": ("dsh-atomic-write", "lib/index.js"),
    "dsh-plugin-package-inventory-index.js": ("dsh-plugin-package-inventory-deepseek", "lib/index.js"),
    "dsh-subprocess-output.js": ("dsh-subprocess-local", "lib/output.js"),
    "dsh-subprocess-index.js": ("dsh-subprocess-local", "lib/index.js"),
    "dsh-subprocess-runner-launch.js": ("dsh-subprocess-local", "lib/runner-launch-B2zsQ1Dz.js"),
    "dsh-fs-local-index.js": ("dsh-fs-local", "lib/index.js"),
    "dsh-tool-bash-index.js": ("dsh-tool-bash", "lib/index.js"),
    "dsh-tool-fs-search-index.js": ("dsh-tool-fs-search", "lib/index.js"),
    "dsh-storage-json-index.js": ("dsh-storage-json", "lib/index.js"),
    "dsh-client-modules-index.js": ("dsh-client-modules", "lib/index.js"),
    "dsh-speech-sensevoice-index.js": ("dsh-experimental-speech-to-text-sensevoice", "lib/index.js"),
}
BASE = "usr/local/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai"

lock = json.load(open(os.path.join(ROOT, "agent", "package-lock.json")))["packages"]
cache = {}

def package_tar(name):
    if name in cache:
        return cache[name]
    entry = lock["node_modules/@deepseek-ai/" + name]
    data = urllib.request.urlopen(entry["resolved"]).read()
    algo, digest = entry["integrity"].split("-", 1)
    if base64.b64encode(hashlib.new(algo, data).digest()).decode() != digest:
        sys.exit(f"integrity mismatch for {name}")
    cache[name] = tarfile.open(fileobj=io.BytesIO(data))
    return cache[name]

out_dir = os.path.join(ROOT, "patches", "android")
for asset, (package, rel) in sorted(PATCHES.items()):
    original = package_tar(package).extractfile("package/" + rel).read()
    patched = open(os.path.join(REPO, "app/src/main/assets/rootfs-patches", asset), "rb").read()
    with tempfile.TemporaryDirectory() as tmp:
        a = os.path.join(tmp, "a", BASE, package, rel)
        b = os.path.join(tmp, "b", BASE, package, rel)
        os.makedirs(os.path.dirname(a)); os.makedirs(os.path.dirname(b))
        open(a, "wb").write(original); open(b, "wb").write(patched)
        diff = subprocess.run(["diff", "-u", "--label", "a/" + BASE + "/" + package + "/" + rel, "--label", "b/" + BASE + "/" + package + "/" + rel,
                               os.path.join("a", BASE, package, rel), os.path.join("b", BASE, package, rel)],
                              cwd=tmp, capture_output=True).stdout
    if not diff:
        print("no change:", asset); continue
    open(os.path.join(out_dir, asset + ".patch"), "wb").write(diff)
    print(asset + ": " + str(diff.count(b"\n")) + " lines")
