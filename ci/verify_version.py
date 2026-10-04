#!/usr/bin/env python3
"""Reject an omitted release merge, an old version, or changed code reusing a released version."""
import argparse
import json
from pathlib import Path
import re
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]

def verify(repository):
    def git(*args, allowed=(0,)):
        result = subprocess.run(['git', *args], cwd=repository, capture_output=True, text=True, timeout=20)
        if result.returncode not in allowed:
            raise ValueError('Cannot verify release history; fetch complete repository history and tags.')
        return result.returncode, result.stdout.strip()
    _, shallow = git('rev-parse', '--is-shallow-repository')
    if shallow != 'false':
        raise ValueError('Version verification requires complete history and release tags.')
    match = re.search(r'^version\s*=\s*"([0-9]+\.[0-9]+\.[0-9]+(?:-SNAPSHOT)?)"\s*$',
                      (repository/'build.gradle.kts').read_text(), re.MULTILINE)
    if match is None:
        raise ValueError('Use a numeric release version or a numeric -SNAPSHOT development version.')
    version = match.group(1)
    number = tuple(map(int, version.removesuffix('-SNAPSHOT').split('.')))
    _, tags = git('tag', '--list')
    releases = [tag for tag in tags.splitlines() if re.fullmatch(r'[0-9]+\.[0-9]+\.[0-9]+', tag)]
    if not releases:
        raise ValueError('No stable release tags available; fetch tags before version verification.')
    latest = max(releases, key=lambda tag: tuple(map(int, tag.split('.'))))
    _, latest_commit = git('rev-parse', 'refs/tags/'+latest+'^{commit}')
    _, head = git('rev-parse', 'HEAD')
    ancestor, _ = git('merge-base', '--is-ancestor', latest_commit, head, allowed=(0, 1))
    if ancestor != 0:
        raise ValueError('Latest stable release '+latest+' is missing from this branch history; merge its hotfix first.')
    latest_number = tuple(map(int, latest.split('.')))
    if number < latest_number:
        raise ValueError('Build version '+version+' is older than stable release '+latest+'.')
    if number == latest_number and (head != latest_commit or version.endswith('-SNAPSHOT')):
        raise ValueError('Changed development source must use a new version; '+latest+' already identifies an immutable release.')
    return {'version': version, 'latest_stable_tag': latest, 'latest_stable_commit': latest_commit,
            'source_commit': head, 'latest_stable_is_ancestor': True}

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--repository', type=Path, default=ROOT)
    args = parser.parse_args()
    print(json.dumps(verify(args.repository.resolve()), indent=2))

if __name__ == '__main__':
    try:
        main()
    except (ValueError, OSError, subprocess.SubprocessError) as error:
        print('Version verification failed: '+str(error), file=sys.stderr)
        sys.exit(1)
