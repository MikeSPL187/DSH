#!/usr/bin/env python3
"""SHA-256 содержимого bridge-плагина для `contentSha256` в app/src/main/assets/bridge-plugin.json.

    python3 -I scripts/bridge-plugin-digest.py third_party/bridge-plugin   # папка с исходником
    python3 -I scripts/bridge-plugin-digest.py p.tar                       # tar с одной верхней папкой

Тот же алгоритм, что BridgePluginArchive.contentDigest: строки "путь\\0sha256\\n" обычных файлов
(путь от корня пакета), по возрастанию пути в байтах UTF-8.
"""
import hashlib
import os
import sys
import tarfile

source = sys.argv[1]
rows = []
if os.path.isdir(source):
    for dirpath, dirnames, files in os.walk(source):
        dirnames.sort()
        for name in files:
            path = os.path.join(dirpath, name)
            if os.path.islink(path) or not os.path.isfile(path):
                continue
            rel = os.path.relpath(path, source).replace(os.sep, "/")
            with open(path, "rb") as f:
                rows.append((rel.encode(), hashlib.sha256(f.read()).hexdigest()))
else:
    with tarfile.open(source) as tar:
        for member in tar.getmembers():
            if not member.isfile():
                continue
            name = member.name.split('/', 1)[1] if '/' in member.name else member.name
            rows.append((name.encode(), hashlib.sha256(tar.extractfile(member).read()).hexdigest()))
rows.sort()
digest = hashlib.sha256()
for path, file_hash in rows:
    digest.update(path + b"\0" + file_hash.encode() + b"\n")
print(len(rows), digest.hexdigest())
