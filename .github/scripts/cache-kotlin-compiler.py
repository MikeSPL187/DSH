"""Expose a checksum-verified official Kotlin compiler as a CI-only Maven repository."""
import hashlib
import os
from pathlib import Path
import shutil
import subprocess
import urllib.request
import xml.etree.ElementTree as ET

VERSION = "2.1.10"
ASSET = "598697467"
BASE = f"kotlin-compiler-embeddable-{VERSION}"
NS = {"v": "https://schema.gradle.org/dependency-verification"}


def digest(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def main():
    metadata = ET.parse("gradle/verification-metadata.xml")
    gradle_home = Path(os.environ["GRADLE_USER_HOME"])
    repository = Path(os.environ["RUNNER_TEMP"], "dsh-verified-maven")
    module = repository / "org/jetbrains/kotlin/kotlin-compiler-embeddable" / VERSION
    module.mkdir(parents=True, exist_ok=True)
    for extension in ("jar", "pom"):
        name = f"{BASE}.{extension}"
        query = f".//v:component[@group='org.jetbrains.kotlin'][@name='kotlin-compiler-embeddable'][@version='{VERSION}']/v:artifact[@name='{name}']/v:sha256"
        checksums = {entry.attrib["value"] for entry in metadata.findall(query, NS)}
        if len(checksums) != 1:
            raise RuntimeError(f"Expected one reviewed checksum for {name}")
        expected = checksums.pop()
        destination = module / name
        if destination.exists() and digest(destination) == expected:
            continue
        temporary = module / (name + ".tmp")
        try:
            cache = gradle_home / "caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-compiler-embeddable" / VERSION
            cached = next((path for path in cache.glob(f"*/{name}") if digest(path) == expected), None)
            if cached:
                shutil.copyfile(cached, temporary)
            elif extension == "jar":
                with temporary.open("wb") as output:
                    subprocess.run(["gh", "api", "-H", "Accept: application/octet-stream",
                                    f"repos/JetBrains/kotlin/releases/assets/{ASSET}"], stdout=output, check=True)
            else:
                url = f"https://repo.maven.apache.org/maven2/org/jetbrains/kotlin/kotlin-compiler-embeddable/{VERSION}/{name}"
                with urllib.request.urlopen(url, timeout=60) as response, temporary.open("wb") as output:
                    shutil.copyfileobj(response, output)
            if digest(temporary) != expected:
                raise RuntimeError(f"Official {name} does not match the reviewed checksum")
            temporary.replace(destination)
        finally:
            temporary.unlink(missing_ok=True)
    init = gradle_home / "init.d/dsh-verified-compiler.gradle"
    init.parent.mkdir(parents=True, exist_ok=True)
    init.write_text('''// CI-only: both artifacts are SHA-256 verified before Gradle starts.
def localRepository = new File(System.getenv('RUNNER_TEMP'), 'dsh-verified-maven')
def installRepository = { repositories ->
    repositories.exclusiveContent {
        forRepository {
            repositories.maven {
                name = 'DshVerifiedKotlinCompiler'
                url = localRepository.toURI()
                metadataSources { mavenPom() }
            }
        }
        filter { includeModule('org.jetbrains.kotlin', 'kotlin-compiler-embeddable') }
    }
}
settingsEvaluated { settings ->
    installRepository(settings.dependencyResolutionManagement.repositories)
    if (settings.dependencyResolutionManagement.repositoriesMode.get() != org.gradle.api.initialization.resolve.RepositoriesMode.FAIL_ON_PROJECT_REPOS) {
        gradle.beforeProject { project -> installRepository(project.repositories) }
    }
}
''', encoding="utf-8")
    print("Configured checksum-verified official Kotlin compiler repository for CI")


if __name__ == "__main__":
    main()
