#!/usr/bin/env python3
"""Архив bridge-плагина для телефона: zip с одной верхней папкой, как ждёт BridgePluginArchive.extract.

    python3 -I infra/bridge-plugin-zip.py <dist>

Берётся third_party/bridge-plugin; содержимое сначала сверяется с contentSha256 из APK.
Порядок записей и время фиксированы — один и тот же исходник даёт один и тот же zip.
"""
import json, os, subprocess, sys, zipfile

repo = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
source = os.path.join(repo, "third_party", "bridge-plugin")
manifest = json.load(open(os.path.join(repo, "app/src/main/assets/bridge-plugin.json")))
version, expected = manifest["version"], manifest["contentSha256"]
if json.load(open(os.path.join(source, "package.json")))["version"] != version:
    sys.exit("third_party/bridge-plugin: версия не совпадает с APK")
digest = subprocess.run([sys.executable, "-I", os.path.join(repo, "scripts/bridge-plugin-digest.py"), source],
                        capture_output=True, text=True, check=True).stdout.split()[1]
if digest != expected:
    sys.exit(f"bridge {version}: content SHA-256 {digest} != {expected} (APK)")

out_dir = sys.argv[1]
os.makedirs(out_dir, exist_ok=True)
name = f"{manifest['packageName']}-{version}"
path = os.path.join(out_dir, name + ".zip")
files = []
for dirpath, dirnames, names in os.walk(source):
    for n in names:
        full = os.path.join(dirpath, n)
        files.append((os.path.relpath(full, source).replace(os.sep, "/"), full))
with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as z:
    for rel, full in sorted(files):
        info = zipfile.ZipInfo(f"{name}/{rel}", date_time=(1980, 1, 1, 0, 0, 0))
        info.external_attr = 0o644 << 16
        info.compress_type = zipfile.ZIP_DEFLATED
        z.writestr(info, open(full, "rb").read())
print(path, digest)
