#!/usr/bin/env python3
"""Host агента DSH в облачном контейнере для debug-сборки на эмуляторе без KVM.

На эмуляторе без KVM proot падает (ptrace под TCG), поэтому Host запускается здесь, в chroot
того же rootfs, а приложение подключается к нему: debug-сборка при флаге `cache/debug-external-host`
вместо proot пишет `cache/debug-external-request` и ждёт вывод Host в `cache/debug-external-stdout`
(см. DefaultHostRuntimeManager.debugExternalHostCommand). Этот скрипт:
  1. ставит флаг и ждёт запрос (порт моста приложения);
  2. копирует с эмулятора настройки агента (`root/.dsh`: патчи, профиль, токен моста);
  3. `adb forward` к мосту приложения, запускает Host, `adb reverse` к его порту;
  4. кладёт строку с адресом Host в `debug-external-stdout`.

Только песочница разработчика; нужен `adb root` (образ google_apis).
  python3 scripts/cloud/external-host.py --rootfs <распакованный rootfs> [--proxy http://127.0.0.1:PORT]
"""
import argparse
import os
import re
import subprocess
import sys
import time

PKG = os.environ.get("DSH_PKG", "app.dsh.mobile.debug")
FILES = f"/data/data/{PKG}/files"
CACHE = f"{FILES}/cache"
ADB = os.environ.get("ADB", "/home/user/android-sdk/platform-tools/adb")
URL = re.compile(r"https?://(?:127\.0\.0\.1|localhost):(\d+)/\S*token=[A-Za-z0-9_\-]+")


def adb(*args, timeout=120, check=False):
    r = subprocess.run([ADB, *args], capture_output=True, text=True, timeout=timeout)
    if check and r.returncode != 0:
        raise RuntimeError(f"adb {' '.join(args)}: {r.stderr.strip()}")
    return r.stdout


def sh(cmd, timeout=120):
    return adb("shell", cmd, timeout=timeout)


def log(msg):
    print(time.strftime("%H:%M:%S"), msg, flush=True)


def sync_settings(rootfs):
    """Настройки агента с эмулятора: патчи Node, профиль, токен моста (сессии Host остаются свои)."""
    home = os.path.join(rootfs, "root/.dsh")
    os.makedirs(os.path.join(home, "profiles/web"), exist_ok=True)
    adb("pull", f"{FILES}/rootfs/root/.dsh/patches", home, timeout=300)
    adb("pull", f"{FILES}/rootfs/root/.dsh/.bridge_token", os.path.join(home, ".bridge_token"))
    adb("pull", f"{FILES}/rootfs/root/.dsh/profiles/web/cordis.patch.yml", os.path.join(home, "profiles/web/cordis.patch.yml"))
    patch = os.path.join(home, "profiles/web/cordis.patch.yml")
    # Та же починка, что RuntimeLayout.repairProfilePatch: «[]» и за ним элементы списка — неверный YAML.
    if os.path.isfile(patch):
        text = open(patch).read()
        if re.search(r"^- ", text, re.M):
            open(patch, "w").write(re.sub(r"^\[\]\n", "", text, flags=re.M))


def ensure_mounts(rootfs):
    # dev/pts — для терминала (forkpty), без него terminal/create отвечает «forkpty(3) failed».
    for d, cmd in (("proc", ["mount", "-t", "proc", "proc"]), ("dev", ["mount", "--bind", "/dev"]), ("dev/pts", ["mount", "--bind", "/dev/pts"])):
        target = os.path.join(rootfs, d)
        os.makedirs(target, exist_ok=True)
        if subprocess.run(["mountpoint", "-q", target]).returncode != 0:
            subprocess.run(cmd + [target], check=True)
    for d in ("workspace", "root/.dsh/tmp"):
        os.makedirs(os.path.join(rootfs, d), exist_ok=True)
    ca = "/root/.ccr/ca-bundle.crt"
    if os.path.isfile(ca):
        subprocess.run(["cp", ca, os.path.join(rootfs, "etc/ccr-ca.crt")], check=True)
    subprocess.run(["cp", "/etc/resolv.conf", os.path.join(rootfs, "etc/resolv.conf")], check=False)
    # Host сам ставит прокси из HTTPS_PROXY и падает на своём undici (Dynamic require of "node:assert"):
    # прокси включает Node (NODE_USE_ENV_PROXY), а предзагрузка прячет переменные от Host.
    with open(os.path.join(rootfs, "etc/dsh-hide-proxy.cjs"), "w") as f:
        f.write("for (const k of ['HTTPS_PROXY','https_proxy','HTTP_PROXY','http_proxy']) delete process.env[k];\n")


def start_host(rootfs, bridge_port, proxy, logf):
    preload = "--require /etc/dsh-hide-proxy.cjs " + " ".join(
        f"--require /root/.dsh/patches/{n}" for n in sorted(os.listdir(os.path.join(rootfs, "root/.dsh/patches")))
        if n.endswith(".cjs")
    ) if os.path.isdir(os.path.join(rootfs, "root/.dsh/patches")) else "--require /etc/dsh-hide-proxy.cjs"
    env = [
        "HOME=/root", "DSH_HOME=/root/.dsh", "DSH_PERMISSION_MODE=danger-full-access",
        "PATH=/opt/dsh/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
        "NODE_ENV=production", "NODE_COMPILE_CACHE=/root/.dsh/node-compile-cache", "LANG=C.UTF-8",
        "TMPDIR=/root/.dsh/tmp", f"DSH_ANDROID_BRIDGE_PORT={bridge_port}", "NODE_EXTRA_CA_CERTS=/etc/ccr-ca.crt",
    ]
    if proxy:
        env += [f"HTTPS_PROXY={proxy}", "NODE_USE_ENV_PROXY=1"]
    cmd = ["chroot", rootfs, "/usr/bin/env", "-i", *env, "sh", "-c",
           f"cd /root && exec /usr/local/bin/node --expose-internals {preload} "
           "/usr/local/lib/node_modules/@deepseek-ai/dsh/lib/host.bundle.mjs --profile web --no-open --port 0"]
    return subprocess.Popen(cmd, stdout=logf, stderr=subprocess.STDOUT, start_new_session=True)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--rootfs", required=True)
    ap.add_argument("--proxy", default=os.environ.get("HTTPS_PROXY", ""))
    ap.add_argument("--log", default="/tmp/dsh-external-host.log")
    a = ap.parse_args()
    rootfs = os.path.realpath(a.rootfs)
    adb("root")
    time.sleep(3)
    uid = sh(f"stat -c %u {FILES}").strip()
    sh(f"touch {CACHE}/debug-external-host; chown {uid}:{uid} {CACHE}/debug-external-host")
    log("флаг поставлен, жду запуск Host в приложении")
    last, proc = None, None
    while True:
        req = sh(f"cat {CACHE}/debug-external-request 2>/dev/null")
        at = re.search(r"at=(\d+)", req)
        if at and at.group(1) != last:
            last = at.group(1)
            bridge = re.search(r"bridgePort=(\d+)", req).group(1)
            log(f"запрос запуска, мост приложения на {bridge}")
            if proc and proc.poll() is None:
                proc.terminate()
                proc.wait(10)
            sync_settings(rootfs)
            ensure_mounts(rootfs)
            adb("forward", f"tcp:{bridge}", f"tcp:{bridge}")
            logf = open(a.log, "w")
            proc = start_host(rootfs, bridge, a.proxy, logf)
            line = None
            for _ in range(180):
                time.sleep(1)
                m = URL.search(open(a.log).read())
                if m:
                    line = m.group(0)
                    port = m.group(1)
                    break
                if proc.poll() is not None:
                    break
            if not line:
                log("Host не напечатал адрес, см. " + a.log)
                continue
            adb("reverse", f"tcp:{port}", f"tcp:{port}")
            local = "/tmp/dsh-external-stdout"
            open(local, "w").write(f"dsh web: {line}\n")
            adb("push", local, f"{CACHE}/debug-external-stdout")
            sh(f"chown {uid}:{uid} {CACHE}/debug-external-stdout; chmod 600 {CACHE}/debug-external-stdout")
            log(f"Host готов на порту {port} (adb reverse)")
        if proc and proc.poll() is not None and last:
            log(f"Host завершился с кодом {proc.returncode}, см. {a.log}")
            proc = None
        time.sleep(3)


if __name__ == "__main__":
    sys.exit(main())
