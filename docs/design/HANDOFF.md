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
| Чат (частично), меню, лист одобрения | `feature/chat/...ChatScreen.kt`, `ApprovalSheet.kt`, `feature/sessions/...ModernNavigationDrawerContent.kt` | сделаны до правила «точь-в-точь» — ПЕРЕПРОВЕРИТЬ по прототипу и доделать |

## Осталось (по порядку)
1. **Терминал** — `#screen-terminal` (цвета `Dsh.colors.term*` уже есть). Сейчас `TerminalScreen` в app — старый.
2. **Чат** — сверить с прототипом главный экран, ход работы агента, док «Файлы/Терминал», лист «+» (вложения), лист модели, «нет сети», опасное действие (`#screen-danger`), одобрение.
3. **Меню** (`#drawer`) — сверить.
4. **Плагины** `#screen-plugins` (+ `#sheet-pladd`, `#sheet-trust` — заменит старый AlertDialog «Разрешить нативный плагин?» в `MainAppHost.kt`).
5. **Автоматизация** `#screen-automation`.
6. **Настройки** `#screen-settings` со всеми подстраницами; ключ API — только последние 4 символа, «Заменить», «Удалить».
7. Старые экраны запуска: `HostStartupSplash`, `StoragePermissionGate` (в app) — перевести в систему.
8. «Восстановить из резервной копии» (шаг 0 онбординга) и резервные копии в Настройках — логики нет, сделать или спросить владельца. Сейчас кнопка скрыта (`onRestoreBackup = null`).
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

## Грабли
- `ModalBottomSheet` в Robolectric зависает — использовать `DshSheetScaffold` или рисовать содержимое напрямую.
- `DshMotion.*()` возвращает `SpringSpec` — подходит для `slideIn/fadeIn`.
- Новые CSS-классы прототипа не должны пересекаться со старыми (при правке прототипа).
- Коммиты завершать строками:
  `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>` и `Claude-Session: <ссылка на сессию>`.
