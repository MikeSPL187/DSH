#!/usr/bin/env python3
"""Real APK/native-image QA. Disposable devices only; no provider credentials."""
import argparse
import hashlib
import http.client
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
from mcp_fixture import server as mcp_server

PACKAGE = 'app.dsh.mobile.debug'


class Device:
    def __init__(self, output):
        self.output, self.serial, self.checks = output, 0, {}
        self.a11y = {}

    def adb(self, *args, timeout=60, stdin=None, check=True):
        return subprocess.run([str(Path(os.environ['ANDROID_HOME']) / 'platform-tools/adb'), '-s', 'emulator-5554', *args], stdin=stdin,
                              capture_output=True, timeout=timeout, check=check).stdout

    def shell(self, command, **kwargs):
        return self.adb('shell', command, **kwargs).decode()

    def private(self, command, **kwargs):
        return self.shell('run-as ' + PACKAGE + ' sh -c ' + shlex.quote(command), **kwargs)

    def instrument(self, command, log_name, timeout):
        try:
            result = self.shell(command, timeout=timeout)
        except subprocess.TimeoutExpired as error:
            result = (error.stdout or b'').decode(errors='replace')
            (self.output / log_name).write_text(result + '\nINSTRUMENTATION TIMED OUT\n')
            raise
        (self.output / log_name).write_text(result)
        return result


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
        raise AssertionError('Action unavailable: ' + label + '; visible: ' + repr([self.label(n) for n in nodes if self.label(n)]))

    def audit_actions(self, screen):
        nodes = self.dump()
        actions = [n for n in nodes if n.get('package') == PACKAGE and n.get('clickable') == 'true' and n.get('enabled') == 'true']
        missing = [n.attrib for n in actions if not self.label(n).strip()]
        self.a11y[screen] = [{'label': self.label(n), 'checked': n.get('checked'), 'bounds': n.get('bounds')} for n in actions]
        (self.output / 'accessibility-actions.json').write_text(json.dumps(self.a11y, ensure_ascii=False, indent=2) + '\n')
        assert not missing, 'Unnamed native actions on ' + screen + ': ' + repr(missing)

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

    def open_settings(self):
        self.adb('shell', 'am', 'start', '-f', '0x20000000', '-a', 'app.dsh.action.NEW_CHAT', '-n', PACKAGE + '/com.dsh.MainActivity')
        nodes = self.wait(lambda n: any(re.search(r'menu|drawer', self.label(x), re.I) for x in n))
        self.tap(next(x for x in nodes if re.search(r'menu|drawer', self.label(x), re.I)))
        self.click('Settings')
        for _ in range(3):
            if self.has('Settings', self.dump()):
                return
            self.adb('shell', 'input', 'keyevent', '4')
        raise AssertionError('Settings root did not reopen')

    def mcp_lifecycle(self, methods):
        self.open_settings()
        self.click('MCP servers', scroll=True)
        self.click('Add server', scroll=True)
        for label, value in [('Name', 'qa_lifecycle'), ('Address', 'http://127.0.0.1:38889/mcp')]:
            nodes = self.wait(lambda n: any(x.get('class') == 'android.widget.EditText' and x.get('content-desc') == label for x in n))
            self.tap(next(x for x in nodes if x.get('class') == 'android.widget.EditText' and x.get('content-desc') == label))
            self.shell('input text ' + shlex.quote(value))
        self.adb('shell', 'input', 'keyevent', '4')
        self.click('Add', scroll=True)
        self.wait(lambda n: self.has('Restart', n) or any('qa_lifecycle, running' in x.get('content-desc', '') for x in n))
        if self.has('Restart', self.dump()):
            self.click('Restart')
        self.wait(lambda n: any('qa_lifecycle, running' in x.get('content-desc', '') for x in n), seconds=120)
        assert methods.count('initialize') >= 1 and 'tools/list' in methods, 'MCP was marked running without negotiation'
        self.screenshot('mcp-connected')
        self.check('native-mcp-add-restart-connects-real-peer')
        self.open_settings()
        self.click('Host', scroll=True)
        previous_proxy = self.shell('settings get global http_proxy').strip()
        try:
            self.shell('settings put global http_proxy 127.0.0.1:9')
            self.click('Stop', scroll=True)
            self.wait(lambda n: self.has('Start', n))
            self.click('Start')
            self.wait(lambda n: self.has('Stop', n), seconds=120)
        finally:
            if previous_proxy in ('', 'null'):
                # Deleting the legacy key alone leaves ProxyTracker's canonical proxy active.
                self.shell('settings put global http_proxy :0')
                self.shell('settings delete global http_proxy')
            else:
                self.shell('settings put global http_proxy ' + shlex.quote(previous_proxy))
        self.check('native-host-stop-start-with-user-proxy')
        self.open_settings()
        self.click('MCP servers', scroll=True)
        self.wait(lambda n: any('qa_lifecycle, running' in x.get('content-desc', '') for x in n), seconds=120)
        assert methods.count('initialize') >= 2, 'MCP peer did not reconnect after Host restart'
        assert not any('qa_lifecycle, after restart' in x.get('content-desc', '') for x in self.dump()), 'MCP remained pending after reconnect'
        self.check('native-mcp-reconnect-settles-after-host-stop-start')
        self.tap(next(x for x in self.dump() if x.get('content-desc', '').startswith('qa_lifecycle,')))
        self.click('Delete')
        self.click('Tap again to delete')
        self.wait(lambda n: self.has('No servers yet', n))
        self.check('native-mcp-two-tap-delete')

    def cold_starts(self):
        samples = []
        for iteration in range(5):
            self.adb('shell', 'am', 'force-stop', PACKAGE)
            started = time.monotonic()
            launch = self.shell('am start -W -n ' + PACKAGE + '/com.dsh.MainActivity', timeout=90)
            self.wait(lambda n: any(re.search(r'menu|drawer', self.label(x), re.I) for x in n) or self.has('Settings', n), seconds=90)
            usable_ms = round((time.monotonic() - started) * 1000)
            assert self.foreground(), 'Cold start dropped to launcher'
            assert usable_ms < 20_000, 'Cold start blocked navigation for ' + str(usable_ms) + ' ms'
            total = re.search(r'TotalTime:\s*(\d+)', launch)
            samples.append({'iteration': iteration + 1, 'amTotalTimeMs': int(total.group(1)) if total else None,
                            'usableWithUiAutomatorOverheadMs': usable_ms})
        (self.output / 'cold-starts.json').write_text(json.dumps(samples, indent=2) + '\n')
        self.check('native-five-cold-starts-allow-navigation-without-host-gate')

    def proxy_auth(self):
        result = self.instrument('am instrument -w -r -e class com.dsh.NativeHostProxyTest ' + PACKAGE + '.test/androidx.test.runner.AndroidJUnitRunner', 'proxy-instrumentation.log', 150)
        assert 'OK (1 test)' in result, 'Native authentication/RPC with an unreachable user proxy failed'
        self.check('native-host-authentication-and-rpc-with-user-proxy')

    def automation(self, methods, probe_only=False):
        target = "com.dsh.NativeAutomationTest" + ("#providerFailureReachesTerminalHistory" if probe_only else "")
        result = self.instrument('am instrument -w -r -e class ' + target + ' ' + PACKAGE + '.test/androidx.test.runner.AndroidJUnitRunner', 'automation-instrumentation.log', 600)
        for name in ('automation.json', 'provider-error.json', 'chat-layout.json', 'offline.json', 'readonly.json', 'provider-save-diagnostic.json', 'integration-state.txt',
                     'integration-failure.png', 'chat-ime-font13.png', 'chat-landscape-font13.png',
                     *[case + '-failure.' + ext for case in ('scheduled', 'provider-error', 'chat-layout', 'offline', 'readonly') for ext in ('png', 'txt')]):
            path = 'cache/qa-native-' + name
            if self.private('test -f ' + shlex.quote(path) + ' && echo present', check=False).strip() == 'present':
                (self.output / ('native-' + name)).write_bytes(self.adb('exec-out', 'run-as', PACKAGE, 'cat', path))
        if probe_only:
            assert 'OK (1 test)' in result, 'Native provider save / HTTP probe failed'
            assert 'llm/qa-fail' in methods and 'llm/auth-ok' in methods and 'llm/auth-bad' not in methods
            self.check('native-provider-save-and-controlled-http-error-roundtrip')
            return
        assert 'OK (5 tests)' in result, 'Native scheduled automation / chat integration failed'
        outcome = json.loads(self.private('cat cache/qa-native-automation.json'))
        assert outcome == {'scheduled': 'ok', 'historyCount': 1, 'model': 'qa-model', 'idleHost': 'running', 'workspaceMarker': True}
        assert 'llm/project-read-confirmed' in methods and 'llm/project-read-missing' not in methods
        assert json.loads(self.private('cat cache/qa-native-provider-error.json')) == {'providerFailure': 'err'}
        assert json.loads(self.private('cat cache/qa-native-chat-layout.json')) == {
            'imeAboveComposer': True, 'fontScale': 1.3, 'landscapeDraftRetained': True}
        assert json.loads(self.private('cat cache/qa-native-offline.json')) == {
            'reason': 'offline', 'createdSessionWhileOffline': False, 'retry': 'ok'}
        assert json.loads(self.private('cat cache/qa-native-readonly.json')) == {'shellDenied': True, 'terminalHistory': True}
        assert 'llm/readonly-shell-requested' in methods and 'llm/readonly-denied' in methods
        assert 'llm/readonly-unexpected-result' not in methods
        assert 'llm/qa-model' in methods and 'llm/qa-fail' in methods and 'llm/auth-ok' in methods
        assert 'llm/auth-bad' not in methods, 'Host did not forward the exact synthetic saved key'
        self.check('native-provider-save-vault-host-and-http-credential-roundtrip')
        self.check('native-jobscheduler-fires-on-real-time-without-run-now')
        self.check('native-automation-inherits-default-model-and-records-provider-error')
        self.check('native-real-chat-last-reply-above-ime-at-font13')
        self.check('native-real-chat-landscape-preserves-draft-at-font13')
        self.check('native-idle-settings-preserves-host-before-scheduled-fire')
        self.check('native-scheduled-automation-reads-the-selected-android-project')
        self.check('native-offline-send-records-reason-and-retries-after-network-restored')
        self.check('native-readonly-shell-denial-finishes-with-explicit-report')

    def recovery(self, port, methods):
        component = PACKAGE + '.test/androidx.test.runner.AndroidJUnitRunner'
        command = 'am instrument -w -r -e class com.dsh.NativeChatRecoveryTest#startInterruptedRequest ' + component
        path = self.output / 'recovery-start-instrumentation.log'
        with path.open('wb') as log:
            runner = subprocess.Popen([str(Path(os.environ['ANDROID_HOME']) / 'platform-tools/adb'), '-s', 'emulator-5554', 'shell', command],
                                      stdout=log, stderr=subprocess.STDOUT)
            try:
                deadline = time.monotonic() + 150
                while time.monotonic() < deadline:
                    if self.private('cat cache/qa-native-recovery-ready.txt', check=False).strip():
                        break
                    assert runner.poll() is None, 'Recovery preparation exited before receiving a partial answer'
                    time.sleep(.25)
                else:
                    raise AssertionError('No persisted partial response before force-stop')
                records = json.loads(self.private('cat files/chat-outbox-v1.json'))
                record = next(x for x in records if x['model']['modelId'] == 'qa-recovery')
                assert 'Native partial response' in record['partial'], 'Partial answer was not durably checkpointed'
                recreation = json.loads(self.private('cat cache/qa-native-recreation.json'))
                assert recreation == {'activityRecreated': True, 'requestStillActive': True}
                (self.output / 'native-recreation.json').write_text(json.dumps(recreation, indent=2) + '\n')
                self.check('native-activity-recreation-preserves-active-application-owned-request')
                (self.output / 'recovery-before-kill.json').write_text(json.dumps({k: record[k] for k in ('id', 'sessionId', 'status', 'partial')}, indent=2) + '\n')
                self.adb('shell', 'am', 'force-stop', PACKAGE)
                assert not self.shell('pidof ' + PACKAGE, check=False).strip(), 'Application process survived force-stop'
            finally:
                self.adb('shell', 'am', 'force-stop', PACKAGE, check=False)
                try:
                    runner.wait(timeout=15)
                except subprocess.TimeoutExpired:
                    runner.kill()
                    runner.wait(timeout=15)
        peer = http.client.HTTPConnection('127.0.0.1', port, timeout=10)
        try:
            peer.request('POST', '/qa/recovery-resume', '{}', {'Content-Type': 'application/json'})
            response = peer.getresponse()
            assert response.status == 200 and json.loads(response.read()) == {'ready': True}
        finally:
            peer.close()
        result = self.instrument('am instrument -w -r -e class com.dsh.NativeChatRecoveryTest#recoverInterruptedRequestAndRetryFromChat ' + component,
                                 'recovery-instrumentation.log', 300)
        for name in ('recovery.json', 'interrupted-chat.png'):
            file = 'cache/qa-native-' + name
            if self.private('test -f ' + shlex.quote(file) + ' && echo present', check=False).strip() == 'present':
                (self.output / ('native-' + name)).write_bytes(self.adb('exec-out', 'run-as', PACKAGE, 'cat', file))
        assert 'OK (1 test)' in result, 'Process-death recovery or UI Retry failed'
        assert 'llm/recovery-partial' in methods
        assert json.loads(self.private('cat cache/qa-native-recovery.json')) == {
            'forceStop': True, 'partialRetained': True, 'interruptionVisible': True, 'retry': 'ok'}
        self.check('native-force-stop-retains-durable-partial-and-visible-interruption')
        self.check('native-interrupted-chat-retry-completes-through-real-host')

    def production(self, image, methods, integration_only=False):
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
        if integration_only:
            return
        self.wait(lambda n: any(re.search(r'menu|меню|drawer', self.label(x), re.I) for x in n))
        nodes = self.dump()
        self.tap(next(x for x in nodes if re.search(r'menu|меню|drawer', self.label(x), re.I)))
        self.wait(lambda n: self.has('Settings', n))
        self.click('Settings')
        self.audit_actions('settings-root')
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
        self.click('Porcelain · Light')
        self.wait(lambda n: self.has('Porcelain · Light', n))
        time.sleep(1)
        assert not self.dark_pixels(), 'Porcelain did not become light'
        self.screenshot('porcelain')
        self.click('System')
        self.shell('cmd uimode night yes')
        self.wait(lambda n: self.has('Appearance', n))
        for _ in range(20):
            if self.dark_pixels(): break
            time.sleep(.5)
        else: raise AssertionError('System night theme/bars did not become dark')
        self.screenshot('system-night')
        self.shell('cmd uimode night no')
        for _ in range(20):
            if not self.dark_pixels(): break
            time.sleep(.5)
        else: raise AssertionError('System day theme did not become light')
        self.audit_actions('appearance-en')
        self.check('porcelain-and-system-day-night')
        self.click('Graphite · Dark')
        for cycle in range(2):
            self.wait(lambda n: self.has('Русский', n))
            self.click('Русский')
            self.wait(lambda n: self.has('Оформление', n))
            assert self.foreground(), 'MainActivity lost foreground after Russian locale'
            self.screenshot('appearance-ru-' + str(cycle))
            self.audit_actions('appearance-ru-' + str(cycle))
            self.wait(lambda n: self.has('English', n))
            self.click('English')
            self.wait(lambda n: self.has('Appearance', n) and self.has('Graphite · Dark', n))
            assert self.foreground(), 'MainActivity lost foreground after English locale'
        self.check('appearance-retained-through-two-locale-cycles')
        self.check('native-settings-and-appearance-actions-have-accessible-names')
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
        self.upload(Path('infra/qa/native-fileops.mjs'), 'files/rootfs/root/dsh-native-fileops.mjs')
        self.upload(Path('infra/qa/native-title.mjs'), 'files/rootfs/root/dsh-native-title.mjs')
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
            print(self.private('cat files/rootfs/root/dsh-native-packages.log', check=False), flush=True)
            self.dump()
            raise AssertionError('Terminal package/cancellation probe did not complete')
        self.screenshot('terminal-packages')
        self.check('native-dpkg-build-install-upgrade-purge-and-curl')
        assert 'PASS' in self.private('cat files/rootfs/root/dsh-native-title.result')
        self.check('native-host-title-uses-complete-words-from-the-shipped-image')
        assert 'PASS' in self.private('cat files/rootfs/root/dsh-native-fileops.result')
        self.check('native-node-file-publication-retains-source-without-overwriting-target')
        cancelled = json.loads(self.private('cat files/rootfs/root/dsh-native-cancel.result'))
        assert cancelled == {'portFreed': True, 'unrelatedListenerPreserved': True}
        self.check('native-android-cancel-frees-server-and-preserves-other-listener')
        self.mcp_lifecycle(methods)
        self.cold_starts()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--api', type=int, required=True)
    parser.add_argument('--rootfs', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--integration-only', action='store_true')
    parser.add_argument('--host-probe', action='store_true')
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
        if not args.integration_only:
            result = device.instrument('am instrument -w -r -e class com.dsh.ReplaceKeyImeTest,com.dsh.OnboardingImeTest,com.dsh.NativeBackupTest ' + PACKAGE + '.test/androidx.test.runner.AndroidJUnitRunner', 'instrumentation.log', 180)
            for source, target in [('round4-ime-save.png', 'ime-save.png'), ('round4-ime-state.json', 'ime-state.json')]:
                path = 'cache/' + source
                if device.private('test -f ' + shlex.quote(path) + ' && echo present', check=False).strip() == 'present':
                    (args.output / target).write_bytes(device.adb('exec-out', 'run-as', PACKAGE, 'cat', path))
            assert 'OK (4 tests)' in result, 'Native IME test failed'
            device.check('native-ime-save-exact-key-and-masked-accessibility')
            device.check('native-ime-onboarding-continue-exact-key')
            device.check('native-skip-with-ime-and-installer-details-back')
            device.check('native-backup-restore-cancel-corrupt-and-share-receiver')
        with mcp_server() as (port, methods):
            device.adb('reverse', 'tcp:38889', 'tcp:' + str(port))
            try:
                device.production(image, methods, args.integration_only)
                device.proxy_auth()
                device.automation(methods, args.host_probe)
                if not args.host_probe:
                    device.recovery(port, methods)
            finally:
                device.adb('reverse', '--remove', 'tcp:38889', check=False)
                (args.output / 'mcp-methods.json').write_text(json.dumps(methods, indent=2) + '\n')
        (args.output / 'meminfo.txt').write_text(device.shell('dumpsys meminfo ' + PACKAGE))
        (args.output / 'gfxinfo.txt').write_text(device.shell('dumpsys gfxinfo ' + PACKAGE))
        (args.output / 'source-revision.txt').write_text(subprocess.check_output(['git', 'rev-parse', 'HEAD']).decode())
    except Exception as error:
        (args.output / 'failure.txt').write_text(str(error))
        try:
            (args.output / 'package-probe.log').write_text(device.private('cat files/rootfs/root/dsh-native-packages.log', check=False))
            host_log = device.private('cat files/logs/host_*.log', check=False)
            host_log = re.sub(r'(?i)(token=)[A-Za-z0-9_-]+', r'\1[redacted]', host_log)
            (args.output / 'host-runtime.log').write_text(host_log[-80_000:])
            (args.output / 'android-proxy.txt').write_text(device.shell('settings get global http_proxy', check=False))
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
