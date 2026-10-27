#!/usr/bin/env python3
"""SHA-256 содержимого bridge-плагина для `contentSha256` в app/src/main/assets/bridge-plugin.json.

    git -C <клон плагина> archive --format=tar --prefix=p/ v2.0.7 > p.tar
    python3 scripts/bridge-plugin-digest.py p.tar

Тот же алгоритм, что BridgePluginArchive.contentDigest: строки "путь\\0sha256\\n" обычных файлов
(без верхней папки архива), по возрастанию пути в байтах UTF-8.
"""
import hashlib
import sys
import tarfile

rows = []
with tarfile.open(sys.argv[1]) as tar:
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
