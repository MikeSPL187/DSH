import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location("boundaries", Path(__file__).resolve().parents[1] / "check-module-boundaries.py")
boundaries = importlib.util.module_from_spec(spec)
spec.loader.exec_module(boundaries)


class ModuleBoundariesTest(unittest.TestCase):
    def test_core_cannot_import_runtime_plugins_or_features(self):
        for dependency in (":runtime:host", ":plugin:surface", ":feature:chat", ":app"):
            self.assertTrue(boundaries.violations("core:data", f'implementation(project("{dependency}"))'))

    def test_outer_modules_do_not_depend_on_app(self):
        for module in ("feature:chat", "runtime:host", "plugin:surface"):
            self.assertTrue(boundaries.violations(module, 'api(project(":app"))'))

    def test_existing_direction_and_test_dependencies_are_allowed(self):
        self.assertEqual([], boundaries.violations("feature:chat", 'implementation(project(":core:data"))'))
        self.assertEqual([], boundaries.violations("core:data", 'testImplementation(project(":feature:chat"))'))

    def test_comments_are_ignored_and_multiline_calls_are_checked(self):
        self.assertEqual([], boundaries.violations("core:data", '// implementation(project(":app"))'))
        self.assertTrue(boundaries.violations("core:data", 'api(\n project(":app")\n)'))


if __name__ == "__main__": unittest.main()
