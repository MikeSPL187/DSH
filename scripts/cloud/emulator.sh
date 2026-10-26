#!/usr/bin/env bash
# Эмулятор dsh26 без KVM: запуск, установка debug-сборки, запуск приложения, проверка на падение.
#   bash scripts/cloud/emulator.sh smoke   # загрузить, поставить APK начисто, открыть, искать FATAL
#   bash scripts/cloud/emulator.sh stop
# Перед `gradle.sh test` эмулятор остановить: вместе им не хватает памяти (Gradle падает с кодом 137).
# Linux (proot) на этом эмуляторе не запускается — Host, чат с агентом и git проверяются только тестами.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
SDK="${ANDROID_HOME:-/home/user/android-sdk}"
ADB="$SDK/platform-tools/adb"
PKG=com.dsh.mobile.debug
OUT="${OUT_DIR:-$ROOT/build/cloud-emulator}"
mkdir -p "$OUT"
case "${1:-smoke}" in
  stop) "$ADB" emu kill >/dev/null 2>&1 || true ;;
  smoke)
    if ! "$ADB" devices | grep -q emulator; then
      (setsid nohup "$SDK/emulator/emulator" -avd dsh26 -no-window -no-audio -no-boot-anim -accel off \
        -cores 4 -gpu swiftshader_indirect -no-snapshot-save > "$OUT/emulator.log" 2>&1 &)
    fi
    echo "жду загрузку (холодная — до 15 минут)…"
    until [ "$("$ADB" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ]; do sleep 10; done
    sleep 30
    "$ADB" install -r "$ROOT/app/build/outputs/apk/debug/app-debug.apk" | tail -1
    "$ADB" shell pm clear "$PKG" >/dev/null
    "$ADB" logcat -c || true
    "$ADB" shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
    sleep 90
    echo "pid=$("$ADB" shell pidof "$PKG" | tr -d '\r')"
    "$ADB" logcat -d | grep -E "FATAL EXCEPTION|AndroidRuntime: (java|kotlin|Process)" -A8 | head -40 || echo "падений нет"
    "$ADB" shell screencap -p /sdcard/s.png && "$ADB" pull /sdcard/s.png "$OUT/screen.png" >/dev/null
    echo "снимок: $OUT/screen.png (окно «System UI isn't responding» — от медленного эмулятора, не от DSH)"
    ;;
esac
