#!/usr/bin/env python3
"""Real APK/native-image QA. Disposable devices only; no provider credentials."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shlex
import signal
import struct
import subprocess
import threading
import time
import xml.etree.ElementTree as ET

PACKAGE = 'app.dsh.mobile.debug'


class Device:
    def __init__(self, output):
        self.output, self.serial, self.checks = output, 0, {}

    def adb(self, *args, timeout=60, stdin=None, check=True):
        return subprocess.run([str(Path(os.environ['ANDROID_HOME']) / 'platform-tools/adb'), '-s', 'emulator-5554', *args], stdin=stdin,
                              capture_output=True, timeout=timeout, check=check).stdout

    def shell(self, command, **kwargs):
        return self.adb('shell', command, **kwargs).decode()

    def private(self, command, **kwargs):
        return self.shell('run-as ' + PACKAGE + ' sh -c ' + shlex.quote(command), **kwargs)

    def dump(self):
        self.serial += 1
        self.adb('shell', 'uiautomator', 'dump', '--compressed', '/sdcard/dsh-qa.xml')
        raw = self.adb('exec-out', 'cat', '/sdcard/dsh-qa.xml').decode()
        assert 'qa-round4-fake-key' not in raw, 'Raw fixture exposed in accessibility'
        (self.output / ('ui-%03d.xml' % self.serial)).write_text(raw)
        return list(ET.fromstring(raw).iter('node'))

    @staticmethod
    def label(node):
        return node.get('text') or node.get('content-desc') or ''

    def screenshot(self, name):
        (self.output / (name + '.png')).write_bytes(self.adb('exec-out', 'screencap', '-p'))

    def dark_pixels(self):
        raw = self.adb('exec-out', 'screencap')
        width, height, fmt = struct.unpack('<III', raw[:12])
        assert fmt in (1, 2), 'Unsupported screenshot pixel format'
        header = len(raw) - width * height * 4
        assert header in (12, 16), 'Unexpected screenshot stride/header'
        points = [(4, height // 2), (width // 2, 2), (width // 3, height - 3)]
        return all(sum(raw[header + (y * width + x) * 4:header + (y * width + x) * 4 + 3]) < 384 for x, y in points)

    def tap(self, node):
        x1, y1, x2, y2 = map(int, re.findall(r'-?\d+', node.get('bounds')))
        self.adb('shell', 'input', 'tap', str((x1 + x2) // 2), str((y1 + y2) // 2))

    def wait(self, predicate, seconds=60):
        deadline, last = time.monotonic() + seconds, []
        while time.monotonic() < deadline:
            try:
                last = self.dump()
            except (subprocess.CalledProcessError, ET.ParseError):
                time.sleep(1)
                continue
            if any('permissioncontroller' in n.get('package', '') for n in last) and any('notifications' in self.label(n).lower() for n in last):
                allow = next((n for n in last if self.label(n) == 'Allow'), None)
                if allow is not None:
                    self.tap(allow)
                    continue
            if predicate(last):
                return last
            failures = [self.label(n) for n in last if 'responding' in self.label(n)]
            assert not failures, 'ANR during native QA: ' + repr(failures)
            time.sleep(1)
        raise AssertionError('UI timeout: ' + repr([self.label(n) for n in last if self.label(n)]))

    def has(self, label, nodes):
        return any(self.label(n) == label for n in nodes)

    def click(self, label, scroll=False):
        for direction in ([1] if not scroll else [1, -1]):
            for _ in range(1 if not scroll else 8):
                nodes = self.dump()
                found = [n for n in nodes if self.label(n) == label]
                if found:
                    self.tap(next((n for n in found if n.get('clickable') == 'true'), found[0]))
                    return
                if scroll:
                    y1, y2 = (1150, 350) if direction == 1 else (350, 1150)
                    self.adb('shell', 'input', 'swipe', '360', str(y1), '360', str(y2), '300')
        raise AssertionError('Action unavailable: ' + label)

    def check(self, name):
        self.checks[name] = 'PASS'
        (self.output / 'checks.json').write_text(json.dumps(self.checks, indent=2) + '\n')
        print(name + ': PASS', flush=True)

    def upload(self, source, target):
        self.private('mkdir -p ' + shlex.quote(str(Path(target).parent)))
        with source.open('rb') as data:
            self.private('cat > ' + shlex.quote(target), stdin=data, timeout=300)

    def foreground(self):
        text = self.shell('dumpsys activity activities')
        return any(re.search(r'\b(?:mResumedActivity|topResumedActivity)\s*[:=]', line) and PACKAGE + '/com.dsh.MainActivity' in line for line in text.splitlines())

    def production(self, image):
        self.upload(image, 'files/cache/rootfs-local.tar.gz')
        digest = self.private('sha256sum files/cache/rootfs-local.tar.gz').split()[0]
        assert digest == hashlib.sha256(image.read_bytes()).hexdigest()
        self.adb('shell', 'am', 'start', '-n', PACKAGE + '/com.dsh.MainActivity')
        self.wait(lambda n: self.has('Get started', n))
        self.click('Get started')
        self.wait(lambda n: self.has('Skip — I’ll connect later', n))
        self.click('Skip — I’ll connect later')
        self.wait(lambda n: self.has('Where to keep projects', n))
        self.click('Continue')
        ready = self.wait(lambda n: self.has('Start working', n) or self.has('Retry', n), seconds=240)
        if self.has('Retry', ready):
            if self.has('Details', ready):
                self.click('Details')
                self.dump()
            raise AssertionError('Linux installation or Host startup failed')
        self.click('Start working')
        self.check('rootfs-install-and-host-start')
        self.wait(lambda n: any(re.search(r'menu|меню|drawer', self.label(x), re.I) for x in n))
        nodes = self.dump()
        self.tap(next(x for x in nodes if re.search(r'menu|меню|drawer', self.label(x), re.I)))
        self.wait(lambda n: self.has('Settings', n))
        self.click('Settings')
        self.click('Appearance', scroll=True)
        self.wait(lambda n: self.has('Graphite · Dark', n))
        self.click('Graphite · Dark')
        for _ in range(20):
            raw = self.private('cat shared_prefs/dsh_theme.xml', check=False)
            try:
                value = ET.fromstring(raw).find("string[@name='preference']")
                if value is not None and value.text == 'dark':
                    break
            except ET.ParseError:
                pass
            time.sleep(.2)
        else:
            raise AssertionError('Theme selection was not persisted')
        for _ in range(10):
            if self.dark_pixels():
                break
            time.sleep(.5)
        else:
            raise AssertionError('Graphite or system bars did not render dark')
        self.screenshot('graphite')
        self.check('theme-and-system-bars-apply-locally')
        for cycle in range(2):
            self.wait(lambda n: self.has('Русский', n))
            self.click('Русский')
            self.wait(lambda n: self.has('Оформление', n))
            assert self.foreground(), 'MainActivity lost foreground after Russian locale'
            self.screenshot('appearance-ru-' + str(cycle))
            self.wait(lambda n: self.has('English', n))
            self.click('English')
            self.wait(lambda n: self.has('Appearance', n) and self.has('Graphite · Dark', n))
            assert self.foreground(), 'MainActivity lost foreground after English locale'
        self.check('appearance-retained-through-two-locale-cycles')
        self.shell('settings put system accelerometer_rotation 0')
        self.shell('settings put system user_rotation 1')

        def landscape(nodes):
            bounds = list(map(int, re.findall(r'\d+', nodes[0].get('bounds'))))
            return bounds[2] - bounds[0] > bounds[3] - bounds[1]

        self.wait(lambda n: self.has('Appearance', n) and landscape(n))
        assert self.foreground(), 'MainActivity lost foreground after rotation'
        self.screenshot('appearance-landscape')
        self.shell('settings put system user_rotation 0')
        self.wait(lambda n: self.has('Appearance', n) and not landscape(n))
        self.check('appearance-landscape-stays-in-app')
        self.adb('shell', 'input', 'keyevent', '4')
        self.wait(lambda n: self.has('Settings', n))
        self.click('Plugins', scroll=True)
        self.wait(lambda n: any(self.label(x).startswith('All') for x in n) and any(self.label(x).startswith('Native') for x in n))
        self.adb('shell', 'input', 'keyevent', '4')
        self.wait(lambda n: self.has('Settings', n))
        self.check('plugins-back-returns-to-settings')
        self.click('Host', scroll=True)
        self.click('Open terminal', scroll=True)
        self.wait(lambda n: self.has('Terminal', n) or self.has('Enable terminal', n))
        nodes = self.wait(lambda n: any(x.get('class') == 'android.widget.EditText' and x.get('enabled') == 'true' for x in n))
        self.screenshot('terminal')
        self.check('host-open-terminal-opens-shell')
        self.upload(Path('infra/qa/native-packages.sh'), 'files/rootfs/root/dsh-native-packages.sh')
        self.upload(Path('infra/qa/native-cancel.mjs'), 'files/rootfs/root/dsh-native-cancel.mjs')
        self.tap(next(x for x in nodes if x.get('class') == 'android.widget.EditText' and x.get('enabled') == 'true'))
        self.shell('input text ' + shlex.quote('sh%s/root/dsh-native-packages.sh'))
        self.adb('shell', 'input', 'keyevent', '66')
        deadline = time.monotonic() + 60
        while time.monotonic() < deadline:
            result = self.private('cat files/rootfs/root/dsh-native-packages.result', check=False)
            if 'PASS' in result:
                break
            time.sleep(1)
        else:
            raise AssertionError('Terminal package/cancellation probe did not complete')
        self.screenshot('terminal-packages')
        self.check('native-dpkg-install-purge-and-curl')
        cancelled = json.loads(self.private('cat files/rootfs/root/dsh-native-cancel.result'))
        assert cancelled == {'portFreed': True, 'unrelatedListenerPreserved': True}
        self.check('native-android-cancel-frees-server-and-preserves-other-listener')


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--api', type=int, required=True)
    parser.add_argument('--rootfs', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    image = args.rootfs.resolve()
    manifest = json.loads(Path('app/src/main/assets/rootfs-manifest.json').read_text())
    assert hashlib.sha256(image.read_bytes()).hexdigest().upper() == manifest['downloadSha256']['x86_64']
    sdk = Path(os.environ['ANDROID_HOME'])
    tools = sdk / 'cmdline-tools/latest/bin'
    package = 'system-images;android-%d;google_apis;x86_64' % args.api
    subprocess.run([str(tools / 'sdkmanager'), '--licenses'], input=b'y\n' * 100, check=True)
    subprocess.run([str(tools / 'sdkmanager'), 'emulator', 'platform-tools', package], check=True)
    name = 'dsh-qa-' + str(args.api)
    avds = Path(os.environ.get('RUNNER_TEMP', '/tmp')) / 'dsh-device-avds'
    avds.mkdir(parents=True, exist_ok=True)
    device_env = dict(os.environ, ANDROID_AVD_HOME=str(avds))
    avd_path = avds / (name + '.avd')
    subprocess.run([str(tools / 'avdmanager'), 'create', 'avd', '-n', name, '-k', package,
                    '--path', str(avd_path), '--force'], input=b'no\n', env=device_env, check=True)
    config = avd_path / 'config.ini'
    overrides = {'hw.lcd.width': '720', 'hw.lcd.height': '1440', 'hw.lcd.density': '320',
                 'hw.ramSize': '3072', 'hw.cpu.ncore': '3', 'hw.keyboard': 'yes',
                 'disk.dataPartition.size': '4096M'}
    lines = [line for line in config.read_text().splitlines() if line.split('=', 1)[0].strip() not in overrides]
    config.write_text('\n'.join(lines + [key + '=' + value for key, value in overrides.items()]) + '\n')
    subprocess.run([str(sdk / 'emulator/emulator'), '-accel-check'], check=True)
    log = (args.output / 'emulator.log').open('wb')
    emulator = subprocess.Popen([str(sdk / 'emulator/emulator'), '-avd', name, '-accel', 'on', '-no-window', '-no-audio', '-no-boot-anim',
                                 '-no-snapshot', '-gpu', 'swiftshader_indirect', '-memory', '3072', '-cores', '3', '-partition-size', '4096'],
                                stdout=log, stderr=subprocess.STDOUT, start_new_session=True, env=device_env)
    stop = threading.Event()

    def watchdog():
        while not stop.wait(2):
            available = next(int(line.split()[1]) for line in Path('/proc/meminfo').read_text().splitlines() if line.startswith('MemAvailable:')) * 1024
            if available < 1024**3:
                (args.output / 'memory-watchdog.txt').write_text('Stopped emulator: MemAvailable < 1 GiB')
                try:
                    os.killpg(emulator.pid, signal.SIGTERM)
                except ProcessLookupError:
                    pass
                return

    threading.Thread(target=watchdog, daemon=True).start()
    device = Device(args.output)
    try:
        deadline = time.monotonic() + 300
        while time.monotonic() < deadline:
            if emulator.poll() is not None:
                raise RuntimeError('Emulator exited during boot')
            try:
                if device.shell('getprop sys.boot_completed', timeout=10, check=False).strip() == '1':
                    break
            except subprocess.TimeoutExpired:
                pass
            time.sleep(2)
        else:
            raise RuntimeError('Emulator boot timeout')
        for setting in ['window_animation_scale', 'transition_animation_scale', 'animator_duration_scale']:
            device.shell('settings put global ' + setting + ' 0')
        device.shell('settings put secure show_ime_with_hard_keyboard 1')
        device.adb('install', '-r', 'app/build/outputs/apk/debug/app-debug.apk', timeout=120)
        device.adb('install', '-r', 'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk', timeout=120)
        result = device.shell('am instrument -w -r -e class com.dsh.ReplaceKeyImeTest ' + PACKAGE + '.test/androidx.test.runner.AndroidJUnitRunner', timeout=180)
        (args.output / 'instrumentation.log').write_text(result)
        assert 'OK (1 test)' in result, 'Native IME test failed'
        device.check('native-ime-save-exact-key-and-masked-accessibility')
        (args.output / 'ime-save.png').write_bytes(device.adb('exec-out', 'run-as', PACKAGE, 'cat', 'cache/round4-ime-save.png'))
        device.production(image)
    except Exception as error:
        (args.output / 'failure.txt').write_text(str(error))
        try:
            (args.output / 'package-probe.log').write_text(device.private('cat files/rootfs/root/dsh-native-packages.log', check=False))
            device.screenshot('failure')
        except Exception:
            pass
        raise
    finally:
        try:
            crash = device.adb('logcat', '-b', 'crash', '-d', '-v', 'brief', check=False).decode()
            crash = re.sub(r'(?i)(token=)[^\s&]+', r'\1[redacted]', crash)
            (args.output / 'crash.log').write_text(crash)
        finally:
            stop.set()
            if emulator.poll() is None:
                os.killpg(emulator.pid, signal.SIGTERM)
            emulator.wait(timeout=30)
            log.close()
            subprocess.run([str(tools / 'avdmanager'), 'delete', 'avd', '-n', name], env=device_env, check=False)


if __name__ == '__main__':
    main()
