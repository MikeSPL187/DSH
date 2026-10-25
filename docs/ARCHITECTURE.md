# Архитектура DSH (Android)

Клиент для агента DSH, который работает в Linux (proot) прямо на телефоне. Интерфейс —
Jetpack Compose, данные — через локальный Host (HTTP/WebSocket на 127.0.0.1) и Room.

## Слои и модули

Зависимости идут только сверху вниз. Модуль нижнего слоя не знает о модулях выше.

```
app                      сборка приложения: MainActivity, MainAppHost и связки (*Route, контроллеры)
 ├─ feature:*            экраны: chat, sessions (меню), files, settings (Плагины, Автоматизация,
 │                        Настройки), runtime (первый запуск и экраны запуска), hosts (логика сопряжения)
 ├─ plugin:*             плагины: native-host (APK-компаньоны), surface (панели Host), runtime-web (веб-островки)
 ├─ runtime:*            host — Linux (rootfs), процесс Host, git, бэкап; bridge — мост к Host
 ├─ core:data            DshRepository и области (плагины, аккаунт, задачи…), Room, настройки
 ├─ core:protocol        RemoteClient (HTTP/WS к Host) — фасад клиентов по областям, вход, соединение
 ├─ core:bridge          bridge/v1: каталог плагинов, слоты
 ├─ core:designsystem    токены «Фарфор»/«Графит», иконки, компоненты, листы, kit (каркас экранов)
 └─ core:model           модели данных, Tr (тексты ru/en без Context)
tooling:*                проверка контракта с Host и генерация — не входят в APK
```

Правила:
- **Дизайн-система одна.** Цвета — только `Dsh.colors`, шрифты — `DshFonts`, иконки — `DshGlyphs`
  из прототипа. Каркас экранов (`SBar`, группы `Gl`/`Gr`, строки `StRow`, кнопки `KBtn`, тосты,
  листы `KSheet`) — `core.designsystem.kit`. Окна — `DshDialog`/`DshHeadlessModal`: это системный
  лист снизу. Кольцо загрузки — `DshSpinner`. Material3 используется только как основа
  (`Text`, `Icon`, `ripple`, `DropdownMenu` с цветами системы), его готовые компоненты — нет.
- **Экраны не ходят в сеть и на диск сами.** Экран получает данные параметрами и отдаёт действия
  колбэками. Логика — в контроллерах/операциях приложения (см. ниже) или в `DshRepository`.
- **Без глобальных «мостов».** Зависимости экрана — параметрами или явным интерфейсом через
  CompositionLocal (`ChatServices` / `LocalChatServices`, реализация `AppChatServices`). Данные — потоками
  (`StateFlow`), а не чтением `.value` в момент отрисовки: экран обновляется сам.
  Настройки Host (`HostTheme`, `HostPermissionPreset`, `HostSessionLogUpload`, `HostSchedules`) — хранилища,
  подключаются, как только Host доступен.
- **Логика без UI тестируется на JVM.** Операции принимают интерфейсы вместо репозитория
  (пример: `WorkspaceOps` + `WorkspaceHost`), тесты — в `app/src/test`.
- **Секреты** — только в `ApiKeyVault` (Keystore), наружу — последние 4 символа; в журналах и
  отчётах — уже очищенный вывод Host.
- **Языки** — русский и английский. Строки в `values-ru` и `values`; в модулях без Context — `Tr.t(en, ru)`.

## Приложение (`app`)

`MainActivity` → `MainAppHost` — корневой Composable: собирает состояние из репозитория,
решает, какую страницу показать (`ActivePage`), и связывает экраны с логикой.

Вынесено из `MainAppHost` (раньше 3200 строк в одной функции, теперь ≈1600 — навигация и связки; метод упирался в лимит
размера ART и падал с VerifyError):

| Файл | Что делает |
|---|---|
| `StartupGate.kt` | ворота запуска: Linux, доступ к папке, ожидание Host, загрузка данных; экраны запуска |
| `AppDrawer.kt` | меню: сессии проекта, поиск, листы «Проект» и действий с сессией |
| `ChatRoute.kt`, `ChatModelSelection.kt` | страница чата: данные открытой сессии из репозитория, действия, выбор модели и уровня рассуждений |
| `ChatSender.kt` | отправка сообщения: команды Host, новая сессия с первым сообщением, вложения |
| `HostAccount.kt` | вход в аккаунт DeepSeek через Host и подписка на его состояние |
| `VoiceInput.kt` | голосовой ввод: разрешение, запись, распознавание |
| `NativePluginsController.kt` | APK-компаньоны: поиск, доверие, загрузка, очередь удаления |
| `ToolPolicyEffect.kt` | режимы инструментов («Сразу / Спросить / Выкл.») и журнал вызовов |
| `WorkspaceOps.kt`, `WorkspaceDialogs.kt` | операции с папками проектов (только внутри корня) и окна |
| `PluginPanesSheet.kt`, `JobsPanel.kt`, `AgentTeamPluginUi.kt` | листы панелей плагинов, фоновых задач, команды |
| `ai/DeepSeekQuick.kt`, `ai/CommitMessageWriter.kt` | короткие запросы приложения к DeepSeek («Объяснить», сообщение коммита) |
| `terminal/*`, `TerminalScreen.kt` | терминал: поток PTY → блоки команд, вкладки, «Объяснить» |
| `ModelProviders.kt` | провайдеры моделей: список, сохранение ключа и полей, удаление ключа (Настройки и первый запуск) |
| `AppChatServices.kt` | возможности Host для чата: оценки, картинки, workflow, траектория, ветвление, выгрузка |
| `Dsh*Route.kt` | связки экранов «Плагины», «Автоматизация», «Настройки» с логикой |

## Протокол (`core:protocol`)

`DshClientProtocol` собран из интерфейсов по областям Host-API: `SessionsApi`, `AccountApi`,
`PluginsApi`, `SettingsApi`, `TeamApi`, `JobsApi`, `TerminalApi`, `ChangesApi`, `HostEventsApi`,
`HostRpcApi`. Каждую реализует свой `internal`-клиент (`SessionsClient`, `PluginsClient`…) поверх
общего `HostConnection` (адрес, OkHttp, JSON, `rpcRaw`). `RemoteClient` — только фасад: делегирует
интерфейсы клиентам (`by`) и меняет адрес Host. Новый вызов Host добавляется в интерфейс своей
области и в её клиент — остальные файлы не трогаются.

## Данные (`core:data`)

`DshRepository` ведёт сессии, сообщения, рабочие папки и открытую сессию и разводит события
Host по областям. Области — отдельные классы, доступные свойствами репозитория:

| Свойство | Класс | Что делает |
|---|---|---|
| `plugins` | `PluginsRepository` | инвентарь, мост `bridge/v1`, слоты, настройки и секреты плагинов, магазин, установка |
| `account` | `AccountRepository` | аккаунт DeepSeek: состояние, профиль, баланс, бонусы |
| `tasks` | `TasksRepository` | команда агента, задачи, расписания, фоновые процессы |
| `terminals` | `TerminalRepository` | терминалы сессии |
| `commands` | `CommandsRepository` | слэш-команды и навыки |
| `approvals` | `ApprovalsRepository` | одобрения и вопросы агента, режимы инструментов |
| `hostSettings` | `HostSettingsRepository` | документы настроек (`namespace`, `setSettings`), провайдеры моделей и их ключи, режимы доступа, каталог моделей, язык Host |
| `speech`, `translation` | `SpeechRepository`, `TranslationRepository` | распознавание речи, перевод рассуждений |

Общие вызовы и разбор ответа `{result:{ok,value}}` — `HostCalls`; разбор проекций Host
(`workspace/follow`, `todos`, `goal`, `inbox`) — чистые функции в `HostProjections.kt` с тестами.
Сырых RPC-строк в приложении нет: экраны вызывают только типизированные методы областей.
Области тестируются на JVM с подставным Host (`FakeHost` в тестах `core:data`).

## Что дальше (по пользе)

1. **Удалённый Host**: клиент работает с одним адресом. Чтобы подключать компьютер, нужно
   хранить хосты (токен — в Keystore), переключать `RemoteClient` на адрес хоста с пиннингом TLS
   (`PairingTls`), затем включить `SettingsUi.remoteHostsSupported`.
2. **Тесты UI-логики** в `feature:chat`, `feature:runtime` и `core:designsystem` — сейчас там
   нет модульных тестов, проверка идёт снимками в `app` (`DshScreensSweepTest`, `ScreensGalleryTest`).
   Для областей данных тесты есть (`core:data`); осталось покрыть `PluginsRepository` и `TasksRepository`.
