"""Populate Gradle's artifact cache from a checksum-verified official release asset."""
import hashlib
import os
from pathlib import Path
import subprocess
import xml.etree.ElementTree as ET

VERSION = "2.1.10"
ASSET = "598697467"
NAME = f"kotlin-compiler-embeddable-{VERSION}.jar"
NS = {"v": "https://schema.gradle.org/dependency-verification"}


def digest(path, algorithm):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, algorithm).hexdigest()


def main():
    root = ET.parse("gradle/verification-metadata.xml")
    query = f".//v:component[@group='org.jetbrains.kotlin'][@name='kotlin-compiler-embeddable'][@version='{VERSION}']/v:artifact[@name='{NAME}']/v:sha256"
    checksums = {entry.attrib["value"] for entry in root.findall(query, NS)}
    if len(checksums) != 1:
        raise RuntimeError("Expected one reviewed Kotlin compiler checksum")
    expected = checksums.pop()
    cache = Path(os.environ["GRADLE_USER_HOME"], "caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-compiler-embeddable", VERSION)
    if any(digest(path, "sha256") == expected for path in cache.glob(f"*/{NAME}")):
        print("Verified Kotlin compiler is already cached")
        return
    archive = Path(os.environ["RUNNER_TEMP"], NAME)
    try:
        with archive.open("wb") as output:
            subprocess.run(["gh", "api", "-H", "Accept: application/octet-stream",
                            f"repos/JetBrains/kotlin/releases/assets/{ASSET}"], stdout=output, check=True)
        if digest(archive, "sha256") != expected:
            raise RuntimeError("Official Kotlin compiler asset does not match the reviewed dependency checksum")
        # Gradle uses SHA-1 for cache addressing; SHA-256 above authenticates the bytes.
        destination = cache / digest(archive, "sha1") / NAME
        destination.parent.mkdir(parents=True, exist_ok=True)
        archive.replace(destination)
        print("Cached checksum-verified Kotlin compiler")
    finally:
        archive.unlink(missing_ok=True)


if __name__ == "__main__":
    main()
