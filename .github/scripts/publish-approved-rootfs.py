#!/usr/bin/env python3
"""Publish the explicitly approved, already tested rootfs artifact unchanged."""
import base64
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import time
import urllib.request
import zipfile

os.umask(0o077)
ROOT = Path(os.environ['ROOTFS_RELEASE_DIR'])
ROOT.mkdir(parents=True, exist_ok=True)
REPO = 'MikeSPL187/DSH'
TAG = 'rootfs-v2.0.2'
SOURCE = '1cc61d5778d8124f13931a2f050851944e3cbfd9'
APK_SOURCE = '12871835ad24fae08ce15a95c110ce74bff6b738'
RUN = 42145348410
ARTIFACT = 13470646003
ZIP_SHA = '0c57cbb09dc13ebb9250379837456b36c99bf24448becfbb8e2e218e303547cd'
PINS = {
    'rootfs-x86_64.bin': '0f7bd5236b0a29a93af9e99edbf4b3831059feed37c43101a4659410d1e2d082',
    'rootfs-arm64-v8a.bin': 'c60321ab63b3ecd27d63eb2b8abf8504b561c7ed55ac14f903f29761fcbbcb64',
}
ASSETS = set(PINS) | {'rootfs-manifest.json', 'sbom-x86_64.tar.gz',
                           'sbom-arm64.tar.gz', 'source-revision.txt', 'SHA256SUMS'}


def gh(*args, output=None):
    result = subprocess.run(['gh', *args], stdout=output or subprocess.PIPE,
                            stderr=subprocess.PIPE, timeout=1200)
    if result.returncode:
        (ROOT / 'last-gh-error.txt').write_bytes(result.stderr)
        raise RuntimeError('GitHub command failed; private diagnostics saved locally')
    return result.stdout


def api(path, data=None, allow_missing=False):
    args = ['api', 'repos/' + REPO + '/' + path]
    if data is not None:
        request = ROOT / 'api-request.json'
        request.write_text(json.dumps(data))
        args += ['--method', 'POST' if path == 'releases' else 'PATCH', '--input', str(request)]
    result = subprocess.run(['gh', *args], capture_output=True, timeout=90)
    if result.returncode:
        if allow_missing:
            try:
                if json.loads(result.stdout).get('status') == '404':
                    return None
            except (ValueError, AttributeError):
                pass
        (ROOT / 'last-gh-error.txt').write_bytes(result.stderr)
        try:
            error = json.loads(result.stdout)
            detail = {k: error[k] for k in ('message', 'status', 'errors') if k in error}
        except ValueError:
            detail = {'message': 'non-JSON API failure'}
        raise RuntimeError('GitHub API failed: ' + path + ' ' + json.dumps(detail))
    return json.loads(result.stdout)


def digest(path):
    h = hashlib.sha256()
    with path.open('rb') as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b''):
            h.update(chunk)
    return h.hexdigest()


def save_state(state):
    (ROOT / 'publication.json').write_text(json.dumps(state, indent=2) + '\n')


def prepare():
    assert api('releases/tags/' + TAG, allow_missing=True) is None, 'Release already exists'
    tag = api('git/ref/tags/' + TAG, allow_missing=True)
    if tag is not None:
        obj = tag['object']
        while obj['type'] == 'tag':
            obj = api('git/tags/' + obj['sha'])['object']
        assert obj['type'] == 'commit' and obj['sha'] == SOURCE, 'Existing tag targets another revision'
    run = api('actions/runs/' + str(RUN))
    assert run['head_sha'] == SOURCE and run['conclusion'] == 'success'
    native = api('actions/runs/42149123847')
    apk = api('actions/runs/42149123906')
    assert native['head_sha'] == apk['head_sha'] == APK_SOURCE
    assert native['conclusion'] == apk['conclusion'] == 'success'
    artifact = api('actions/artifacts/' + str(ARTIFACT))
    assert not artifact['expired'] and artifact['name'] == 'rootfs-2.0.2-candidate'
    assert artifact['digest'] == 'sha256:' + ZIP_SHA
    pinned = api('contents/app/src/main/assets/rootfs-manifest.json?ref=' + APK_SOURCE)
    pinned = json.loads(base64.b64decode(pinned['content']))
    extracted = Path(os.environ['ROOTFS_ARTIFACT_DIR'])
    assert {p.name for p in extracted.iterdir()} == ASSETS | {'CANDIDATE.txt'}
    assert all(p.is_file() and not p.is_symlink() for p in extracted.iterdir())
    assert sum(p.stat().st_size for p in extracted.iterdir()) < 650_000_000
    hashes = {}
    for line in (extracted / 'SHA256SUMS').read_text().splitlines():
        sha, name = line.split('  ', 1)
        assert name in ASSETS | {'CANDIDATE.txt'} and '/' not in name
        assert digest(extracted / name) == sha, 'CI checksum mismatch: ' + name
        hashes[name] = sha
    assert set(hashes) == (ASSETS - {'SHA256SUMS'}) | {'CANDIDATE.txt'}
    assert hashes['rootfs-manifest.json'] == '5f1dda1a50ad72ee15cf4a5f1cb6552bc05bf18f8f3c139b93f800956777adbb'
    assert hashes['sbom-arm64.tar.gz'] == 'b7144b9609c3d7689ebeb9c1ac570d487b7dfb1cf3f1c65876b02019ec0d3c76'
    assert hashes['sbom-x86_64.tar.gz'] == '2223b0a2ef9a44cdcae319650d1b26e9e6c25d94c80f89baccbe4e2eeb62d8e2'
    assert (extracted / 'source-revision.txt').read_text().strip() == SOURCE
    manifest = json.loads((extracted / 'rootfs-manifest.json').read_text())
    assert manifest == pinned, 'Release manifest differs from the tested APK pins'
    assert manifest['releaseTag'] == TAG
    for abi, name in [('x86_64', 'rootfs-x86_64.bin'), ('arm64-v8a', 'rootfs-arm64-v8a.bin')]:
        assert manifest['downloadSha256'][abi].lower() == hashes[name] == PINS[name]
        assert manifest['downloadUrls'][abi] == 'https://github.com/' + REPO + '/releases/download/' + TAG + '/' + name
    release_dir = ROOT / 'release-assets'
    release_dir.mkdir(exist_ok=True)
    for name in sorted(ASSETS - {'SHA256SUMS'}):
        (extracted / name).rename(release_dir / name)
    # The original CI checksum file included CANDIDATE.txt. Verify it above,
    # then generate publication checksums for exactly the released assets.
    (release_dir / 'SHA256SUMS').write_text(''.join(
        digest(release_dir / name) + '  ' + name + '\n' for name in sorted(ASSETS - {'SHA256SUMS'})))
    released = {name: {'sha256': digest(release_dir / name), 'size': (release_dir / name).stat().st_size}
                for name in sorted(ASSETS)}
    state = {'repository': REPO, 'tag': TAG, 'source_revision': SOURCE, 'ci_run': RUN,
             'artifact_id': ARTIFACT, 'artifact_zip_sha256': ZIP_SHA,
             'native_run': native['id'], 'apk_run': apk['id'], 'assets': released,
             'status': 'verified-before-publication'}
    save_state(state)
    print(json.dumps(state, indent=2), flush=True)


def validate_assets(release, state):
    assert release['tag_name'] == TAG and release['target_commitish'] == SOURCE
    assets = {a['name']: a for a in release['assets']}
    assert set(assets) == ASSETS
    for name, expected in state['assets'].items():
        actual = assets[name]
        assert actual['state'] == 'uploaded' and actual['size'] == expected['size'], name
        assert actual['digest'] == 'sha256:' + expected['sha256'], 'Remote digest mismatch: ' + name


def draft():
    state = json.loads((ROOT / 'publication.json').read_text())
    assert state['status'] == 'verified-before-publication'
    assert api('releases/tags/' + TAG, allow_missing=True) is None
    notes = Path('.github/scripts/rootfs-v2.0.2-notes.md').read_text()
    release = api('releases', {'tag_name': TAG, 'target_commitish': SOURCE,
                             'name': 'Linux 2.0.2 для DSH Mobile 1.1.4', 'body': notes,
                             'draft': True, 'prerelease': True, 'make_latest': 'false'})
    state['release_id'] = release['id']
    state['status'] = 'draft-created'
    save_state(state)
    gh('release', 'upload', TAG, '--repo', REPO,
       *[str(ROOT / 'release-assets' / name) for name in sorted(ASSETS)])
    release = api('releases/' + str(release['id']))
    assert release['draft']
    validate_assets(release, state)
    state['status'] = 'draft-assets-verified'
    save_state(state)
    print('Draft assets verified:', len(release['assets']), flush=True)


def publish():
    state = json.loads((ROOT / 'publication.json').read_text())
    assert state['status'] == 'draft-assets-verified'
    release = api('releases/' + str(state['release_id']))
    assert release['draft']
    validate_assets(release, state)
    release = api('releases/' + str(state['release_id']),
                  {'draft': False, 'prerelease': True, 'make_latest': 'false'})
    assert not release['draft'] and release['published_at']
    validate_assets(release, state)
    obj = api('git/ref/tags/' + TAG)['object']
    while obj['type'] == 'tag':
        obj = api('git/tags/' + obj['sha'])['object']
    assert obj['type'] == 'commit' and obj['sha'] == SOURCE
    state.update(status='published', url=release['html_url'], published_at=release['published_at'])
    save_state(state)
    print('Published:', release['html_url'], flush=True)


def verify_public():
    state = json.loads((ROOT / 'publication.json').read_text())
    assert state['status'] in ('published', 'public-downloads-verified')
    verified = {}
    for name in sorted(ASSETS):
        url = 'https://github.com/' + REPO + '/releases/download/' + TAG + '/' + name
        for attempt in range(5):
            try:
                h, size = hashlib.sha256(), 0
                with urllib.request.urlopen(url, timeout=180) as response:
                    assert response.status == 200
                    for chunk in iter(lambda: response.read(1024 * 1024), b''):
                        h.update(chunk)
                        size += len(chunk)
                assert h.hexdigest() == state['assets'][name]['sha256']
                assert size == state['assets'][name]['size']
                verified[name] = {'sha256': h.hexdigest(), 'size': size, 'url': url}
                print('Public download SHA-256 verified:', name, flush=True)
                break
            except Exception:
                if attempt == 4:
                    raise
                time.sleep(5 * (attempt + 1))
    state.update(status='public-downloads-verified', public_downloads=verified)
    save_state(state)


{'prepare': prepare, 'draft': draft, 'publish': publish, 'verify': verify_public}[sys.argv[1]]()
