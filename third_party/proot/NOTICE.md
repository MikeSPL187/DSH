# Сторонний код: proot для Android (страницы 16 КБ)

Библиотеки в `app/src/main/jniLibs/{arm64-v8a,x86_64}/`:

| Файл | Проект | Лицензия | Исходники |
| --- | --- | --- | --- |
| `libproot.so` | proot (форк Termux) | GPL-2.0-or-later | https://github.com/termux/proot |
| `libproot-loader.so` | proot, `libexec/proot/loader` | GPL-2.0-or-later | https://github.com/termux/proot |
| `libtalloc.so` | talloc (Samba) | LGPL-3.0-or-later | https://www.samba.org/ftp/talloc/ |
| `libandroid-shmem.so` | libandroid-shmem | Apache-2.0 | https://github.com/termux/libandroid-shmem |

## Сборка

Собираются из исходников нашим CI — `.github/workflows/proot.yml`: рецепты `termux-packages`
закреплённого коммита (`infra/proot/termux.lock`; в каждом рецепте — SHA-256 архива исходников), образ
сборщика Termux по digest, зависимости тоже из исходников. `infra/proot/package.sh` приводит имена к
правилу APK (Android распаковывает только `lib*.so`):

- `DT_NEEDED libtalloc.so.2` у proot → `libtalloc.so`, `SONAME` talloc → `libtalloc.so`;
- `RUNPATH` на `/data/data/com.termux/...` убран;
- проверка: каждый сегмент `LOAD` выровнен на 16 КБ.

В приложении: `PROOT_LOADER` и `LD_LIBRARY_PATH=<nativeLibraryDir>`.

Текущие файлы в `jniLibs` — пакеты Termux proot 5.1.107.98, talloc 2.5.0, libandroid-shmem 0.7 с той же
правкой `DT_NEEDED`. Замена на сборку CI: запустить workflow «Proot» с `release`, сверить
`jniLibs.SHA256SUMS` релиза и закоммитить библиотеки из `jniLibs.tar.gz`.

## Исходники (GPL-2.0, LGPL-3.0)

Релиз `proot-v<версия>` этого репозитория содержит рядом с бинарниками архивы исходников всех трёх
проектов и рецепты сборки (`termux-recipes.tar.gz`) — это и есть предложение исходников для
распространяемого APK.
