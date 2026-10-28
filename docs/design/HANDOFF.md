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
| Чат | `feature/chat/...`: `ChatKit.kt` (шапка, главный экран, док, поле ввода, «нет сети»), `ChatThread.kt` (ход работы агента, шаги, инструменты, лог), `ChatApproval.kt` (одобрение `.approve`, `.ask`, `.decide`, `#screen-danger`), `ChatSheets.kt` (листы модели, «+», доступа, риска, вопрос агента), `ChangedFiles.kt`, `PresentedFiles.kt`, `QueuedMessages.kt`, `WorkflowRuns.kt`, `Trajectory.kt`, `MarkdownText.kt`; связка в `MainAppHost` (`chatApproval`, `ApprovalInspect.kt` — размеры и свободное место для удаления) | функции приложения, которых нет в прототипе (пресеты, слэш-команды, план, цель/задачи, очередь, результаты, workflow, голос), — в стиле системы (решение владельца); имя в приветствии — из профиля, «шаг N из M» — из todos. Пока одобрение ждёт на другом экране — плашка сверху |
| Меню | `feature/sessions/.../DshDrawer.kt` (`DshPushDrawer` — чат уезжает вправо и уменьшается, `DshDrawerContent`, листы `ProjectSheetBody` и `SessionSheetBody`) | подпись строки — первая строка последнего ответа агента (`DshRepository.sessionPreviews`), «Ждёт вашего решения» — по ожидающим одобрениям, «Агент работает» — по идущим сессиям. Нажатие на проект и долгое нажатие на сессию открывают листы в стиле системы (в прототипе их нет): выбор/добавление/переименование/порядок/удаление проекта; переименовать, закрепить, продолжить в новой сессии, архив. Свайп от левого края открывает |

## Осталось (по порядку)
1. ~~Терминал~~ — готово.
2. ~~Чат~~ — готово (решения владельца: функции приложения, которых нет в прототипе — пресеты, слэш-команды и навыки,
   режим плана, очередь, задачи/цель, выданные файлы, workflow, голос, полоса Claude Code — оставить, но в стиле
   системы (листы «+», модели, карточки хода работы); имя в приветствии — из профиля аккаунта, «шаг N из M» — из todos) — сверить с прототипом главный экран, ход работы агента, док «Файлы/Терминал», лист «+» (вложения), лист модели, «нет сети», опасное действие (`#screen-danger`), одобрение.
3. ~~Меню~~ — готово.
4–6. ~~Плагины, Автоматизация, Настройки~~ — готово (влито из `claude/redesign-settings`).
   Хвосты закрыты: `LocalCodeFontSize` читают терминал и блоки кода в чате; `ui.pendingComposerText` попадает в черновик чата.
7. ~~Экраны запуска~~ — готово: `HostStartScreen` (знак DSH и состояние; при ошибке — причина, последний вывод с копированием отчёта, «Повторить», «Открыть без Host») и `StorageAccessScreen` (выбор папки, как шаг 2) в `feature/runtime/.../OnboardingScreen.kt`. В прототипе их нет — собраны из частей первого запуска.
8. ~~«Восстановить из резервной копии» в первом запуске~~ — готово: выбор файла → `restoreUserDataBackup` (Host на время останавливается, если работал), тост с числом файлов и напоминанием, что ключ API нужно ввести заново.
9. ~~Действия на экране «Изменения»~~ — готово, как в прототипе: «Проверить с агентом» открывает новую сессию и сразу отправляет запрос (`ui.pendingAutoSend`); «Сообщение коммита» — приложение само пишет его по diff через DeepSeek (`ai/CommitMessageWriter`) и копирует, тост «Скопировано: «…»»; «Спросить» о файле — вопрос в черновике и файл во вложениях. Короткие запросы приложения к DeepSeek — общий `ai/DeepSeekQuick` (им же пользуется «Объяснить» в терминале).
10. ~~Ссылки «@» и цель сессии~~ — готово (паритет с официальным DSH: `ui-reference`, `ui-goal`). «@» в конце черновика
   открывает над строкой ввода список файлов/папок и сессий (`MentionPanel.kt`; данные — `ContextRepository`:
   `fileReferences/list`, `sessionReferenceResolver/candidates`; в новом чате, пока у Host нет сессии, — файлы рабочей
   папки на устройстве, `localFileCandidates`, без выхода за папку). Ссылка — обычный текст (`@путь`, `@"путь с пробелами"`,
   `@[название](dsh-session:…)`), разворачивает её Host; в поле и в пузыре сессия показывается как «@название»
   (`MentionTransformation`). Цель — в `PlanDock`: пауза/продолжить, изменить, готово, убрать вторым касанием (`goals/*`
   с `{id, revision}` из проекции).
11. ~~Проверка плана~~ — готово (`ui-plan`): вопрос `exit_plan_mode` с `intent.kind = plan-review` открывает экран
   `PlanReviewScreen` (`ChatPlanReview.kt`, связка `planReviewOf`/`planReviewAnswer` в `UserQuestionDialog.kt`):
   документ плана, «Выполнить план» (метка `intent.approve`), «Доработать» — замечания свободным ответом.
   «Назад» не отвечает Host: `ui.hiddenPlanReview`, в ленте карточка «Проверить».
12. ~~Субагенты~~ — готово (`ui-subagent`): проекция `subagentCatalog` → «Агенты N» в доке и лист `SubagentsSheetBody`
   (`ChatSubagents.kt`); строка открывает разговор субагента, родителя помнит `ui.subagentView` (в проекции `subagent`
   его нет): плашка «К сессии», сообщения — `subagents/prompt`, «Стоп» — `subagents/interruptByParent`; разовый —
   только просмотр, внешний не открывается.
13. ~~Превью документов и браузер~~ — готово (`ui-sidebar-documentpreview`, `ui-sidebar-browser`). Файлы: «Вид | Код» для
   Markdown (рендерер чата приходит параметром `markdown`, `feature/files` от чата не зависит), «Страница | Код» для
   HTML (`HtmlStaticPreview`: `WebView` без JS, сети и файлов), PDF — `PdfDoc` (`PdfRenderer` под замком, страницы
   по требованию, ширина ≤ 1440 px, сведение пальцев 1–4×), `DocPreviews.kt`. Браузер — `BrowserScreen.kt` поверх
   страницы (`ui.browser`): только http(s) по политике `BrowserUrls` (= `network_security_config`: открытый HTTP —
   лишь localhost), Safe Browsing, без мостов в Kotlin и `file://`; HTML рабочей папки — с виртуального
   `https://dsh.workspace/`, файлы отдаются только изнутри неё. Адреса в выводе терминала — ссылки (`LocalOpenUrl`).
14. ~~Пределы агента~~ — готово (`ui-settings-shell`, `ui-settings-agent-loop`, конфиг `dsh-subagent`). «Агент» →
   «Пределы агента»: время команды, вывод в памяти, глубина и число субагентов, параллельные вызовы. Пространство
   настроек ищется по полям (`AgentLimits.kt`), а не по id записи профиля; `value`/`base`/`user` из
   `settings/describe`, запись `settings/mutate` с ревизией, «Сбросить» и сохранение стандартного — `unset`.
   Время команды не выше `maxTimeoutMs` Host. Шаги степпера — `AgentLimitSteps` (значение из `cordis.yml` вне сетки
   сохраняется). Пометка «изменено» — `StRow(badge = …)`.
15. ~~Веб-поиск~~ — готово (паритет с `ui-settings-web-search` и больше). «Агент» → «Веб-поиск»: переключатель
   Search API меняет поставщиков поиска и чтения страниц разом (`web.searchProvider/fetchProvider`, совпадение со
   стандартным — `unset`), предел выбранного способа (`count` или `maxUses`), «Передавать ваш вопрос»
   (`sendOriginalQuestion` — в официальном интерфейсе его нет) и ключ: тот же `DEEPSEEK_API_KEY`, только «••••» и
   последние 4 символа, переход на «Модели и ключи». Переключатели откликаются сразу и откатываются, если Host отказал.
16. ~~Контекст и расход~~ — готово (паритет с `ContextMeter`/`TurnUsagePanel` и больше). Кольцо и процент — в подзаголовке
   шапки (в доке и ленте чипов места на 360 px нет), заголовок — кнопка листа «Контекст и расход»: заполненность из
   `contextPressure` (окно модели от Host вместо прежних зашитых 64 000), полоса состава `contextBreakdown` (доли, «≈»),
   предупреждение от 80 % и 95 %, расход `tokenUsage`, реальная трата за сегодня по журналу баланса
   (`BalanceLedger.peek`, без сети) и «Сжать сейчас» — `/compact` мимо поля ввода (`ChatSender.compactNow`).
   Цен Host не отдаёт — стоимость сессии не выдумываем. Заодно чипы над полем — 40 dp с затуханием края ленты.
17. ~~Инструкции для агента~~ — готово (аналог `/memory` Claude Code). «Агент» → «Инструкции для агента»: три файла,
   которые читает `dsh-agent-instructions` (`~/.dsh/AGENTS.md`, `AGENTS.md`/`CLAUDE.md` проекта, `AGENTS.local.md`),
   с весом и бюджетом 64 КБ. Страница-редактор: «Начать с шаблона»/«Пустой файл», «Правка | Вид» (Markdown чата),
   «Сохранить» (атомарно, `AgentInstructions.write`), «Удалить файл» вторым нажатием, «Выйти без сохранения?» и на
   шапке, и на системном «Назад». Уже существующий `CLAUDE.md` правится на месте.
18. ~~Коммит из «Изменений»~~ — готово (шаг 1 пункта «Git до конца»). «Закоммитить» → лист: сообщение (ИИ пишет по
   изменениям), сводка, автор (первый раз — имя и почта в `git config --global`, потом «От имени … · Изменить»).
   `WorkspaceGit.commit`: `add -A` и `commit --no-verify -m` с выключенными хуками и подписью; после коммита пустые
   «Изменения» показывают «N коммитов не отправлены» (`rev-list HEAD --not --remotes`). Отправка (push) и запрос на
   слияние — следующий шаг: нужен токен GitHub по правилам секретов.
19. ~~Вход в GitHub и отправка~~ — готово. «Настройки → GitHub»: вход по одноразовому коду (OAuth device flow,
   `GitHubAuth`, общий лист `DeviceSignInSheetBody`), токен — в Android Keystore (`GitHubAccount`, ссылка `github`),
   не показывается никогда; «Выйти» удаляет его. «Изменения»: «Отправить в origin/<ветка>»; без входа — тот же лист,
   после входа отправка идёт сама. Отправка — из процесса приложения (JGit 5.13, `GitHubPush`), а не git в Linux:
   иначе токен виден процессам агента через `/proc`. Токен уходит только на `https://github.com/<o>/<r>`; конфиг
   с `url`/`http`/`credential`/`include`/`hooksPath` — отказ; хуки не запускаются; пользовательский и системный
   конфиги JGit пустые (`GitHubPush.isolate`). **Нужно владельцу:** OAuth App на github.com (Developer settings →
   OAuth Apps, включить Device Flow), его client id — в переменную репозитория `DSH_GITHUB_CLIENT_ID` (или
   `-PdshGithubClientId` локально). Запрос на слияние — следующий шаг.

## Архитектура
Слои, модули, правила и план — в `docs/ARCHITECTURE.md`. Кратко: `MainAppHost` разобран на части
(`StartupGate`, `AppDrawer`, `ChatSender`, `HostAccount`, `VoiceInput`, `NativePluginsController`,
`ToolPolicyEffect`, `WorkspaceOps`/`WorkspaceDialogs`), каркас экранов перенесён в
`core.designsystem.kit`, `DshTopBar` = `SBar`. Страница чата — `ChatRoute` (данные сессии читает сама,
выбор модели — `ChatModelSelection`). Протокол разделён на клиентов по областям Host-API (`RemoteClient` —
фасад), `DshRepository` — на области (`repository.plugins`, `.account`, `.tasks`, `.terminals`, `.commands`,
`.approvals`, `.hostSettings`, `.speech`, `.translation`). Комментарии в коде — на русском.
Папка проекта по умолчанию на диске — `Default` (решение владельца); старая `默认工作区` переносится при запуске
вместе с папкой её сессий в Host (`DefaultWorkspaceMigration`). Проверка как в CI: `./gradlew test` (снимочные тесты экранов — только в debug-варианте).

## Окна и панели вне прототипа
- Все окна приложения (`DshDialog`, `DshHeadlessModal`, `DshSheetPanel`) рисуются одним системным листом
  `DshModalSheet` (core/designsystem/DshPanels.kt): затемнение, карточка снизу r34, «ручка», пружина, смахивание.
  Кнопки `DshDialogButton` в ряду делят ширину поровну (высота 48, r14). Поле — `DshTextInput` (s2, r16, 52).
- «Фоновые задачи» и «Команда агента» из шапки чата — листы. Правая панель чата удалена (дублировала док);
  панели нативных плагинов (`RIGHT_PANEL*`) — лист `PluginPanesSheet`, кнопка в шапке только если они есть.
- Удалённый Host: клиент не переключает адрес и токен на удалённый Host, поэтому раздел в Настройках скрыт
  (`SettingsUi.remoteHostsSupported = false`), страница «Хосты» и старый диалог удалены; логика сопряжения
  (`feature/hosts/RemotePairingManager`, тесты) осталась. Чтобы включить: хранить хосты (токен — в Keystore),
  переключать `RemoteClient` на адрес хоста с пиннингом TLS, затем поднять флаг.
- Мёртвые страницы «Среда выполнения» (RUNTIME) и «Хосты» (HOSTS) удалены.

## Языки
Только русский и английский (решение владельца): `values-zh` удалены, `Tr.t(en, ru)`, выбор языка — «Русский / English».
Сохранённый ранее `zh` читается как «Как в системе». Host понимает только zh/en — ему всегда отправляется `en`.
Таблицы строк (`genxml.py`) пишут `values-ru` и `values`.

## Сборка и проверка
Облачный контейнер с нуля: `bash scripts/cloud/setup.sh [--emulator]`, сборка — `scripts/cloud/gradle.sh`,
эмулятор — `scripts/cloud/emulator.sh smoke`. Продолжение аудита и промт для новой сессии — `docs/security/NEXT_AUDIT.md`.
```bash
S=/tmp/<scratchpad>   # любой каталог для логов
ANDROID_HOME=/opt/android-sdk bash ./gradlew --offline -q --no-daemon -Dorg.gradle.workers.max=2 \
  testDebugUnitTest :app:assembleDebug   # SDK плагинов — third_party/plugin-sdk
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
  пакет `app.dsh.mobile.debug`. Скачать rootfs в песочнице нельзя (TLS прокси) — это ограничение среды.

- Сборка в облачном контейнере без `/opt/android-sdk`: SDK в `/home/user/android-sdk`, проверка зависимостей
  только локально `--dependency-verification lenient` (не коммитить изменения verification-metadata),
  Maven Central через зеркало `maven-central.storage-download.googleapis.com` в `~/.gradle/init.d/`.
- Эмулятор в облачном контейнере ставится вручную (нет `sdkmanager`): `emulator-linux_x64-16428233.zip` и
  `sys-img/google_apis/x86-26_r16.zip` с dl.google.com в `/home/user/android-sdk`, AVD `dsh26` — руками
  (`~/.android/avd/dsh26.ini` + `config.ini`); запускать через `setsid nohup`, холодная загрузка ~4 мин.
- Экран без Linux на эмуляторе (только debug-сборка): `app/src/debug/.../ScreenPreviewActivity.kt`,
  `adb shell am start -n app.dsh.mobile.debug/com.dsh.debug.ScreenPreviewActivity --ez dark true --es state main`
  (`state`: терминал — main / empty / connecting; чат — chat-home / chat-approve / chat-danger, меню — chat-drawer, в чате кнопка меню открывает его). Вводить текст лучше `input keyevent` по одной клавише:
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
- Android 8: одна неудачная попытка `PdfRenderer` ломает его в процессе для всех следующих PDF
  (issuetracker 118866570) — поэтому `PdfDoc.looksLikePdf` отсеивает битые и обрезанные файлы заранее.
  Файл с целыми заголовком и концовкой, но испорченной серединой, всё ещё может сломать PDF до перезапуска.
- Инструментальные тесты библиотек: `androidx.test.ext:junit` не содержит `AndroidJUnitRunner` — нужен
  `androidx.test:runner`, иначе `am instrument` пишет «Process crashed».
- Новые CSS-классы прототипа не должны пересекаться со старыми (при правке прототипа).
- Строки ресурсов — только в модуле, где они используются (`nonTransitiveRClass=true`, копии в других модулях
  не нужны); у каждой строки `values/` есть пара в `values-ru/`.
- Lint (`./gradlew lintDebug`) — 0 ошибок: API выше minSdk 26 (например, `LocalDate.ofInstant` — Android 14)
  без проверки версии не вызывать; разрешения, которые нужны модулю, объявлять в его манифесте.
- Безопасность (повторный аудит, `docs/SECURITY_FIXES_REPORT.ru.md` §4а): строки для `sh -c` — только через
  `shellCommand` (всё в кавычках); zip из недоверенного источника — только `SafeZip`; текст наружу (журнал, отчёт) —
  через `SecretScrubber`; путь от агента — через `workspaceFileOf`; ссылки — `openWebLink`; сбой сети
  в вызове, который меняет права или включает/выключает плагин, — ошибка, а не успех;
  git — только с `WorkspaceGit.HARDENING`; ключ своего API проверяется только на зафиксированном адресе.
- Устойчивость (третий круг, `docs/SECURITY_FIXES_REPORT.ru.md` §4б): поток Host (`callbackFlow` поверх WebSocket)
  собирать только через `keepSubscribed` (core:data) или с `.catch` — обрыв кончается исключением, а области
  приложения и `LaunchedEffect` без обработчика роняют процесс; JSON от Host — только `json.parseHostJson`
  (kotlinx переполняет стек на глубоких массивах, а `StackOverflowError` не ловится `catch (e: Exception)`);
  в модулях без `core:protocol` — `Json.parseBoundedJson` / `jsonTooDeep` из `core:model` (и перед org.json тоже, G-51);
  рекурсивный разбор деревьев от Host и плагинов — с пределом глубины; любой буфер из вывода терминала или
  потока — с пределом размера; уведомления — только со своим тегом или id из своего диапазона (не пересекаться
  с `HostService.NOTIFICATION_ID` и `DshNotifications`); состояние «идёт» в хранилище после смерти процесса — не правда,
  сверять с памятью процесса; текст наружу к модели (DeepSeek) — через `SecretScrubber`; ввод в терминал — через `TermPaste`
  (многострочная вставка — только после подтверждения); уведомления от плагинов — с пометкой «Плагин:».
- Всё, что попадает в Linux на телефоне (образ, агент, плагин, pnpm, proot), — только из `infra/` нашим CI,
  по digest/integrity/lockfile и с SHA-256 в APK; никаких `npm install -g` «последней» версии, `pnpm add` git-тега
  и правок файлов агента на телефоне. Правка агента — в исходниках `agent/` (образ собирает агента из них);
  порядок — `infra/README.md`.
- Сеть Host — только через `AgentNetworkProxy` (переменные `*_PROXY` в `agentProxyEnv`); новый нужный агенту домен —
  в `AgentNetworkPolicy.BASE_HOSTS` или пусть его объявит плагин; секреты (пароль прокси) в журналы не писать.
- Четвёртый круг (`docs/SECURITY_FIXES_REPORT.ru.md` §4в): действие, которое меняет состояние Host (отправка, «Стоп»,
  ответ на вопрос, архив, переименование), ждёт ответа и смотрит `result.ok` — HTTP 200 ещё не согласие; локальное
  состояние («идёт», пузырь, кэш) меняется только после согласия, при отказе — видимая ошибка и повтор (`ChatMessage.failed`
  → «Не отправлено · Повторить»). Аргументы инструментов — только JSON-парсером (`parseHostJson`), никаких регулярных
  выражений по JSON. Текст файла или вывода, который рисуется одним `Text`, — с пределом длины строки и общего объёма
  (`readViewText`), подсветка — не в composition.
- Цепочка поставки (G-41, G-42): всё, что приложение скачивает и исполняет в Linux, закреплено хэшем в APK —
  образ (`rootfs-manifest.json`: https + `downloadSha256`), bridge-плагин (`bridge-plugin.json`: `version` +
  `contentSha256`, считать `scripts/bridge-plugin-digest.py` по `git archive` тега). Никаких «последних релизов»
  во время работы: новая версия — новый APK. Обновления APK и ссылки — только `MikeSPL187/DSH` (`DSH_GITHUB_REPO`).
- Данные пользователя (G-05, G-09, G-10, G-12): резервная копия — подпись `BackupSigner` (Keystore) и восстановление в два
  шага `prepareRestore` → окно `RestoreConfirmDialog` → `applyRestore` (откат при сбое); журнал Host — только через
  `HostLog` (очистка секретов, предел, ротация); очистка — только известных папок через `SafeFiles`; необратимое удаление
  проекта — с вводом имени, «Default» и занятый агентом проект не удаляются. Host запускать и останавливать только через
  `DefaultHostRuntimeManager` (замок `HostLifecycleLock`), не вызывать `stop()` изнутри запуска — взаимоблокировка.
- Блокировка (G-07): новый экран — внутри `LockedContent` в `MainActivity`, ничего не рисовать поверх замка; листы доверия
  и подобные запросы не всплывают поверх чата — метка в меню (G-06).
- Фон и простои (G-13, G-39): «занят» Host — ход агента, живая команда терминала, фоновый процесс, ожидающее одобрение
  или вопрос; Host не прогревать из `Application` (процесс поднимается и ради JobScheduler). Сторонние плагины из сети —
  только при включённом `BridgePolicy.isThirdPartyPluginsAllowed` (G-48). Новые ссылки в тексте — `LinkAnnotation`
  только для http(s) с видимым доменом. В KDoc не писать `/*` (например, `build/*.log`) — это вложенный комментарий Kotlin.
- Вход по отпечатку: `BiometricLock.available()` — единственная проверка (отпечаток или код телефона);
  без способа входа блокировка не включается, иначе из DSH не выйти.
- Агент (`agent/`, наш форк): поведение под Android правится только в исходниках TS и включается проверкой
  `process.env.DSH_ANDROID === '1'` (переменную ставит `DefaultHostRuntimeManager`), без ветки upstream остаётся как было.
  Патчей-диффов к собранному JS больше нет и не добавлять. Значения от модели передавать отдельными
  элементами argv, без оболочки (G-52).
- Коммиты завершать строками:
  `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>` и `Claude-Session: <ссылка на сессию>`.
