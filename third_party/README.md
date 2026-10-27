# Сторонний код в репозитории

Копии, из которых собираются APK и образ Linux, — чтобы сборка не ходила в чужие репозитории.
Менять — только заменой целиком на новый закреплённый источник и с просмотром диффа.

| Папка | Источник | Версия | Лицензия | Как проверяется |
|---|---|---|---|---|
| `bridge-plugin/` | https://github.com/EricFL1998/deepseek-harness-mobile-bridge-plugin | тег `v2.0.7`, коммит `3802e4aaefe365025a561b416ce64f778bb35cd8` | MIT (`bridge-plugin/LICENSE`) | содержимое = `contentSha256` в `app/src/main/assets/bridge-plugin.json` (тест `RootfsManifestContractTest`, `infra/rootfs/prepare-bridge.sh`) |
| `plugin-sdk/sdk/` | https://github.com/EricFL1998/dsh-mobile-plugin-sdk | коммит `e2fe80a6c403f86770e10628f9d832fd849685e4`, папка `sdk/` | в исходном репозитории файла лицензии нет (как и у исходного приложения) — SDK и раньше компилировался внутрь APK | составная сборка Gradle (`settings.gradle.kts`) |
| `proot/` | proot, talloc, libandroid-shmem | `NOTICE.md` | GPL-2.0 / LGPL-3.0 / Apache-2.0 | сборка CI `infra/proot`, релиз `proot-v…` с исходниками |

Пакет SDK `com.deepseek.dsh.plugin.nativesdk`, действие `com.deepseek.dsh.action.PLUGIN_UI` и разрешение
`com.deepseek.dsh.permission.BIND_PLUGIN` — контракт с уже собранными нативными плагинами; их имена не менять (G-49).

## Агент

`agent/` — исходники агента DSH: https://github.com/deepseek-ai/deepseek-harness, тег `dsh-v0.2.1-rc.2`
(коммит `687f282e8625c6b8d11ff58211a90524be2e4317`), MIT (`agent/LICENSE`, сторонние — `agent/THIRD_PARTY_NOTICES.md`).
Без `docs/`, `snapshots/`, `.agents/`, `.github/` (документация и данные проверок upstream, в сборку не входят).
Собирается нашим CI (workflow «Agent» и стадия `agent-build` образа `infra/rootfs`), зависимости — `agent/pnpm-lock.yaml`.
Наши изменения — коммитами в `agent/`; поведение под Android включается только при `DSH_ANDROID=1`
(fs-local, storage-json, atomic-write, spill-local, subprocess-local, tool-fs-search, tool-bash, app-boot,
client-modules, speech-to-text-sensevoice).
