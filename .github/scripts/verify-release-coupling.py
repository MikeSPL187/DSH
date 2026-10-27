#!/usr/bin/env python3
"""Проверка перед выпуском APK: всё, что APK скачивает, существует и совпадает с хэшами в APK.

APK закрепляет две внешние вещи (порядок выпуска — RELEASING.md, сборка — infra/README.md):

  - `app/src/main/assets/rootfs-manifest.json`: релиз образа `releaseTag` должен существовать,
    а SHA-256 его архивов (GitHub считает их сам, поле `digest` ресурса) — совпадать с `downloadSha256`;
  - `app/src/main/assets/bridge-plugin.json`: версия плагина та же, что в образе (`bridgeVersion`), и в релизе
    `bridge-plugin-v<версия>` есть архив, который приложение скачает для обновления; копию
    `third_party/bridge-plugin` сверяет с `contentSha256` `infra/rootfs/prepare-bridge.sh` (отдельный шаг workflow).

Ничего не запускает в чужих репозиториях и не смотрит на «последние релизы»: обновления приходят только
с новым APK.
"""

from __future__ import annotations

import json
import re
import sys
import urllib.error
import urllib.request

API = "https://api.github.com"


def fail(message: str) -> None:
    print(f"::error::{message}", file=sys.stderr)
    raise SystemExit(1)


def load_json(path: str) -> dict:
    with open(path, encoding="utf-8") as stream:
        return json.load(stream)


def release(repo: str, tag: str) -> dict:
    req = urllib.request.Request(
        f"{API}/repos/{repo}/releases/tags/{tag}",
        headers={"Accept": "application/vnd.github+json", "User-Agent": "dsh-release-guard"},
    )
    try:
        with urllib.request.urlopen(req, timeout=30) as response:
            return json.load(response)
    except urllib.error.HTTPError as error:
        fail(f"релиз {repo}@{tag} недоступен: HTTP {error.code}")


def main() -> None:
    bridge = load_json("app/src/main/assets/bridge-plugin.json")
    rootfs = load_json("app/src/main/assets/rootfs-manifest.json")

    version = str(bridge.get("version", "")).strip()
    if not re.fullmatch(r"\d+\.\d+\.\d+", version):
        fail(f"version в bridge-plugin.json — не semver: {version!r}")
    if str(rootfs.get("bridgeVersion", "")).strip() != version:
        fail(f"плагин {version} в APK, а в образе — {rootfs.get('bridgeVersion')!r}: сначала пересобрать образ")
    if not re.fullmatch(r"[0-9a-f]{64}", str(bridge.get("contentSha256", ""))):
        fail("в bridge-plugin.json нет contentSha256")

    tag = str(rootfs.get("releaseTag", "")).strip()
    urls = rootfs.get("downloadUrls", {})
    hashes = rootfs.get("downloadSha256", {})
    if not tag or not urls:
        fail("в rootfs-manifest.json нет releaseTag или downloadUrls")
    for abi, url in urls.items():
        match = re.fullmatch(r"https://github\.com/([^/]+/[^/]+)/releases/download/([^/]+)/([^/]+)", url)
        if not match or match.group(2) != tag:
            fail(f"{abi}: адрес не из релиза {tag}: {url}")
        assets = {a["name"]: a for a in release(match.group(1), tag).get("assets", [])}
        asset = assets.get(match.group(3))
        if asset is None:
            fail(f"{abi}: в релизе {tag} нет {match.group(3)}")
        digest = str(asset.get("digest", "")).removeprefix("sha256:").lower()
        if digest != str(hashes.get(abi, "")).lower():
            fail(f"{abi}: SHA-256 архива {digest} не совпадает с APK {hashes.get(abi)}")
        print(f"{abi}: {match.group(1)}@{tag}/{match.group(3)} — SHA-256 совпадает")

    bridge_repo = re.fullmatch(r"https://github\.com/([^/]+/[^/]+)", str(bridge.get("repository", "")).rstrip("/"))
    if not bridge_repo:
        fail(f"repository в bridge-plugin.json — не репозиторий GitHub: {bridge.get('repository')!r}")
    archive = f"{bridge.get('packageName')}-{version}.zip"
    if archive not in {a["name"] for a in release(bridge_repo.group(1), f"bridge-plugin-v{version}").get("assets", [])}:
        fail(f"в релизе bridge-plugin-v{version} нет {archive}")

    print(f"образ {tag} и плагин {version} совпадают с APK")


if __name__ == "__main__":
    main()
