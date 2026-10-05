import json
import os
import platform
import subprocess
from pathlib import Path
from .artifacts import inspect_jar
from .model import fingerprint, require, sha


def run(suite, artifact, java, work, rounds=3, samples=4):
    require(1 <= rounds <= 10 and 1 <= samples <= 100, 'Bounded sampling required')
    artifact = Path(artifact).resolve(); info = inspect_jar(artifact)
    require(info['descriptor']['id'] == 'connection-guard', 'This adapter only measures Connection Guard')
    java = Path(java).resolve(); javac = java.with_name('javac')
    version = subprocess.run([str(java), '-version'], capture_output=True, text=True, timeout=10, check=True).stderr
    work = Path(work).resolve(); require(not work.exists(), 'Preserve prior runs'); work.mkdir(parents=True, mode=0o700)
    source = Path(__file__).resolve().parents[1] / 'java/CoreLookupBenchmark.java'
    classes = work / 'classes'; classes.mkdir()
    subprocess.run([str(javac), '--release', '17', '-proc:none', '-cp', str(artifact), '-d', str(classes), str(source)],
                   check=True, timeout=30)
    data = work / 'suite.json'; data.write_text(json.dumps(suite))
    environment = dict(java=version, os=platform.platform(), machine=platform.machine(), cores=os.cpu_count(),
                       heap='64m/256m', sources='owned_typed_futures', lookup='350/200/4/32/64', fixture_delay_ms=50)
    child_environment = {name: os.environ[name] for name in ['PATH', 'LANG', 'JAVA_HOME'] if name in os.environ}
    child_environment['CONNECTIONGUARD_CLOUD'] = 'false'
    measured = subprocess.run([str(java), '-Xms64m', '-Xmx256m', '-cp', str(classes) + os.pathsep + str(artifact),
                               'CoreLookupBenchmark', str(data), str(rounds), str(samples)],
                              cwd=work, env=child_environment, capture_output=True, text=True, timeout=180)
    (work / 'stderr.log').write_text(measured.stderr)
    require(measured.returncode == 0, 'Core fixture failed; inspect private stderr.log')
    require(len(measured.stdout.encode()) <= 16 * 1024 * 1024, 'Oversized fixture result')
    rows = json.loads(measured.stdout)
    return dict(schema=1, kind='measured', product='connection-guard', version=info['descriptor']['version'],
                suite_sha256=fingerprint(suite), adapter_sha256=fingerprint({'python': sha(__file__), 'java': sha(source)}),
                artifact_sha256=sha(artifact), environment_sha256=fingerprint(environment), environment=environment,
                layer='core_lookup', profile='controlled_typed_source', cache_policy='disabled',
                sampling=dict(rounds=rounds, samples=samples, warmups=1), rows=rows,
                stderr_sha256=sha(work / 'stderr.log'), native_login_tested=False, http_parser_tested=False,
                live_accuracy_tested=False, real_account_authentication_tested=False)
