"""Download the APK-pinned public rootfs through its official GitHub release asset."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
from urllib.parse import quote, unquote, urlparse


def release_asset(manifest, lookup):
    url = urlparse(manifest['downloadUrls']['x86_64'])
    match = re.fullmatch(r'/([A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+)/releases/download/([^/]+)/([^/]+)', url.path)
    if url.scheme != 'https' or url.netloc != 'github.com' or not match or url.query or url.fragment:
        raise ValueError('Expected a public GitHub release asset URL')
    repo, tag, filename = (unquote(value) for value in match.groups())
    release = lookup(f'repos/{repo}/releases/tags/{quote(tag, safe="")}')
    if release is None:
        return None
    assets = [asset for asset in release['assets'] if asset['name'] == filename]
    if len(assets) != 1 or not isinstance(assets[0]['id'], int) or assets[0]['id'] <= 0:
        raise ValueError('Pinned release asset is missing or ambiguous')
    return f'repos/{repo}/releases/assets/{assets[0]["id"]}'


def download(manifest, destination, lookup, fetch):
    asset = release_asset(manifest, lookup)
    if asset is None:
        return False
    expected = manifest['downloadSha256']['x86_64'].lower()
    if not re.fullmatch(r'[0-9a-f]{64}', expected):
        raise ValueError('Invalid pinned SHA-256')
    destination.parent.mkdir(parents=True, exist_ok=True)
    temporary = destination.with_name(destination.name + '.download')
    try:
        fetch(asset, temporary)
        with temporary.open('rb') as stream:
            if hashlib.file_digest(stream, 'sha256').hexdigest() != expected:
                raise ValueError('Published rootfs does not match APK SHA-256')
        temporary.replace(destination)
    finally:
        temporary.unlink(missing_ok=True)
    return True


def lookup(path):
    result = subprocess.run(['gh', 'api', path], capture_output=True, text=True)
    if result.returncode:
        if '(HTTP 404)' in result.stderr:
            return None
        raise RuntimeError(f'GitHub release lookup failed: {result.stderr.strip()}')
    return json.loads(result.stdout)


def fetch(path, destination):
    with destination.open('wb') as output:
        subprocess.run(['gh', 'api', '-H', 'Accept: application/octet-stream', path], stdout=output, check=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--destination', type=Path, default=Path('qa-rootfs/rootfs-x86_64.bin'))
    args = parser.parse_args()
    manifest = json.loads(Path('app/src/main/assets/rootfs-manifest.json').read_text())
    published = download(manifest, args.destination, lookup, fetch)
    with open(os.environ['GITHUB_OUTPUT'], 'a') as output:
        output.write('published=' + str(published).lower() + '\n')


if __name__ == '__main__':
    main()
