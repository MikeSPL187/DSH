Linux 2.0.2 для кандидата DSH Mobile 1.1.4 (114): Debian 13, Node 24.22.1, Host 0.2.1-rc.2 и bridge 2.0.7.

- curl включён в образ.
- Все 303 production-пакета агента собраны из исходников; контроль 5708 файлов и SBOM исключают подмену зависимостями из npm.
- Узкий glibc shim переводит creat/creat64 в эквивалентный openat для совместимости с Android seccomp. Исходник и checksum библиотеки находятся в SBOM.
- Доставлены исправления Android subprocess cancellation и согласия на потенциально разрушительные workspace-команды.

Полное исправление dpkg требует APK 1.1.4: он включает эмуляцию hardlink в PRoot и защищает публикацию файлов Node от перемещения временного источника.

Проверки: [образы x86_64/ARM64](https://github.com/MikeSPL187/DSH/actions/runs/42145348410), [20 нативных проверок на Android 11/13](https://github.com/MikeSPL187/DSH/actions/runs/42149123847), [подписанная сборка APK](https://github.com/MikeSPL187/DSH/actions/runs/42149123906).

Архивы опубликованы без пересборки из проверенного CI artifact 13470646003, source 1cc61d5778d8124f13931a2f050851944e3cbfd9. SHA-256 совпадают с закреплённым манифестом APK 1.1.4. Релиз предварительный; исправления приложения находятся в [draft PR №2](https://github.com/MikeSPL187/DSH/pull/2).
