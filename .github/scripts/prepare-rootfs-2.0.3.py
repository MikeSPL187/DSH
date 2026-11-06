#!/usr/bin/env python3
"""Prepare the tested rootfs 2.0.3 as a private draft. This script cannot publish."""
import base64
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys

os.umask(0o077)
ROOT = Path(os.environ['ROOTFS_RELEASE_DIR'])
ROOT.mkdir(parents=True, exist_ok=True)
REPO = 'MikeSPL187/DSH'
TAG = 'rootfs-v2.0.3'
SOURCE = '20f21bc6e7e2e177a372ca200b853766bdee071b'
APK_SOURCE = '66bf8dc9f9f9acda99d9b2af2fdb431744af6fe0'
RUN = 42257899221
ARTIFACT = 13523299434
ZIP_SHA = 'e096d4e366546b763909e0b8a93f364ee24ffade185ad2583fd7c302f911028e'
PINS = {
    'rootfs-x86_64.bin': 'c7022c8692c02928302d047182df10e9421c05c78f7cad2e121882b9ae84c886',
    'rootfs-arm64-v8a.bin': '4f27be41b3ac58e09145ff0752e3cfe6fe70a1d89611693d5f4dad8365febbb8',
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
                if str(json.loads(result.stdout).get('status')) == '404':
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
    existing = api('releases/tags/' + TAG, allow_missing=True)
    if existing is not None:
        assert existing['draft'] and existing['prerelease'], 'Release already public; do not alter it'
        assert existing['target_commitish'] == SOURCE
    tag = api('git/ref/tags/' + TAG, allow_missing=True)
    if tag is not None:
        obj = tag['object']
        while obj['type'] == 'tag':
            obj = api('git/tags/' + obj['sha'])['object']
        assert obj['type'] == 'commit' and obj['sha'] == SOURCE, 'Existing tag targets another revision'
    run = api('actions/runs/' + str(RUN))
    assert run['head_sha'] == SOURCE and run['conclusion'] == 'success'
    native = api('actions/runs/42261945428')
    apk = api('actions/runs/42261945444')
    assert native['head_sha'] == apk['head_sha'] == APK_SOURCE
    assert native['conclusion'] == apk['conclusion'] == 'success'
    artifact = api('actions/artifacts/' + str(ARTIFACT))
    assert not artifact['expired'] and artifact['name'] == 'rootfs-2.0.3-candidate'
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
    assert hashes['rootfs-manifest.json'] == 'c01d7cfc3a93af9227fac84fda43079689aa2d49c0916f50fa13d9430f9f7b4a'
    assert hashes['sbom-arm64.tar.gz'] == '16fb8c017cf561053f2383a92f9a55eb40329f52b873ae7597d84592b73e499e'
    assert hashes['sbom-x86_64.tar.gz'] == 'c04eb15484cd22d0aceaca1846d045602929a2ce7005a88cf7c5dc2d767a0406'
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
    release = api('releases/tags/' + TAG, allow_missing=True)
    if release is None:
        release = api('releases', {
            'tag_name': TAG, 'target_commitish': SOURCE, 'draft': True, 'prerelease': True, 'make_latest': 'false',
            'name': 'Rootfs v2.0.3 — tested QA candidate',
            'body': 'Неопубликованный кандидат Linux для DSH 1.1.4. Точные проверенные файлы CI 42257899221 (source ' + SOURCE + '), без пересборки. Debian 13, Node 24, Python 3, git, curl; исправления dpkg/PRoot, отмены серверов и заголовков. Android 11/13/15: установка, shell, пакеты, MCP и proxy — PASS (run 42261945428). ARM-образ проверен в CI; Android ARM-устройство недоступно. Приложение принимает только SHA-256 из rootfs-manifest.json. Публикация требует отдельного разрешения владельца.'
        })
    assert release['draft'] and release['tag_name'] == TAG
    assert release['target_commitish'] == SOURCE
    state['release_id'] = release['id']
    state['status'] = 'draft-created'
    save_state(state)
    existing = {a['name']: a for a in release['assets']}
    assert set(existing) <= ASSETS
    for name, asset in existing.items():
        assert asset['state'] == 'uploaded'
        assert asset['size'] == state['assets'][name]['size']
        assert asset['digest'] == 'sha256:' + state['assets'][name]['sha256']
    missing = ASSETS - set(existing)
    if missing:
        gh('release', 'upload', TAG, '--repo', REPO,
           *[str(ROOT / 'release-assets' / name) for name in sorted(missing)])
    release = api('releases/' + str(release['id']))
    assert release['draft']
    validate_assets(release, state)
    state['status'] = 'draft-assets-verified'
    save_state(state)
    print('Draft assets verified:', len(release['assets']), flush=True)



{'prepare': prepare, 'draft': draft}[sys.argv[1]]()
