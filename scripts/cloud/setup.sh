#!/usr/bin/env bash
# Подготовка облачного контейнера Claude Code к сборке, тестам и эмулятору DSH.
# Только для песочницы: на рабочей машине разработчика не нужен.
#
#   bash scripts/cloud/setup.sh            # SDK, SDK плагинов, зеркало Maven
#   bash scripts/cloud/setup.sh --emulator # плюс эмулятор и AVD dsh26 (Android 8, ~1,5 ГБ)
#
# Повторный запуск безопасен: готовое пропускается.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
SDK="${ANDROID_HOME:-/home/user/android-sdk}"
DL="https://dl.google.com/android/repository"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

say() { printf '\n== %s\n' "$*"; }
fetch() { curl -sSfL --retry 4 --retry-delay 2 -o "$2" "$1"; }

say "Android SDK: $SDK"
mkdir -p "$SDK"
if [ ! -x "$SDK/cmdline-tools/latest/bin/sdkmanager" ]; then
  fetch "$DL/commandlinetools-linux-11076708_latest.zip" "$TMP/clt.zip"
  mkdir -p "$SDK/cmdline-tools"
  unzip -q "$TMP/clt.zip" -d "$SDK/cmdline-tools"
  mv "$SDK/cmdline-tools/cmdline-tools" "$SDK/cmdline-tools/latest"
fi
if [ ! -f "$SDK/platforms/android-35/android.jar" ] || [ ! -d "$SDK/build-tools/35.0.0" ] || [ ! -x "$SDK/platform-tools/adb" ]; then
  # sdkmanager через прокси иногда падает — тогда пакеты ставятся напрямую архивами.
  if ! yes | "$SDK/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$SDK" \
      "platforms;android-35" "build-tools;35.0.0" "platform-tools" >/dev/null; then
    echo "sdkmanager не справился, ставлю архивы напрямую"
    if [ ! -f "$SDK/platforms/android-35/android.jar" ]; then
      fetch "$DL/platform-35_r02.zip" "$TMP/p.zip"; mkdir -p "$SDK/platforms"
      unzip -q "$TMP/p.zip" -d "$SDK/platforms"; mv "$SDK"/platforms/android-[!3]* "$SDK/platforms/android-35" 2>/dev/null || true
    fi
    if [ ! -d "$SDK/build-tools/35.0.0" ]; then
      fetch "$DL/build-tools_r35_linux.zip" "$TMP/b.zip"; mkdir -p "$SDK/build-tools"
      unzip -q "$TMP/b.zip" -d "$SDK/build-tools"; mv "$SDK"/build-tools/android-* "$SDK/build-tools/35.0.0" 2>/dev/null || true
    fi
    if [ ! -x "$SDK/platform-tools/adb" ]; then
      fetch "$DL/platform-tools-latest-linux.zip" "$TMP/pt.zip"; unzip -q "$TMP/pt.zip" -d "$SDK"
    fi
  fi
fi
mkdir -p "$SDK/licenses"
printf '\n24333f8a63b6825ea9c5514f83c2829b004d1fee\n' > "$SDK/licenses/android-sdk-license"


say "Зеркало Maven Central для Gradle и Robolectric"
mkdir -p "$HOME/.gradle/init.d"
cat > "$HOME/.gradle/init.d/dsh-maven-mirror.gradle" <<'G'
// Контейнер: repo.maven.apache.org через прокси отвечает 404/429 через раз; берём официальное зеркало Maven Central от Google.
def fix = { RepositoryHandler repos ->
    repos.withType(MavenArtifactRepository).configureEach { r ->
        def u = r.url.toString()
        if (u.startsWith("https://repo.maven.apache.org/") || u.startsWith("https://repo1.maven.org/")) r.url = "https://maven-central.storage-download.googleapis.com/maven2/"
    }
}
settingsEvaluated { s -> fix(s.pluginManagement.repositories); fix(s.dependencyResolutionManagement.repositories); s.gradle.beforeProject { fix(it.buildscript.repositories); fix(it.repositories) } }
// Robolectric сам скачивает android-all во время тестов — туда же.
allprojects { tasks.withType(Test).configureEach { systemProperty 'robolectric.dependency.repo.url', 'https://maven-central.storage-download.googleapis.com/maven2/' } }
G

if [ "${1:-}" = "--emulator" ]; then
  say "Эмулятор и образ Android 8 (API 26, без KVM — только -accel off)"
  if [ ! -x "$SDK/emulator/emulator" ]; then
    fetch "$DL/emulator-linux_x64-16632760.zip" "$TMP/e.zip"; unzip -q "$TMP/e.zip" -d "$SDK"
  fi
  IMG="$SDK/system-images/android-26/google_apis/x86_64"
  if [ ! -f "$IMG/system.img" ]; then
    fetch "$DL/sys-img/google_apis/x86_64-26_r16.zip" "$TMP/s.zip"; mkdir -p "$IMG"
    unzip -q "$TMP/s.zip" -d "$TMP/s"; cp -r "$TMP"/s/*/. "$IMG/"
  fi
  mkdir -p "$HOME/.android/avd/dsh26.avd"
  cat > "$HOME/.android/avd/dsh26.ini" <<A
avd.ini.encoding=UTF-8
path=$HOME/.android/avd/dsh26.avd
path.rel=avd/dsh26.avd
target=android-26
A
  cat > "$HOME/.android/avd/dsh26.avd/config.ini" <<'A'
AvdId=dsh26
PlayStore.enabled=false
abi.type=x86_64
avd.ini.encoding=UTF-8
hw.cpu.arch=x86_64
hw.cpu.ncore=4
hw.lcd.width=1080
hw.lcd.height=2340
hw.lcd.density=420
hw.ramSize=3072
hw.keyboard=yes
hw.gpu.enabled=yes
hw.gpu.mode=swiftshader_indirect
disk.dataPartition.size=6G
image.sysdir.1=system-images/android-26/google_apis/x86_64/
tag.id=google_apis
tag.display=Google APIs
skin.dynamic=yes
A
fi

say "Готово. Сборка и тесты: bash scripts/cloud/gradle.sh test   (подробнее — docs/security/NEXT_AUDIT.md)"
