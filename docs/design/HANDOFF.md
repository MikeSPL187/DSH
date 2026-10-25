# Передача работы: перенос прототипа в приложение

Ветка: `claude/mobile-app-security-audit-8aocub`. Сначала прочитать `CLAUDE.md` в корне — там все правила.

## Главное правило
Каждый экран делается ТОЧЬ-В-ТОЧЬ как в `docs/design/dsh-prototype.html`. Старый дизайн экрана
удаляется полностью (компоненты, строки, картинки). Логика приложения подключается к экранам
прототипа; выдумывать данные нельзя (если правды нет — честная формулировка, сказать владельцу).

## Готово
| Экран | Где | Примечание |
|---|---|---|
| Дизайн-система | `core/designsystem`: `DshTokens` (цвета = прототип, + `term*`), `DshComponents`, `DshGlyphs` (иконки из `<symbol>`, генератор `docs/design/tools/glyphs.py`), `DshSheet` (лист `.sheet`) | `DshSwitch`/`DshButton` по размерам прототипа (`big = true` — 52 dp) |
| Первый запуск | `feature/runtime/.../OnboardingScreen.kt`, связка `app/.../FirstRunOnboarding.kt` | ключ проверяется у провайдера, папка — настоящий выбор, шаг 3 — реальная установка rootfs и запуск Host, ждёт готовности (выбор владельца) |
| Переустановка Linux | `LinuxSetupScreen` там же | заменил старый `RootfsSetupScreen` |
| Файлы | `feature/files/.../FilesScreen.kt`, `FilesModel.kt`; git: `runtime/host/.../WorkspaceGit.kt`, адаптер `app/.../WorkspaceFilesGit.kt` | всё по `#screen-files`; «агент» в подписях не пишем — не знаем, кто менял |
| Плагины, Автоматизация, Настройки | `feature/settings/.../ui/` (`DshScreenKit.kt` — общий каркас экранов: шапка, группы, листы, тосты), связки `app/.../DshPluginsRoute.kt`, `AutomationRunner.kt`, `DshSettingsRoute.kt` | ключ API в Keystore (`ApiKeyVault`), видны только 4 последних символа; автоматизации — своя логика на JobScheduler (`AutomationStore`); вход по отпечатку — `BiometricGate.kt`. Невозможное (нет логики у Host) скрыто по решению владельца |
| Терминал | `app/.../TerminalScreen.kt` (экран), `app/.../terminal/TermEngine.kt` (поток PTY → блоки), `TerminalRoute.kt` (связка с Host, вкладки, история), `TermExplainer.kt` («Объяснить» через DeepSeek) | блоки с кодом и временем — по меткам bash: при открытии вкладки приложение тихо ставит `PROMPT_COMMAND`, печатающий `OSC 133;D;код;папка;HOME` (решение владельца). Без bash — блоки без кода. «Объяснить» — ответ модели прямо в блоке (решение владельца), строка `FIX:` → кнопка. Полноэкранные программы (vim, top) показываются без раскладки экрана. Тест на записи настоящего bash: `TermEngineTest` |
| Чат (частично), меню, лист одобрения | `feature/chat/...ChatScreen.kt`, `ApprovalSheet.kt`, `feature/sessions/...ModernNavigationDrawerContent.kt` | сделаны до правила «точь-в-точь» — ПЕРЕПРОВЕРИТЬ по прототипу и доделать |

## Осталось (по порядку)
1. ~~Терминал~~ — готово.
2. **Чат** — сверить с прототипом главный экран, ход работы агента, док «Файлы/Терминал», лист «+» (вложения), лист модели, «нет сети», опасное действие (`#screen-danger`), одобрение.
3. **Меню** (`#drawer`) — сверить.
4–6. ~~Плагины, Автоматизация, Настройки~~ — готово (влито из `claude/redesign-settings`).
   Хвосты: `LocalCodeFontSize` (размер кода из Настроек) пока читает только терминал; `ui.pendingComposerText` (подстановка в черновик из Автоматизации/Плагинов) чат ещё не забирает — подключить при переносе Чата.
7. Старые экраны запуска: `HostStartupSplash`, `StoragePermissionGate` (в app) — перевести в систему.
8. «Восстановить из резервной копии» (шаг 0 онбординга) — логика уже есть (`RuntimeBackupAndDiagnostics.restoreUserDataBackup`, используется в Настройках), осталось подключить к онбордингу (сейчас `onRestoreBackup = null`).
9. Действия «Проверить с агентом» / «Сообщение коммита» / «Спросить» сейчас подставляют текст в черновик чата (через `runtimeUi.voiceTranscript`), а не отправляют сразу — как в прототипе можно отправлять сразу.

## Сборка и проверка
```bash
S=/tmp/<scratchpad>   # любой каталог для логов
ANDROID_HOME=/opt/android-sdk bash ./gradlew --offline -q --no-daemon -Dorg.gradle.workers.max=2 \
  -PdshSdkRepo=$S/sdk-repo -ProbolectricDepsDir=/opt/robolectric-deps testDebugUnitTest :app:assembleDebug
```
- Долгие сборки — в фоне с файлом-маркером, ждать циклом короче 600 с.
- Снимки экранов: `app/src/test/.../ui/ScreensGalleryTest.kt` (Robolectric) → `app/build/ui-screens/*.png`.
  На каждый экран: светлая/тёмная, 360/390/412 dp (`@Config(qualifiers = "ru-w360dp-h740dp-xxhdpi")`),
  все состояния; сценарий проходить нажатиями (`hasText(...)`, `performClick`, `performTextInput`).
- Снимки прототипа той же ширины — Playwright (Node): открыть `docs/design/dsh-prototype.html`,
  `document.querySelector('[data-go="files"]').click()` и т. п.; пример полного обхода — `docs/design/tools/sweep.js`.
  Склеивать «прототип | приложение» попарно и смотреть глазами.
- Эмулятор: `dsh26` (Android 8) без KVM — работает; `dsh30` не держит system_server.
  `emulator -avd dsh26 -no-window -no-audio -no-boot-anim -accel off -cores 4 -gpu swiftshader_indirect -no-snapshot-save`,
  пакет `com.dsh.mobile.debug`. Скачать rootfs в песочнице нельзя (TLS прокси) — это ограничение среды.

- Сборка в облачном контейнере без `/opt/android-sdk`: SDK в `/home/user/android-sdk`, проверка зависимостей
  только локально `--dependency-verification lenient` (не коммитить изменения verification-metadata),
  Maven Central через зеркало `maven-central.storage-download.googleapis.com` в `~/.gradle/init.d/`.
- Эмулятор в облачном контейнере ставится вручную (нет `sdkmanager`): `emulator-linux_x64-16428233.zip` и
  `sys-img/google_apis/x86-26_r16.zip` с dl.google.com в `/home/user/android-sdk`, AVD `dsh26` — руками
  (`~/.android/avd/dsh26.ini` + `config.ini`); запускать через `setsid nohup`, холодная загрузка ~4 мин.
- Экран без Linux на эмуляторе (только debug-сборка): `app/src/debug/.../ScreenPreviewActivity.kt`,
  `adb shell am start -n com.dsh.mobile.debug/com.deepseek.dsh.debug.ScreenPreviewActivity --ez dark true --es state main`
  (`state`: main / empty / connecting). Вводить текст лучше `input keyevent` по одной клавише:
  `input text` на эмуляторе без KVM теряет символы.
- Моноширинный текст в приложении ~2 % уже прототипа: Android округляет ширину символа до целого пикселя (хинтинг).
- Снимки терминала прототипа: Playwright, кадры main/top/explain/gradle/busy/stopped/ctrl/font/empty
  при высоте телефона (360×740, 390×844, 412×915).
- Обход экранов Настроек/Плагинов/Автоматизации/Терминала: `app/src/test/.../ui/DshScreensSweepTest.kt` → `app/build/ui-sweep/`.

## Грабли
- Android обрезает пробелы в начале/конце строковых ресурсов — такие строки брать в кавычки.
- `org.json` в чистых JVM-тестах не работает — такие тесты делать в Robolectric (app).
- `ModalBottomSheet` в Robolectric зависает — использовать `DshSheetScaffold` или рисовать содержимое напрямую.
- `DshMotion.*()` возвращает `SpringSpec` — подходит для `slideIn/fadeIn`.
- Новые CSS-классы прототипа не должны пересекаться со старыми (при правке прототипа).
- Коммиты завершать строками:
  `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>` и `Claude-Session: <ссылка на сессию>`.
