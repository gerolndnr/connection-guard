import hashlib
import ipaddress
import json
import math
from pathlib import Path


class Invalid(ValueError):
    pass


def require(value, message):
    if not value:
        raise Invalid(message)


def sha(path):
    digest = hashlib.sha256()
    with Path(path).open('rb') as source:
        for chunk in iter(lambda: source.read(65536), b''):
            digest.update(chunk)
    return digest.hexdigest()


def fingerprint(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, separators=(',', ':'), allow_nan=False).encode()).hexdigest()


def _pairs(pairs):
    result = {}
    for key, value in pairs:
        require(key not in result, 'Duplicate JSON key')
        result[key] = value
    return result


def read(path):
    path = Path(path)
    require(not path.is_symlink() and path.is_file() and path.stat().st_size <= 32 * 1024 * 1024, 'Invalid JSON file')
    return json.loads(path.read_text(encoding='utf-8'), object_pairs_hook=_pairs,
                      parse_constant=lambda value: (_ for _ in ()).throw(Invalid('Non-finite number')))


def write(path, value):
    path = Path(path)
    require(not path.exists(), 'Output exists; preserve the previous run')
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open('x', encoding='utf-8') as out:
        json.dump(value, out, indent=2, allow_nan=False)
        out.write('\n')


def validate_suite(suite):
    require(suite.get('schema') == 1 and suite.get('kind') == 'controlled', 'Expected a controlled suite')
    cases = suite.get('cases')
    require(isinstance(cases, list) and 1 <= len(cases) <= 128, 'Case count outside bounds')
    ids = set()
    for case in cases:
        require(isinstance(case, dict) and set(case) <= {'id', 'track', 'ip', 'fixture', 'expected', 'expected_core', 'request_limit', 'concurrency', 'cache_recovery', 'retry_after_seconds', 'expected_source_reason'}, 'Unknown case fields')
        name = case.get('id', '')
        require(isinstance(name, str) and name.replace('_', '').isalnum() and 1 <= len(name) <= 64 and name not in ids, 'Invalid/duplicate case ID')
        ids.add(name)
        require(case.get('track') in {'provider', 'rules', 'geo', 'cache', 'concurrency'}, 'Invalid track')
        require(case.get('expected') in {'ALLOW', 'DENY', 'POSITIVE', 'NEGATIVE', 'UNKNOWN', 'GB'}, 'Invalid controlled oracle')
        require('expected_core' not in case or case['expected_core'] in {'POSITIVE', 'NEGATIVE', 'UNKNOWN', 'GB'}, 'Invalid core oracle')
        address = ipaddress.ip_address(case.get('ip', ''))
        # Fixed subject literals only. Every actual socket destination remains loopback.
        v4 = [ipaddress.ip_address('81.2.69.142') + i for i in range(16)]
        native = address in v4 or str(address) in {'2001:218::', '2001:218::1'}
        mapped = address.ipv4_mapped if address.version == 6 else None
        documented = address in ipaddress.ip_network('192.0.2.0/24') if address.version == 4 else address in ipaddress.ip_network('2001:db8::/32')
        require(native or documented or mapped is not None and (mapped in v4 or mapped in ipaddress.ip_network('192.0.2.0/24')),
                'Only fixed controlled subject literals are accepted')
        require(type(case.get('concurrency', 1)) is int and 1 <= case.get('concurrency', 1) <= 32, 'Concurrency outside bounds')
        require(type(case.get('request_limit', 1)) is int and 0 <= case.get('request_limit', 1) <= 64, 'Invalid request limit')
        require(case.get('fixture') in {'positive', 'negative', '503_open', '503_closed', '429_open', '429_closed', 'timeout_open', 'timeout_closed',
                'malformed_open', 'malformed_closed', 'missing_open', 'missing_closed', 'slow_positive', 'manual_deny', 'manual_allow', 'deny_allow_conflict', 'expiry',
                'geo_gb', 'geo_block_gb', 'geo_allow_gb', 'geo_empty_whitelist', 'no_cache', 'warm_cache', 'warm_negative_cache', 'shared_positive', 'unique_positive'}, 'Unknown fixture')
        recovery = case.get('cache_recovery', False)
        require(type(recovery) is bool, 'Recovery flag must be boolean')
        require(not recovery or case['fixture'].split('_')[0] in {'429', 'malformed', 'missing'} and case.get('concurrency', 1) == 1,
                'Recovery probes require a qualified single-client source failure')
        retry = case.get('retry_after_seconds')
        require(retry is None or type(retry) is int and 1 <= retry <= 5 and recovery and case['fixture'].startswith('429'), 'Invalid bounded retry pause')
        require(not recovery or not case['fixture'].startswith('429') or retry is not None, 'Rate-limit recovery needs an explicit retry pause')
        reason = case.get('expected_source_reason')
        require(reason is None or case.get('expected_core') == 'UNKNOWN' and reason in {'RATE_LIMIT', 'INVALID_RESPONSE'}, 'Invalid typed-source reason')
    return suite


def validate_result(result):
    require(result.get('schema') == 1 and result.get('kind') == 'measured', 'Expected measured receipt, never a simulated ranking')
    for key in ('suite_sha256', 'adapter_sha256', 'artifact_sha256', 'environment_sha256'):
        value = result.get(key, '')
        require(isinstance(value, str) and len(value) == 64 and all(c in '0123456789abcdef' for c in value), 'Missing evidence hash: ' + key)
    require(result.get('layer') in {'native_velocity_login_gate', 'core_lookup'}, 'Unknown measurement layer')
    require(isinstance(result.get('profile'), str) and isinstance(result.get('cache_policy'), str) and isinstance(result.get('sampling'), dict), 'Missing measurement conditions')
    for key, limit in [('rounds', 10), ('samples', 100), ('warmups', 100)]:
        require(type(result['sampling'].get(key)) is int and 1 <= result['sampling'][key] <= limit, 'Invalid sampling plan')
    if 'environment' in result:
        require(fingerprint(result['environment']) == result['environment_sha256'], 'Environment hash does not match')
    rows = result.get('rows')
    require(isinstance(rows, list) and len(rows) <= 50000, 'Invalid rows')
    ids = set()
    for row in rows:
        require(isinstance(row, dict) and isinstance(row.get('case_id'), str), 'Invalid case row')
        require(type(row.get('round')) is int and 0 <= row['round'] < result['sampling']['rounds'], 'Invalid round')
        require(type(row.get('sample')) is int and 0 <= row['sample'] <= 3200 and row.get('phase') in {'setup', 'warmup', 'measure'}, 'Invalid sample')
        require(row.get('status') in {'measured', 'unsupported', 'error'}, 'Invalid measurement status')
        identity = (row.get('case_id'), row.get('round'), row.get('phase'), row.get('sample'))
        require(identity not in ids, 'Duplicate sample')
        ids.add(identity)
        if row['status'] == 'measured':
            require(row.get('outcome') in {'ALLOW', 'DENY', 'POSITIVE', 'NEGATIVE', 'UNKNOWN', 'GB', 'TIMEOUT', 'PROTOCOL_ERROR'}, 'Invalid measured outcome')
            require(type(row.get('duration_ns')) is int and 0 <= row['duration_ns'] < 120_000_000_000, 'Invalid measured duration')
            require(type(row.get('requests')) is int and row['requests'] >= 0 or row.get('requests') is None, 'Invalid request count')
            if 'source_reasons' in row:
                require(isinstance(row['source_reasons'], list) and len(row['source_reasons']) <= 128 and all(isinstance(reason, str) and reason.replace('_', '').isalpha() and reason.isupper() for reason in row['source_reasons']), 'Invalid typed-source reasons')
            if row.get('batch_requests') is not None:
                require(type(row['batch_requests']) is int and row['batch_requests'] >= 0 and isinstance(row.get('batch_id'), str), 'Invalid batch count')
    for error in result.get('environment_errors', []):
        require(isinstance(error, dict) and isinstance(error.get('case_id'), str) and isinstance(error.get('reason'), str), 'Invalid environment failure')
    proofs = result.get('recovery_proofs', [])
    require(isinstance(proofs, list) and len(proofs) <= 1280, 'Invalid recovery evidence')
    proof_ids = set()
    for proof in proofs:
        require(isinstance(proof, dict) and isinstance(proof.get('case_id'), str), 'Invalid recovery case')
        number = proof.get('round')
        require(type(number) is int and 0 <= number < result['sampling']['rounds'], 'Invalid recovery round')
        identity = (proof['case_id'], number)
        require(identity not in proof_ids, 'Duplicate recovery proof'); proof_ids.add(identity)
        require(proof.get('cache_retained') is True, 'Recovery must retain the cache')
        for key in ['initial_source_calls', 'positive_source_calls']:
            require(type(proof.get(key)) is int and 0 <= proof[key] <= 1000, 'Invalid recovery source count')
        require(type(proof.get('recovery_wait_ms')) is int and 0 <= proof['recovery_wait_ms'] <= 6000, 'Invalid recovery wait')
        paused = proof.get('paused', [])
        require(isinstance(paused, list) and len(paused) in {0, 3}, 'Invalid retry-pause proof')
        for observation in [proof.get('recovered'), proof.get('cached_replay'), *paused]:
            require(isinstance(observation, dict) and observation.get('outcome') in {'ALLOW', 'DENY', 'TIMEOUT', 'PROTOCOL_ERROR'}, 'Invalid recovery outcome')
            require(type(observation.get('requests')) is int and 0 <= observation['requests'] <= 64, 'Invalid recovery requests')
            require(type(observation.get('duration_ns')) is int and 0 <= observation['duration_ns'] < 120_000_000_000, 'Invalid recovery duration')
    return result
