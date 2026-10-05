"""Sequential rotated product rounds. Never run two timing proxies concurrently."""
from contextlib import contextmanager
import fcntl
import os
from pathlib import Path
import tempfile
from . import analysis, native, study
from .model import fingerprint, read, require, validate_suite, write


@contextmanager
def exclusive_run():
    # Advisory lock for this suite, not protection against unrelated processes.
    path = Path(tempfile.gettempdir()) / ('cgbench-' + str(os.getuid()) + '.lock')
    require(not path.is_symlink(), 'Invalid lock path')
    with path.open('a') as lock:
        try:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            raise ValueError('Another cgbench run is active; run comparisons sequentially')
        try:
            yield
        finally:
            fcntl.flock(lock, fcntl.LOCK_UN)


def run(manifest, work):
    require(manifest.get('schema') == 1, 'Invalid matrix manifest')
    # Complete this before creating output or starting any native process.
    study_plan = study.preflight(manifest, work) if 'study' in manifest else None
    suite = validate_suite(read(manifest['suite']))
    products = manifest.get('products')
    require(isinstance(products, list) and 1 <= len(products) <= 8, 'Bounded product set required')
    identifiers = [p.get('id') for p in products]
    require(all(isinstance(i, str) and i.replace('-', '').isalnum() and len(i) <= 64 for i in identifiers)
            and len(set(identifiers)) == len(identifiers), 'Invalid product IDs')
    rounds, samples, warmups = (manifest.get(k, v) for k, v in [('rounds', 3), ('samples', 4), ('warmups', 2)])
    require(all(type(v) is int for v in [rounds, samples, warmups]) and 1 <= rounds <= 10 and 1 <= samples <= 100 and 1 <= warmups <= 100, 'Invalid sampling')
    require(rounds * (samples + warmups) * sum(c.get('concurrency', 1) for c in suite['cases']) <= 40000, 'Matrix receipt row budget exceeded')
    work = Path(work).resolve(); require(not work.exists(), 'Preserve prior matrix'); work.mkdir(parents=True, mode=0o700)
    write(work / 'manifest.json', manifest); write(work / 'suite.json', suite)
    if study_plan is not None:
        write(work / 'study-plan.json', study_plan)
    schedule = [identifiers[n % len(products):] + identifiers[:n % len(products)] for n in range(rounds)]
    write(work / 'schedule.json', schedule)
    receipts = {}
    for number, order in enumerate(schedule):
        for identifier in order:
            p = products[identifiers.index(identifier)]
            optional = [Path(p[k]).resolve() if p.get(k) else None for k in ['assets', 'geo_db', 'sqlite']]
            value = native.run(suite, Path(p['artifact']).resolve(), Path(manifest['java']).resolve(),
                               Path(manifest['runtime']).resolve(), work / f'round-{number}-{identifier}',
                               1, samples, p['adapter'], *optional, warmups, number)
            if study_plan is not None:
                require(value['artifact_sha256'] == study_plan['artifact_sha256'][identifier], 'Study artifact changed after preflight')
                if identifier in study_plan['dependency_sha256']:
                    require(value.get('asset_sha256') == study_plan['dependency_sha256'][identifier], 'Study dependencies changed after preflight')
                require(value.get('environment', {}).get('runtime_sha256') == study_plan['protocol']['runtime_sha256'], 'Study runtime changed after preflight')
                require(all(value.get('input_sha256', {}).get('cgbench/' + name) == digest for name, digest in study_plan['runner_input_sha256'].items())
                        and value.get('input_sha256', {}).get('java/LoopbackSecurityManager.java') == study_plan['transport_guard_sha256'],
                        'Study runner changed after preflight')
                value['study_plan_sha256'] = fingerprint(study_plan)
            value['sampling']['rounds'] = rounds
            if identifier not in receipts:
                receipts[identifier] = value
            else:
                target = receipts[identifier]
                for key in ['environment_sha256', 'adapter_sha256', 'artifact_sha256', 'profile', 'cache_policy']:
                    require(target[key] == value[key], 'Conditions changed within the matrix: ' + key)
                for key in ['rows', 'logs', 'environment_errors', 'config_sha256', 'recovery_proofs']:
                    target[key].extend(value[key])
            # Durable progress even if a subsequent artifact fails to start.
            write(work / f'round-{number}-{identifier}.json', value)
            print(f'Completed round {number + 1}/{rounds}: {identifier}', flush=True)
    for identifier, value in receipts.items():
        value['matrix_schedule_sha256'] = fingerprint(schedule)
        value['matrix_order'] = schedule
        write(work / (identifier + '.json'), value)
        write(work / (identifier + '-summary.json'), analysis.summarize(value, suite))
    return receipts, suite
