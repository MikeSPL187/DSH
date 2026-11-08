"""Verify installable, separately instrumented benchmark APKs before publishing CI artifacts."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import zipfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, default=Path('.'))
    parser.add_argument('--require-mapping', action='store_true')
    args = parser.parse_args()
    tools = Path(os.environ['ANDROID_HOME'], 'build-tools/35.0.0')
    records = {}
    for role, module in [('target', 'app'), ('controller', 'benchmark')]:
        apk = args.root / module / f'build/outputs/apk/benchmark/{module}-benchmark.apk'
        signature = subprocess.check_output([str(tools / 'apksigner'), 'verify', '--verbose', '--print-certs', str(apk)], text=True)
        badging = subprocess.check_output([str(tools / 'aapt2'), 'dump', 'badging', str(apk)], text=True)
        manifest = subprocess.check_output([str(tools / 'aapt2'), 'dump', 'xmltree', str(apk), '--file', 'AndroidManifest.xml'], text=True)
        package = re.search(r"package: name='([^']+)'", badging).group(1)
        signers = re.findall(r'Signer #\d+ certificate SHA-256 digest: ([0-9a-f]+)', signature)
        if not signers:
            raise RuntimeError(f'{role}: missing signer')
        if role == 'target':
            if package != 'app.dsh.mobile.benchmark' or 'application-debuggable' in badging:
                raise RuntimeError('Benchmark target must be isolated and non-debuggable')
            if not re.search(r':shell\([^)]*\)=true', manifest) or 'PerformanceFixtureActivity' not in manifest:
                raise RuntimeError('Profileable benchmark fixture is missing')
            if os.environ.get('GITHUB_SHA'):
                with zipfile.ZipFile(apk) as archive:
                    dex = (archive.read(name) for name in archive.namelist() if re.fullmatch(r'classes\d*\.dex', name))
                    if not any(os.environ['GITHUB_SHA'].encode() in content for content in dex):
                        raise RuntimeError('Benchmark target source revision is missing')
        else:
            target = re.search(r':targetPackage[^=]*="([^"]+)"', manifest).group(1)
            if target != package or package == 'app.dsh.mobile.benchmark':
                raise RuntimeError('Instrumentation must run in its own package')
        mapping = args.root / module / 'build/outputs/mapping/benchmark/mapping.txt'
        if args.require_mapping and not mapping.is_file():
            raise RuntimeError(f'{role}: missing R8 mapping')
        records[role] = dict(package=package, bytes=apk.stat().st_size,
                             sha256=hashlib.sha256(apk.read_bytes()).hexdigest(), signerSha256=signers,
                             signatureVerified=True, r8MappingPresent=mapping.is_file())
    if records['target']['signerSha256'] != records['controller']['signerSha256']:
        raise RuntimeError('Benchmark APK signing identities differ')
    result = dict(source=os.environ.get('GITHUB_SHA'), artifacts=records,
                  selfInstrumenting=True, physicalExecutionVerified=False)
    (args.root / 'benchmark-verification.json').write_text(json.dumps(result, indent=2) + '\n')
    print(json.dumps(result, indent=2))


if __name__ == '__main__':
    main()
