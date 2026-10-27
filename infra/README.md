# Сборка того, что приложение ставит в Linux

Всё, что телефон скачивает и исполняет с правами DSH, собирается здесь нашим CI из закреплённых
версий и принимается телефоном только с SHA-256, вшитым в APK.

| Что | Где | Workflow | Как закреплено |
|---|---|---|---|
| Образ Linux (Debian 13, Node 24, pnpm, Python 3, git) | `rootfs/Dockerfile` | `Rootfs` | базовый образ по digest; пакеты Debian — подписи apt, версии в SBOM |
| Агент `@deepseek-ai/dsh` | `rootfs/agent/` | `Rootfs` | tarball по integrity, все зависимости — `package-lock.json` (`npm ci`) |
| Android-патчи агента | `rootfs/patches/android/` | `Rootfs` | диффы из `app/src/main/assets/rootfs-patches` против пакетов lockfile (`make-patches.py`); CI проверяет, что они совпадают |
| Bridge-плагин | `app/src/main/assets/bridge-plugin.json` | `Rootfs` | `fetch-bridge.sh`: содержимое тега сверяется с `contentSha256` из APK; зависимости — `rootfs/profile/pnpm-lock.yaml` |
| pnpm | `rootfs/Dockerfile` | `Rootfs` | tarball по integrity |
| proot, talloc, libandroid-shmem | `proot/` | `Proot` | рецепты Termux закреплённого коммита (SHA-256 исходников), сборщик по digest |

## Выпуск образа

1. Actions → «Rootfs» → Run workflow, `rootfs_version` (например `2.0.0`).
2. Из релиза `rootfs-v<версия>` взять `rootfs-manifest.json` и положить в
   `app/src/main/assets/rootfs-manifest.json` (ссылки — на релизы этого репозитория, SHA-256 образов,
   `androidPatched: true`).
3. С таким манифестом приложение не заменяет файлы агента своими копиями из `assets/rootfs-patches`;
   когда устройства со старым образом обновятся, папку можно удалить из APK.

## Новая версия агента

1. `agent/package.json` — из tarball новой версии, `package-lock.json` — `npm install --package-lock-only`
   в папке с ним, `DSH_VERSION`/`DSH_INTEGRITY` в `Dockerfile` и `DSH_VERSION` в `rootfs.yml`.
2. Перенести правки `app/src/main/assets/rootfs-patches` на новые файлы и пересобрать диффы:
   `python3 -I infra/rootfs/make-patches.py`.
