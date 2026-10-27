#!/usr/bin/env python3
"""Сверить нашу сборку агента из исходников (agent/) с пакетами, опубликованными в npm.

    python3 -I infra/agent/compare-npm.py <node_modules собранного дерева> [отчёт.json]

Для каждого пакета `@deepseek-ai/*` из infra/rootfs/agent/package-lock.json скачивается tarball (integrity
сверяется) и каждый его файл сравнивается с файлом нашей сборки. Итог: сколько файлов совпало байт в байт,
сколько отличается, чего нет. Совпадение означает: исходники в agent/ — ровно то, из чего собран агент из npm.
"""
import base64, hashlib, io, json, os, sys, tarfile, urllib.request

repo = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
built = sys.argv[1]
lock = json.load(open(os.path.join(repo, "infra/rootfs/agent/package-lock.json")))["packages"]
report = {"identical": 0, "different": [], "missing": [], "packages": 0}
for key, entry in sorted(lock.items()):
    name = key.split("node_modules/")[-1]
    if not name.startswith("@deepseek-ai/") or "resolved" not in entry:
        continue
    data = urllib.request.urlopen(entry["resolved"]).read()
    algo, digest = entry["integrity"].split("-", 1)
    if base64.b64encode(hashlib.new(algo, data).digest()).decode() != digest:
        sys.exit(f"integrity mismatch: {name}")
    report["packages"] += 1
    root = os.path.join(built, name)
    with tarfile.open(fileobj=io.BytesIO(data)) as tar:
        for member in tar.getmembers():
            if not member.isfile():
                continue
            rel = member.name.split("/", 1)[1]
            ours = os.path.join(root, rel)
            if not os.path.isfile(ours):
                report["missing"].append(f"{name}/{rel}")
                continue
            if open(ours, "rb").read() == tar.extractfile(member).read():
                report["identical"] += 1
            else:
                report["different"].append(f"{name}/{rel}")
print(f"packages {report['packages']}: identical {report['identical']}, different {len(report['different'])}, missing {len(report['missing'])}")
for kind in ("different", "missing"):
    for path in report[kind][:40]:
        print(f"  {kind}: {path}")
if len(sys.argv) > 2:
    json.dump(report, open(sys.argv[2], "w"), indent=1)
