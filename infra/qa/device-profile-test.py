"""Check workflow launcher arguments without booting or downloading an AVD."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
LAUNCHER = ROOT / '.github/scripts/run-device-qa.sh'


class DeviceProfileTest(unittest.TestCase):
    def launch(self, api, profile=None):
        with tempfile.TemporaryDirectory() as directory:
            directory = Path(directory)
            capture = directory / 'arguments'
            python = directory / 'python3'
            python.write_text('#!/bin/sh\nprintf "%s\\n" "$@" > "$QA_CAPTURE"\n')
            python.chmod(0o755)
            env = dict(os.environ, PATH=str(directory) + os.pathsep + os.environ['PATH'],
                       QA_CAPTURE=str(capture))
            env.pop('QA_PROFILE', None)
            if profile is not None:
                env['QA_PROFILE'] = profile
            result = subprocess.run(['bash', str(LAUNCHER), str(api)], cwd=ROOT, env=env,
                                    text=True, capture_output=True)
            return result, capture.read_text().splitlines() if capture.exists() else None

    def test_full_and_default_require_rootfs_on_all_three_apis(self):
        for api in (30, 33, 35):
            for profile in (None, 'full'):
                with self.subTest(api=api, profile=profile):
                    result, argv = self.launch(api, profile)
                    self.assertEqual(0, result.returncode, result.stderr)
                    self.assertEqual([
                        'infra/qa/android-device.py', '--api', str(api),
                        '--rootfs', 'qa-rootfs/rootfs-x86_64.bin',
                        '--output', f'device-evidence/api-{api}',
                    ], argv)

    def test_ui_only_omits_linux_without_changing_api_or_output(self):
        for api in (30, 33, 35):
            with self.subTest(api=api):
                result, argv = self.launch(api, 'ui-layout')
                self.assertEqual(0, result.returncode, result.stderr)
                self.assertEqual([
                    'infra/qa/android-device.py', '--api', str(api),
                    '--output', f'device-evidence/api-{api}', '--ui-only',
                ], argv)

    def test_focused_profiles_keep_host_integration_and_its_pinned_image(self):
        for profile in ('integration-only', 'host-probe'):
            result, argv = self.launch(30, profile)
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertIn('--integration-only', argv)
            self.assertIn('--rootfs', argv)
            self.assertEqual(profile == 'host-probe', '--host-probe' in argv)
            self.assertNotIn('--ui-only', argv)

    def test_focused_profiles_cannot_silently_run_on_other_apis(self):
        for api in (33, 35):
            for profile in ('integration-only', 'host-probe'):
                result, argv = self.launch(api, profile)
                self.assertEqual(2, result.returncode)
                self.assertIsNone(argv)

    def test_unknown_api_or_profile_fails_before_launch(self):
        for api, profile in ((29, 'full'), (30, 'typo'), ('30;exit', 'full')):
            result, argv = self.launch(api, profile)
            self.assertEqual(2, result.returncode)
            self.assertIsNone(argv)


if __name__ == '__main__':
    unittest.main()
