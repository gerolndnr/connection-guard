#!/usr/bin/env python3
"""Actual candidate JAR only; synthetic signed Intel and loopback provider fixtures."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess

parser = argparse.ArgumentParser()
parser.add_argument('--artifact', type=Path, required=True)
parser.add_argument('--java', type=Path, required=True)
parser.add_argument('--work-dir', type=Path, required=True)
args = parser.parse_args()
artifact = args.artifact.resolve(strict=True)
java = args.java.resolve(strict=True)
work = args.work_dir.resolve()
work.mkdir(parents=True, exist_ok=True)
classes = work / 'classes'
classes.mkdir(exist_ok=True)
source = Path(__file__).with_name('Intel061Fixture.java').resolve()
env = dict(os.environ, CONNECTIONGUARD_CLOUD='false', CONNECTIONGUARD_INTEL_REFRESH='false')
with (work / 'console.log').open('w') as log:
    subprocess.run([str(java.with_name('javac')), '-cp', str(artifact), '-d', str(classes), str(source)], env=env, stdout=log, stderr=subprocess.STDOUT, check=True, timeout=45)
    subprocess.run([str(java), '-cp', os.pathsep.join((str(artifact), str(classes))), 'com.github.gerolndnr.connectionguard.core.local.Intel061Fixture', str(work)], env=env, stdout=log, stderr=subprocess.STDOUT, check=True, timeout=45)
result = json.loads((work / 'result.json').read_text())
assert result['status'] == 'passed' and result['cases'] == 10
result.update(artifact_sha256=hashlib.sha256(artifact.read_bytes()).hexdigest(), fixture_source_sha256=hashlib.sha256(source.read_bytes()).hexdigest())
(work / 'result.json').write_text(json.dumps(result, indent=2) + '\n')
print(json.dumps(result, indent=2))
