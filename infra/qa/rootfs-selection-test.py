import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location("device", Path(__file__).with_name("android-device.py"))
device = importlib.util.module_from_spec(spec)
spec.loader.exec_module(device)


class RootfsSelectionTest(unittest.TestCase):
    def test_ui_only_does_not_require_linux(self):
        self.assertIsNone(device.rootfs_for_run(None, True, Path("absent-manifest")))

    def test_linux_tests_always_require_an_image(self):
        with self.assertRaises(ValueError): device.rootfs_for_run(None, False)

    def test_explicit_images_are_checked_even_in_ui_mode(self):
        with tempfile.TemporaryDirectory() as directory:
            image, manifest = Path(directory, "rootfs"), Path(directory, "manifest")
            image.write_bytes(b"fixture")
            manifest.write_text(json.dumps({"downloadSha256": {"x86_64": hashlib.sha256(b"fixture").hexdigest()}}))
            for ui in (True, False):
                self.assertEqual(image.resolve(), device.rootfs_for_run(image, ui, manifest))
                image.write_bytes(b"wrong")
                with self.assertRaises(ValueError): device.rootfs_for_run(image, ui, manifest)
                image.write_bytes(b"fixture")


if __name__ == "__main__": unittest.main()
