import hashlib
import importlib.util
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("compiler", Path(__file__).resolve().parents[2] / ".github/scripts/cache-kotlin-compiler.py")
compiler = importlib.util.module_from_spec(spec)
spec.loader.exec_module(compiler)


class VerifiedCompilerTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        previous = Path.cwd()
        os.chdir(self.root)
        self.addCleanup(os.chdir, previous)
        self.home = self.root / "gradle-home"
        self.runner = self.root / "runner"
        self.runner.mkdir()
        self.environment = patch.dict(os.environ, GRADLE_USER_HOME=str(self.home), RUNNER_TEMP=str(self.runner))
        self.environment.start()
        self.addCleanup(self.environment.stop)
        metadata = self.root / "gradle/verification-metadata.xml"
        metadata.parent.mkdir()
        artifacts = []
        for extension in ("jar", "pom"):
            content = extension.encode()
            name = f"{compiler.BASE}.{extension}"
            artifacts.append(f'<artifact name="{name}"><sha256 value="{hashlib.sha256(content).hexdigest()}" /></artifact>')
            cached = self.home / "caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-compiler-embeddable" / compiler.VERSION / "cached" / name
            cached.parent.mkdir(parents=True, exist_ok=True)
            cached.write_bytes(content)
        metadata.write_text('<verification-metadata xmlns="https://schema.gradle.org/dependency-verification"><components>'
                            f'<component group="org.jetbrains.kotlin" name="kotlin-compiler-embeddable" version="{compiler.VERSION}">'
                            + ''.join(artifacts) + '</component></components></verification-metadata>')

    def test_verified_cache_prepares_repository_without_network(self):
        with patch.object(compiler.subprocess, "run") as download:
            compiler.main()
            download.assert_not_called()
        jar = self.runner / "dsh-verified-maven/org/jetbrains/kotlin/kotlin-compiler-embeddable" / compiler.VERSION / f"{compiler.BASE}.jar"
        self.assertEqual(b"jar", jar.read_bytes())
        init = (self.home / "init.d/dsh-verified-compiler.gradle").read_text()
        self.assertIn("includeModule('org.jetbrains.kotlin', 'kotlin-compiler-embeddable')", init)
        self.assertIn("mavenPom()", init)

    def test_corrupt_download_cannot_be_exposed_to_gradle(self):
        jar = next(self.home.rglob("*.jar"))
        jar.write_bytes(b"corrupt cache")
        def corrupt_download(*args, stdout, **kwargs):
            stdout.write(b"corrupt download")
        with patch.object(compiler.subprocess, "run", side_effect=corrupt_download):
            with self.assertRaisesRegex(RuntimeError, "reviewed checksum"):
                compiler.main()
        self.assertFalse((self.home / "init.d/dsh-verified-compiler.gradle").exists())
        self.assertEqual([], list(self.runner.rglob("*.tmp")))
        self.assertEqual([], list(self.runner.rglob("*.jar")))

    def test_missing_reviewed_checksum_fails_before_download(self):
        metadata = self.root / "gradle/verification-metadata.xml"
        metadata.write_text('<verification-metadata xmlns="https://schema.gradle.org/dependency-verification" />')
        with patch.object(compiler.subprocess, "run") as download:
            with self.assertRaises(RuntimeError): compiler.main()
            download.assert_not_called()


if __name__ == "__main__": unittest.main()
