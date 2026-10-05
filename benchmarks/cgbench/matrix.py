"""Sequential rotated product rounds. Never run two timing proxies concurrently."""
from contextlib import contextmanager
import fcntl
import os
from pathlib import Path
import tempfile
from . import analysis, native
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
            value['sampling']['rounds'] = rounds
            if identifier not in receipts:
                receipts[identifier] = value
            else:
                target = receipts[identifier]
                for key in ['environment_sha256', 'adapter_sha256', 'artifact_sha256', 'profile', 'cache_policy']:
                    require(target[key] == value[key], 'Conditions changed within the matrix: ' + key)
                for key in ['rows', 'logs', 'environment_errors', 'config_sha256']:
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
