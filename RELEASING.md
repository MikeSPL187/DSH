# Выпуск

APK закрепляет всё, что потом скачивает телефон: образ Linux (`app/src/main/assets/rootfs-manifest.json`,
SHA-256 архивов) и bridge-плагин (`app/src/main/assets/bridge-plugin.json`, `contentSha256` содержимого; исходник — `third_party/bridge-plugin`).
Ничего «последнего» во время работы приложение не берёт: новая версия образа или плагина приходит только
с новым APK. Как собираются образ и proot — `infra/README.md`.

Порядок:

```text
(плагин: новая версия и contentSha256)  ->  образ (workflow «Rootfs»)  ->  APK (тег app-v…)
```

## Версии

| Файл | Поле | Что это |
|---|---|---|
| `app/build.gradle.kts` | `versionName` / `versionCode` | версия APK; тег `app-v1.0.3` |
| `app/src/main/assets/bridge-plugin.json` | `version`, `contentSha256` | плагин в образе и единственная версия, которую приложение поставит |
| `app/src/main/assets/rootfs-manifest.json` | `runtimeVersion`, `releaseTag`, `downloadSha256` | образ; адреса — релизы этого репозитория |

## Новая версия bridge-плагина

1. Заменить `third_party/bridge-plugin` содержимым нового тега (`git archive v<версия> | tar -x`), просмотреть дифф.
2. В `bridge-plugin.json` — `version` и `contentSha256`: `python3 -I scripts/bridge-plugin-digest.py third_party/bridge-plugin`.
3. Actions → «Bridge plugin» → Run workflow с `release`: архив для обновления на телефоне (`bridge-plugin-v<версия>`).
4. Пересобрать образ (ниже): `bridgeVersion` образа должен совпасть.

## Новый образ

1. Actions → «Rootfs» → Run workflow, `rootfs_version` (например `2.0.1`).
2. `rootfs-manifest.json` из релиза `rootfs-v<версия>` — в `app/src/main/assets/rootfs-manifest.json`.

## APK

1. Поднять `versionName` / `versionCode`, закоммитить, поставить тег `app-v<версия>` и отправить.
2. Workflow «Build Android APK» на теге проверяет, что образ из манифеста существует и его SHA-256
   совпадают с APK, а архив плагина есть в релизе `bridge-plugin-v<версия>`, а копия `third_party/bridge-plugin` совпадает с `contentSha256`
   (`.github/scripts/verify-release-coupling.py`, `infra/rootfs/prepare-bridge.sh`); затем тесты,
   подписанная сборка (секреты `ANDROID_KEYSTORE_*` добавляет владелец) и релиз с APK.
