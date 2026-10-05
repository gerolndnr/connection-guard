import json
import re
import ssl
import urllib.request
import zipfile
from pathlib import Path
from .model import require, sha, write


def inspect_jar(path):
    path = Path(path)
    require(not path.is_symlink(), 'Symlink artifact rejected')
    path = path.resolve()
    require(path.is_file() and path.stat().st_size <= 64 * 1024 * 1024, 'Invalid plugin JAR')
    with zipfile.ZipFile(path) as jar:
        require(sum(entry.file_size for entry in jar.infolist()) <= 256 * 1024 * 1024, 'Oversized expanded JAR')
        require(jar.testzip() is None, 'Corrupt JAR')
        entry = jar.getinfo('velocity-plugin.json')
        require(entry.file_size <= 32768, 'Invalid Velocity descriptor')
        descriptor = json.loads(jar.read(entry))
        require(re.fullmatch(r'[a-z][a-z0-9_-]{0,63}', descriptor.get('id', '')), 'Invalid Velocity ID')
    return dict(path=str(path), sha256=sha(path), descriptor=descriptor)


def fetch_modrinth(slug, game_version, destination):
    """Explicit public free download. No PAT, commercial plans, or redistribution."""
    require(re.fullmatch('[a-z0-9-]{1,64}', slug) is not None, 'Invalid public project slug')
    destination = Path(destination)
    require(not destination.exists(), 'Preserve existing artifact cache')
    request = urllib.request.Request('https://api.modrinth.com/v2/project/' + slug + '/version',
                                     headers={'User-Agent': 'ConnectionGuardBenchmark/0.1 (public metadata)'})
    # Python.org macOS builds may not have the OS certificate store configured.
    # Keep verification on and use the installed Mozilla root bundle when available.
    try:
        import certifi
        context = ssl.create_default_context(cafile=certifi.where())
    except ImportError:
        context = ssl.create_default_context()
    with urllib.request.urlopen(request, timeout=20, context=context) as response:
        require(response.url.startswith('https://api.modrinth.com/'), 'Unexpected metadata redirect')
        raw = response.read(1024 * 1024 + 1)
    require(len(raw) <= 1024 * 1024, 'Oversized metadata')
    versions = json.loads(raw)
    eligible = [v for v in versions if v.get('version_type') == 'release' and 'velocity' in v.get('loaders', []) and game_version in v.get('game_versions', [])]
    require(eligible, 'No compatible public stable Velocity release; do not substitute another platform')
    selected = max(eligible, key=lambda version: version['date_published'])
    files = [f for f in selected['files'] if f.get('primary')]
    require(len(files) == 1, 'Select an unambiguous primary artifact')
    file = files[0]
    require(file['url'].startswith('https://cdn.modrinth.com/data/') and 0 < file['size'] <= 64 * 1024 * 1024, 'Invalid official download')
    with urllib.request.urlopen(file['url'], timeout=30, context=context) as response:
        require(response.url.startswith('https://cdn.modrinth.com/'), 'Unexpected redirect')
        binary = response.read(file['size'] + 1)
    import hashlib
    require(len(binary) == file['size'] and hashlib.sha512(binary).hexdigest() == file['hashes']['sha512'], 'Official artifact checksum mismatch')
    destination.mkdir(parents=True, mode=0o700)
    jar = destination / 'plugin.jar'; jar.write_bytes(binary)
    receipt = inspect_jar(jar)
    receipt.update(version_id=selected['id'], version=selected['version_number'], published=selected['date_published'], source=file['url'], slug=slug)
    write(destination / 'receipt.json', receipt)
    return receipt
