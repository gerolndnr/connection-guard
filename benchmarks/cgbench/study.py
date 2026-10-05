"""Predeclare bounded native latency studies before starting any proxy."""
import os
from pathlib import Path
import shutil
from .model import fingerprint, read, require, sha, validate_suite


def _hash(value):
    return isinstance(value, str) and len(value) == 64 and all(c in '0123456789abcdef' for c in value)


def preflight(manifest, work):
    require(manifest.get('schema') == 1, 'Invalid matrix manifest')
    protocol = read(manifest['study'])
    require(isinstance(protocol, dict), 'Study protocol must be an object')
    require(set(protocol) == {'schema', 'kind', 'id', 'suite_sha256', 'primary_case', 'sampling',
                             'products', 'runtime_sha256', 'host', 'acceptance', 'scope'}, 'Unknown study protocol fields')
    require(protocol['schema'] == 1 and protocol['kind'] == 'native_latency_study' and
            protocol['id'] == 'cg-latency-v1', 'Unqualified study protocol')
    suite = validate_suite(read(manifest['suite']))
    require(_hash(protocol['suite_sha256']) and fingerprint(suite) == protocol['suite_sha256'], 'Study dataset changed')
    require(protocol['primary_case'] in {c['id'] for c in suite['cases']}, 'Unknown primary endpoint')
    require(protocol['sampling'] == {'rounds': 6, 'samples': 30, 'warmups': 20}, 'Sampling protocol changed; preregister a new study')
    require(all(type(manifest.get(k)) is int and manifest[k] == v for k, v in protocol['sampling'].items()),
            'Study requires six rounds, thirty measured batches and twenty warmups')
    declared = protocol['products']
    products = manifest.get('products')
    require(isinstance(products, list) and isinstance(declared, list) and len(products) == len(declared) == 3,
            'Stable, candidate and qualified HTTP comparator are required')
    require([p.get('id') for p in products] == [p.get('id') for p in declared] ==
            ['cg-stable', 'cg-candidate', 'georestrict'], 'Study product order/IDs changed')
    artifacts = {}
    dependencies = {}
    for product, expected in zip(products, declared):
        require(set(expected) == {'id', 'adapter', 'artifact_sha256'}, 'Unexpected declared product fields')
        require(product.get('adapter') == expected['adapter'], 'Study adapter changed')
        path = Path(product['artifact'])
        require(path.is_file() and not path.is_symlink(), 'Study artifact must be a regular reviewed JAR')
        actual = sha(path)
        require(_hash(product.get('artifact_sha256')) and product['artifact_sha256'] == actual,
                'Study artifact differs from its declared hash')
        require(expected['artifact_sha256'] is None and product['id'] == 'cg-candidate' or
                _hash(expected['artifact_sha256']) and actual == expected['artifact_sha256'], 'Pinned stable/comparator artifact changed')
        artifacts[product['id']] = actual
        if product['adapter'] == 'connection-guard':
            require(product.get('assets'), 'CG dependency cache must be explicit')
            files = sorted(Path(product['assets']).rglob('*.jar'))
            require(files and all(f.is_file() and not f.is_symlink() for f in files), 'Invalid/empty CG dependency cache')
            dependencies[product['id']] = {f.relative_to(product['assets']).as_posix(): sha(f) for f in files}
    require(artifacts['cg-stable'] != artifacts['cg-candidate'], 'Candidate must be a distinct pinned artifact')
    require(dependencies['cg-stable'] == dependencies['cg-candidate'], 'CG dependency conditions differ')
    runtime = Path(manifest['runtime'])
    require(runtime.is_file() and not runtime.is_symlink() and sha(runtime) == protocol['runtime_sha256'], 'Study runtime changed')
    host = protocol['host']
    require(host == {'min_free_bytes': 6442450944, 'max_load_per_logical_cpu': 0.25}, 'Host preflight protocol changed')
    parent = Path(work).resolve()
    require(not parent.exists(), 'Preserve earlier study output')
    while not parent.exists(): parent = parent.parent
    free = shutil.disk_usage(parent).free
    require(free >= host['min_free_bytes'], 'Study needs at least 6 GiB free; preserve existing evidence')
    cores = os.cpu_count() or 1
    load = list(os.getloadavg())
    require(max(load[:2]) / cores <= host['max_load_per_logical_cpu'], 'Host start load exceeds study threshold; defer native timing')
    require(protocol['acceptance'] == {
        'primary_metric': 'round_p95_client_login_ms', 'minimum_primary_reduction_fraction': 0.20,
        'minimum_improved_rounds': 5, 'secondary_max_regression_fraction': 0.10,
        'replicate_entire_study': True, 'all_functional_and_budget_gates_pass': True,
        'bootstrap_unit': 'round', 'bootstrap_seed': 20261005}, 'Acceptance criteria changed; preregister a new study')
    inputs = {p.name: sha(p) for p in sorted(Path(__file__).parent.glob('*.py'))}
    return dict(schema=1, kind='preregistered_native_latency_plan', protocol=protocol,
                protocol_sha256=fingerprint(protocol), manifest_sha256=fingerprint(manifest),
                suite_sha256=fingerprint(suite), artifact_sha256=artifacts,
                dependency_sha256=dependencies, runner_input_sha256=inputs,
                transport_guard_sha256=sha(Path(__file__).parents[1] / 'java/LoopbackSecurityManager.java'),
                host_at_preflight=dict(logical_cpus=cores, load_average=load, free_bytes=free),
                planned_proxies=6 * len(suite['cases']) * len(products),
                planned_measured_logins=6 * 30 * sum(c.get('concurrency', 1) for c in suite['cases']) * len(products),
                planned_warmup_logins=6 * 20 * sum(c.get('concurrency', 1) for c in suite['cases']) * len(products),
                runtime_started=False, timing_study_completed=False,
                idle_throughout_run_verified=False, production_accuracy_tested=False)
