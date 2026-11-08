import hashlib
import importlib.util
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('rootfs', Path(__file__).resolve().parents[2] / '.github/scripts/download-pinned-rootfs.py')
rootfs = importlib.util.module_from_spec(spec)
spec.loader.exec_module(rootfs)


class PinnedRootfsTest(unittest.TestCase):
    manifest = {'downloadUrls': {'x86_64': 'https://github.com/MikeSPL187/DSH/releases/download/rootfs-v2.0.3/rootfs-x86_64.bin'},
                'downloadSha256': {'x86_64': hashlib.sha256(b'image').hexdigest()}}

    def release(self, path):
        self.assertEqual('repos/MikeSPL187/DSH/releases/tags/rootfs-v2.0.3', path)
        return {'assets': [{'id': 123, 'name': 'rootfs-x86_64.bin'}]}

    def test_success_is_committed_only_after_hash_matches(self):
        with tempfile.TemporaryDirectory() as directory:
            target = Path(directory, 'rootfs')
            def fetch(path, file):
                self.assertEqual('repos/MikeSPL187/DSH/releases/assets/123', path)
                file.write_bytes(b'image')
            self.assertTrue(rootfs.download(self.manifest, target, self.release, fetch))
            self.assertEqual(b'image', target.read_bytes())

    def test_corrupt_or_failed_download_preserves_existing_image(self):
        for failure in (False, True):
            with tempfile.TemporaryDirectory() as directory:
                target = Path(directory, 'rootfs')
                target.write_bytes(b'existing')
                def fetch(path, file):
                    file.write_bytes(b'wrong')
                    if failure: raise OSError('network failure')
                with self.assertRaises((OSError, ValueError)):
                    rootfs.download(self.manifest, target, self.release, fetch)
                self.assertEqual(b'existing', target.read_bytes())
                self.assertFalse(target.with_name('rootfs.download').exists())

    def test_missing_release_allows_candidate_but_missing_asset_fails(self):
        with tempfile.TemporaryDirectory() as directory:
            target = Path(directory, 'rootfs')
            self.assertFalse(rootfs.download(self.manifest, target, lambda _: None, lambda *_: self.fail()))
            with self.assertRaises(ValueError):
                rootfs.download(self.manifest, target, lambda _: {'assets': []}, lambda *_: self.fail())

    def test_lookup_does_not_convert_network_errors_into_candidate_fallback(self):
        for status, expected in (('404', None), ('504', RuntimeError)):
            result = subprocess.CompletedProcess([], 1, '', f'gh: error (HTTP {status})')
            with patch.object(rootfs.subprocess, 'run', return_value=result):
                if expected:
                    with self.assertRaises(expected): rootfs.lookup('fixture')
                else: self.assertIsNone(rootfs.lookup('fixture'))

    def test_non_github_urls_are_rejected_before_network(self):
        manifest = dict(self.manifest, downloadUrls={'x86_64': 'https://example.com/image'})
        with self.assertRaises(ValueError): rootfs.release_asset(manifest, lambda _: self.fail())


if __name__ == '__main__': unittest.main()
