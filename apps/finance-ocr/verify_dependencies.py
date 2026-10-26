"""Build-time only: verify a downloaded wheelhouse against official PyPI and emit a hash lock."""
import argparse
from concurrent.futures import ThreadPoolExecutor, as_completed
from email.parser import BytesParser
import hashlib
import json
from pathlib import Path
import re
import urllib.request
import zipfile


def verify(wheel):
    with zipfile.ZipFile(wheel) as archive:
        names = [name for name in archive.namelist() if name.endswith('.dist-info/METADATA')]
        if len(names) != 1:
            raise ValueError('Expected one wheel metadata document')
        metadata = BytesParser().parsebytes(archive.read(names[0]))
    name, version = metadata['Name'], metadata['Version']
    if not re.fullmatch(r'[A-Za-z0-9_.-]+', name) or not re.fullmatch(r'[A-Za-z0-9_.+-]+', version):
        raise ValueError('Invalid package identity')
    with urllib.request.urlopen(f'https://pypi.org/pypi/{name}/{version}/json', timeout=30) as response:
        official = json.load(response)
    sha = hashlib.sha256(wheel.read_bytes()).hexdigest()
    matching = [item for item in official['urls'] if item['filename'] == wheel.name and item['digests']['sha256'] == sha and not item.get('yanked')]
    if len(matching) != 1 or official.get('vulnerabilities'):
        raise ValueError('Wheel differs from official PyPI or has reported vulnerabilities: ' + name)
    hashes = sorted({item['digests']['sha256'] for item in official['urls'] if item['packagetype'] == 'bdist_wheel' and not item.get('yanked')})
    if not hashes:
        raise ValueError('No official wheels for pinned package')
    return name, version, sha, hashes


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--wheelhouse', type=Path, required=True)
    parser.add_argument('--lock', type=Path, required=True)
    arguments = parser.parse_args()
    wheels = sorted(arguments.wheelhouse.glob('*.whl'))
    if not wheels or any(path.is_symlink() for path in wheels):
        raise ValueError('Expected downloaded regular wheels')
    results, failures = [], []
    with ThreadPoolExecutor(max_workers=4) as executor:
        pending = {executor.submit(verify, wheel): wheel for wheel in wheels}
        for future in as_completed(pending):
            try:
                results.append(future.result())
            except Exception as error:
                failures.append(pending[future].name + ': ' + str(error))
    if failures:
        raise ValueError('Official wheel verification failed: ' + '; '.join(sorted(failures)))
    normalized = [name.lower().replace('_', '-') for name, *_ in results]
    if len(normalized) != len(set(normalized)):
        raise ValueError('Duplicate package version in wheelhouse')
    lines = ['# Generated from downloaded wheels verified against official https://pypi.org release SHA256.',
             '# Includes official wheel hashes for other platforms; do not install unpinned dependencies.',
             '--only-binary=:all:', '--require-hashes', '']
    for name, version, _sha, hashes in sorted(results, key=lambda item: item[0].lower()):
        lines.append(f'{name}=={version} ' + chr(92))
        lines.extend('    --hash=sha256:' + digest + (' ' + chr(92) if index < len(hashes) - 1 else '') for index, digest in enumerate(hashes))
    with arguments.lock.open('x', encoding='utf-8', newline='\n') as stream:
        stream.write('\n'.join(lines) + '\n')
    print(json.dumps({'verifiedOfficialWheels': len(results), 'packages': [{'name': name, 'version': version, 'sha256': sha} for name, version, sha, _ in results]}, sort_keys=True))


if __name__ == '__main__':
    main()
