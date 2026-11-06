"""Prepare and publish a versioned Chronicles release using standard-library tools."""
import argparse
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import subprocess
import tempfile
import zipfile


def require(condition, message):
    if not condition:
        raise ValueError(message)


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def source_identity(source, tag):
    require(re.fullmatch(r'v\d+\.\d+\.\d+', tag), 'Expected a stable vX.Y.Z tag')
    version = tag[1:]
    properties = (source / 'gradle.properties').read_text()
    match = re.search(r'^version\s*=\s*(\S+)\s*$', properties, re.M)
    require(match and match[1] == version, 'Tag differs from gradle.properties version')
    commit = subprocess.check_output(['git', '-C', str(source), 'rev-parse', 'HEAD'], text=True).strip()
    require(re.fullmatch(r'[0-9a-f]{40}', commit), 'Expected a full source commit')
    return version, commit


def entries(path):
    with zipfile.ZipFile(path) as archive:
        names = archive.namelist()
        require(len(names) == len(set(names)), 'Duplicate JAR entries')
        require(archive.testzip() is None, 'Invalid JAR CRC')
        for item in archive.infolist():
            require(not PurePosixPath(item.filename).is_absolute()
                    and '..' not in PurePosixPath(item.filename).parts, 'Unsafe JAR entry')
            require(item.file_size < 32 * 1024 * 1024, 'Unexpectedly large JAR entry')
        return {name: archive.read(name) for name in names if not name.endswith('/')}


def manifest(data):
    text = data.decode().replace('\r\n', '\n').replace('\n ', '')
    return dict(line.split(': ', 1) for line in text.splitlines() if ': ' in line)


def verify_equivalent(approved, built, client_entries=None):
    """Ignore ZIP directory records; accept only a proven client-class manifest list."""
    actual, expected = entries(approved), entries(built)
    require(set(actual) == set(expected), 'Historical JAR file membership differs from the build')
    for name in actual:
        if actual[name] == expected[name]:
            continue
        require(name == 'META-INF/MANIFEST.MF' and client_entries,
                'Historical JAR payload differs: ' + name)
        left, right = manifest(actual[name]), manifest(expected[name])
        attribute = 'Fabric-Loom-Client-Only-Entries'
        client_list = set(filter(None, left.pop(attribute, '').split(';')))
        right.pop(attribute, None)
        require(left == right and client_list == client_entries,
                'Historical JAR manifest differs beyond verified client classes')


def validate_mod(path, version):
    files = entries(path)
    metadata = json.loads(files['fabric.mod.json'])
    require(metadata['id'] == 'wicklen_chronicles' and metadata['version'] == version,
            'Wrong mod identity/version')
    require(any(name.startswith('dev/wicklen/chronicles/core/') and name.endswith('.class')
                for name in files), 'Missing bundled independent core')


def prepare(source, tag, output, notes_dir, historical=False):
    version, commit = source_identity(source, tag)
    names = [f'wicklen-chronicles-{version}.jar', f'wicklen-chronicles-{version}-sources.jar']
    built = source / 'build/libs'
    validate_mod(built / names[0], version)
    notes = notes_dir / (version + '.md')
    require(notes.is_file(), 'Add release notes before publishing: ' + str(notes))
    client_root = source / 'fabric/build/classes/java/client'
    client_entries = {p.relative_to(client_root).as_posix() for p in client_root.rglob('*.class')}
    require(not historical or client_entries, 'Compiled client classes are required for historical verification')
    require(not output.exists() or not any(output.iterdir()), 'Release output must be empty')
    output.mkdir(parents=True, exist_ok=True)
    hashes = {}
    for name in names:
        shutil.copyfile(built / name, output / name)
        hashes[name] = digest(output / name)
    (output / 'SHA256SUMS.txt').write_text(''.join(f'{hashes[n]}  {n}\n' for n in sorted(hashes)))
    hashes['SHA256SUMS.txt'] = digest(output / 'SHA256SUMS.txt')
    (output / 'release-notes.md').write_text(notes.read_text() + f'\nSource commit: `{commit}`.\n')
    data = {'version': version, 'tag': tag, 'commit': commit,
            'approved_draft': historical, 'client_entries': sorted(client_entries), 'files': hashes}
    (output / 'release.json').write_text(json.dumps(data, indent=2) + '\n')
    print(json.dumps({key: data[key] for key in ('tag', 'commit', 'approved_draft', 'files')}, indent=2))


def github(repository, endpoint, allow_missing=False):
    result = subprocess.run(['gh', 'api', f'repos/{repository}/{endpoint}'], capture_output=True, text=True)
    if allow_missing and result.returncode and 'HTTP 404' in result.stderr:
        return None
    require(result.returncode == 0, result.stderr)
    return json.loads(result.stdout)


def find_release(repository, tag):
    release = github(repository, 'releases/tags/' + tag, allow_missing=True)
    if release:
        return release
    # GitHub's by-tag endpoint omits drafts whose tags have not been published.
    page = 1
    while True:
        releases = github(repository, f'releases?per_page=100&page={page}')
        matches = [item for item in releases if item['tag_name'] == tag]
        require(len(matches) <= 1, 'Multiple drafts use the requested release tag')
        if matches:
            return matches[0]
        if len(releases) < 100:
            return None
        page += 1


def publish(output, repository):
    data = json.loads((output / 'release.json').read_text())
    require(re.fullmatch(r'v\d+\.\d+\.\d+', data['tag']), 'Invalid release tag')
    require(data['tag'] == 'v' + data['version'], 'Release version differs')
    require(re.fullmatch(r'[0-9a-f]{40}', data['commit']), 'Invalid release commit')
    expected_names = {f"wicklen-chronicles-{data['version']}.jar",
                      f"wicklen-chronicles-{data['version']}-sources.jar", 'SHA256SUMS.txt'}
    require(set(data['files']) == expected_names, 'Unexpected publish assets')
    for name, expected in data['files'].items():
        require(digest(output / name) == expected, 'Prepared release asset changed: ' + name)
    existing_tag = github(repository, 'git/ref/tags/' + data['tag'], allow_missing=True)
    if existing_tag:
        resolved = github(repository, 'commits/' + data['tag'])
        require(resolved['sha'] == data['commit'], 'Existing tag points elsewhere')
    release = find_release(repository, data['tag'])
    common = ['--repo', repository, '--title', 'Wicklen: Chronicles ' + data['version'],
              '--notes-file', str(output / 'release-notes.md'),
              '--latest=' + ('true' if os.environ.get('MAKE_LATEST') == 'true' else 'false')]
    if data['approved_draft']:
        registry = Path(__file__).resolve().parents[2] / 'docs/releases/historical-artifacts.json'
        record = json.loads(registry.read_text())[data['version']]
        require(record['commit'] == data['commit'], 'Historical source commit differs')
        require(set(record['files']) == expected_names - {'SHA256SUMS.txt'}, 'Unexpected historical asset registry')
        require(release and release['draft'], 'Historical publication requires an unpublished draft')
        require(release['target_commitish'] == data['commit'], 'Draft target commit differs')
        require({a['name'] for a in release['assets']} == expected_names, 'Draft asset membership changed')
        with tempfile.TemporaryDirectory() as directory:
            for asset in release['assets']:
                with (Path(directory) / asset['name']).open('wb') as stream:
                    result = subprocess.run(['gh', 'api', '--header', 'Accept: application/octet-stream',
                                             f"repos/{repository}/releases/assets/{asset['id']}"],
                                            stdout=stream, stderr=subprocess.PIPE)
                require(result.returncode == 0, result.stderr.decode())
            for name, expected in record['files'].items():
                candidate = Path(directory) / name
                require(candidate.stat().st_size == expected['bytes'] and digest(candidate) == expected['sha256'],
                        'Historical artifact hash differs: ' + name)
                verify_equivalent(candidate, output / name,
                                  set(data['client_entries']) if not name.endswith('-sources.jar') else None)
            sums = ''.join(f"{record['files'][n]['sha256']}  {n}\n" for n in sorted(record['files']))
            require((Path(directory) / 'SHA256SUMS.txt').read_text() == sums, 'Historical checksum file differs')
            payload = Path(directory) / 'publish.json'
            payload.write_text(json.dumps({'draft': False, 'name': 'Wicklen: Chronicles ' + data['version'],
                                           'body': (output / 'release-notes.md').read_text(),
                                           'make_latest': 'true' if os.environ.get('MAKE_LATEST') == 'true' else 'false'}))
            subprocess.run(['gh', 'api', '--method', 'PATCH', f"repos/{repository}/releases/{release['id']}",
                            '--input', str(payload), '--silent'], check=True)
    else:
        require(release is None, 'Release already exists; published releases are never overwritten')
        subprocess.run(['gh', 'release', 'create', data['tag'], *[str(output / n) for n in sorted(expected_names)],
                        '--target', data['commit'], *common], check=True)
    print(f"Published https://github.com/{repository}/releases/tag/{data['tag']}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest='command', required=True)
    for name in ('check-source', 'prepare'):
        child = commands.add_parser(name)
        child.add_argument('--source', type=Path, required=True)
        child.add_argument('--tag', required=True)
        if name == 'prepare':
            child.add_argument('--output', type=Path, required=True)
            child.add_argument('--notes-dir', type=Path, required=True)
            child.add_argument('--historical', action='store_true')
    child = commands.add_parser('publish')
    child.add_argument('--output', type=Path, required=True)
    child.add_argument('--repository', required=True)
    args = vars(parser.parse_args())
    command = args.pop('command')
    if command == 'check-source':
        print(source_identity(**args))
    else:
        globals()[command](**args)


if __name__ == '__main__':
    main()
