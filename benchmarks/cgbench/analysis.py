import collections
import math
import random
import statistics
from datetime import datetime
from .model import fingerprint, require, validate_result


def quantile(values, p):
    if not values:
        return None
    values = sorted(values)
    index = (len(values) - 1) * p
    low = int(index)
    return values[low] + (values[min(low + 1, len(values) - 1)] - values[low]) * (index - low)


def wilson(successes, total):
    if not total:
        return None
    z = 1.959963984540054
    rate = successes / total
    denominator = 1 + z * z / total
    center = (rate + z * z / (2 * total)) / denominator
    radius = z * math.sqrt(rate * (1 - rate) / total + z * z / (4 * total * total)) / denominator
    return [max(0, center - radius), min(1, center + radius)]


def summarize(result, suite):
    validate_result(result)
    expected = {case['id']: case for case in suite['cases']}
    for proof in result.get('recovery_proofs', []):
        require(result['layer'] == 'native_velocity_login_gate' and proof['case_id'] in expected and expected[proof['case_id']].get('cache_recovery'), 'Recovery proof outside the measured suite/layer')
    groups = collections.defaultdict(list)
    for row in result['rows']:
        require(row['case_id'] in expected, 'Receipt has an unknown case')
        groups[row['case_id']].append(row)
    cases = []
    for name, definition in expected.items():
        rows = groups[name]
        measured = [r for r in rows if r['status'] == 'measured' and r['phase'] == 'measure']
        timing = [r['duration_ns'] / 1e6 for r in measured]
        actual = collections.Counter(r['outcome'] for r in measured)
        verdict = 'not_tested'
        if measured:
            oracle = definition.get('expected_core') if result['layer'] == 'core_lookup' else definition['expected']
            require(oracle is not None, 'Measurement has no oracle for its actual layer')
            verdict = 'pass' if all(r['outcome'] == oracle for r in measured) else 'fail'
        elif rows:
            verdict = 'unsupported' if all(r['status'] == 'unsupported' for r in rows) else 'error'
        # Requests are counted for an entire batch, so never add them once per concurrent caller.
        batches = {r['batch_id']: r['batch_requests'] for r in measured if r.get('batch_requests') is not None}
        for row in measured:
            if row.get('batch_requests') is not None:
                require(batches[row['batch_id']] == row['batch_requests'], 'Conflicting counts for the same batch')
        request_verdict = None
        if batches:
            request_verdict = all(value <= definition.get('request_limit', definition.get('concurrency', 1)) for value in batches.values())
            if not request_verdict:
                verdict = 'fail'
        required = result['sampling']['rounds'] * result['sampling']['samples'] * definition.get('concurrency', 1)
        complete = len(measured) == required and all(
            sum(r['round'] == number for r in measured) == result['sampling']['samples'] * definition.get('concurrency', 1)
            for number in range(result['sampling']['rounds']))
        problems = [r.get('reason', 'setup_error') for r in rows if r['status'] == 'error']
        problems += [r['reason'] for r in result.get('environment_errors', []) if r['case_id'] == name]
        if problems or measured and not complete:
            verdict = 'error'
        contract_failures = []
        if measured and result['layer'] == 'core_lookup' and definition.get('expected_source_reason'):
            allowed = {definition['expected_source_reason']}
            if definition['fixture'].startswith('429'): allowed.add('CIRCUIT_OPEN')
            if any(not r.get('source_reasons') or not set(r['source_reasons']) <= allowed for r in measured):
                contract_failures.append('unknown_source_reason_mismatch')
        if measured and result['layer'] == 'native_velocity_login_gate' and definition.get('cache_recovery'):
            proofs = [p for p in result.get('recovery_proofs', []) if p['case_id'] == name]
            if {p['round'] for p in proofs} != set(range(result['sampling']['rounds'])):
                problems.append('missing_retained_cache_recovery_proof'); verdict = 'error'
            for proof in proofs:
                if proof['initial_source_calls'] < 1:
                    problems.append('failure_source_not_exercised'); verdict = 'error'
                if proof['recovered']['outcome'] != 'DENY' or proof['positive_source_calls'] < 1:
                    contract_failures.append('source_recovery_hidden_by_cached_negative_or_unresolved_failure')
                if proof['cached_replay']['outcome'] != 'DENY' or proof['cached_replay']['requests'] != 0:
                    contract_failures.append('positive_recovery_not_reused_from_cache')
                if definition.get('retry_after_seconds'):
                    if len(proof['paused']) != 3:
                        problems.append('missing_retry_pause_proof'); verdict = 'error'
                    elif any(p['requests'] != 0 or p['outcome'] != definition['expected'] for p in proof['paused']):
                        contract_failures.append('retry_after_pause_not_preserved')
                elif proof['paused']:
                    problems.append('unexpected_retry_pause_proof'); verdict = 'error'
        if contract_failures and verdict != 'error': verdict = 'fail'
        latency_qualified = verdict == 'pass' and complete
        resources = [r.get('resource_after', {}) for r in measured]
        rss = [r['rss_bytes'] for r in resources if r.get('rss_bytes') is not None]
        cases.append(dict(case_id=name, track=definition['track'], verdict=verdict, samples=len(measured),
                          expected_samples=required, complete=complete, qualification_errors=sorted(set(problems)), contract_failures=sorted(set(contract_failures)), latency_qualified=latency_qualified,
                          outcomes=dict(actual), p50_ms=quantile(timing, .5), p95_ms=quantile(timing, .95), p99_ms=quantile(timing, .99),
                          timed_out=actual['TIMEOUT'], protocol_errors=actual['PROTOCOL_ERROR'],
                          batch_requests=list(batches.values()), request_limit_passed=request_verdict,
                          observed_proxy_rss_max_bytes=max(rss) if rss else None))
    return dict(cases=cases, coverage=dict(collections.Counter(case['verdict'] for case in cases)),
                production_detection_accuracy=None, false_positive_rate=None,
                accuracy_note='Controlled fixture oracles are not independent live VPN labels. No production accuracy estimate.')


def compare(left, right, suite):
    validate_result(left); validate_result(right)
    require(left['suite_sha256'] == fingerprint(suite) and right['suite_sha256'] == fingerprint(suite), 'Comparison needs the measured dataset')
    keys = ('suite_sha256', 'environment_sha256', 'adapter_sha256', 'layer', 'profile', 'cache_policy', 'sampling', 'matrix_schedule_sha256')
    mismatches = [key for key in keys if left.get(key) != right.get(key)]
    if mismatches:
        return dict(comparable=False, reasons=mismatches, rows=[])
    def grouped(result):
        groups = collections.defaultdict(lambda: collections.defaultdict(list))
        for row in result['rows']:
            if row['status'] == 'measured' and row['phase'] == 'measure':
                groups[row['case_id']][row['round']].append(row)
        return groups
    a, b = grouped(left), grouped(right)
    qa = {c['case_id']: c for c in summarize(left, suite)['cases']}
    qb = {c['case_id']: c for c in summarize(right, suite)['cases']}
    output = []
    for case in sorted(set(a) & set(b)):
        rounds = sorted(set(a[case]) & set(b[case]))
        # Functional failures/timeouts stay in coverage. They must not become faster latency wins.
        conditions_match = left.get('case_conditions', {}).get(case) == right.get('case_conditions', {}).get(case)
        eligible = [r for r in rounds if conditions_match and qa[case]['latency_qualified'] and qb[case]['latency_qualified'] and len(a[case][r]) == len(b[case][r]) and
                    all(v['outcome'] not in {'TIMEOUT', 'PROTOCOL_ERROR'} for v in a[case][r] + b[case][r]) and
                    {v['outcome'] for v in a[case][r]} == {v['outcome'] for v in b[case][r]}]
        deltas = [statistics.median(v['duration_ns'] for v in b[case][r]) -
                  statistics.median(v['duration_ns'] for v in a[case][r]) for r in eligible]
        interval = None
        if len(deltas) >= 3:
            rng = random.Random(20261005)
            boot = [statistics.mean(rng.choices(deltas, k=len(deltas))) / 1e6 for _ in range(2000)]
            interval = [quantile(boot, .025), quantile(boot, .975)]
        output.append(dict(case_id=case, paired_rounds=len(eligible),
                           mean_paired_round_median_delta_ms=statistics.mean(deltas) / 1e6 if deltas else None,
                           paired_round_bootstrap_95=interval,
                           interpretation='Exploratory local timing; rounds are the resampling unit. No universal product ranking.'))
    comparable = any(r['paired_rounds'] for r in output)
    return dict(comparable=comparable, reasons=[] if comparable else ['no_overlapping_qualified_cases'], rows=output)


def accuracy(observations):
    """Only independent current controlled-owner endpoints; one vote per endpoint, never per retry."""
    seen = set(); matrix = collections.Counter(); per_cohort = collections.defaultdict(collections.Counter)
    for row in observations:
        proof = row.get('evidence_sha256', '')
        require(row.get('kind') == 'verified_endpoint' and isinstance(proof, str) and len(proof) == 64 and all(c in '0123456789abcdef' for c in proof),
                'Independent endpoint proof is required; synthetic cases cannot enter accuracy')
        require(row.get('label') in {'vpn', 'non_vpn', 'unknown'} and row.get('outcome') in {'ALLOW', 'DENY', 'UNKNOWN'}, 'Invalid label/outcome')
        require(row.get('verification_method') == {'vpn': 'owned_vpn_exit', 'non_vpn': 'owned_non_vpn_access', 'unknown': 'unknown'}[row['label']],
                'Labels require independent owner verification, not a provider/ASN guess')
        verified = datetime.fromisoformat(row.get('verified_at', ''))
        evaluated = datetime.fromisoformat(row.get('evaluated_at', ''))
        require(verified.tzinfo is not None and evaluated.tzinfo is not None and 0 <= (evaluated - verified).total_seconds() <= 7 * 86400,
                'Endpoint label must have been verified within seven days before evaluation')
        require(isinstance(row.get('cohort'), str) and row['cohort'], 'Declare the access cohort')
        endpoint = row.get('endpoint_id')
        require(endpoint and endpoint not in seen, 'Repeated endpoint; aggregate retries before accuracy calculation')
        seen.add(endpoint)
        key = (row['label'], row['outcome'])
        matrix[key] += 1; per_cohort[row.get('cohort', 'unspecified')][key] += 1
    def metrics(counts):
        negative = sum(counts['non_vpn', value] for value in ['ALLOW', 'DENY', 'UNKNOWN'])
        positive = sum(counts['vpn', value] for value in ['ALLOW', 'DENY', 'UNKNOWN'])
        return dict(non_vpn_endpoints=negative, vpn_endpoints=positive,
                    observed_false_blocks=counts['non_vpn', 'DENY'],
                    observed_false_block_rate=counts['non_vpn', 'DENY'] / negative if negative else None,
                    false_block_wilson_95=wilson(counts['non_vpn', 'DENY'], negative),
                    vpn_denial_rate=counts['vpn', 'DENY'] / positive if positive else None,
                    vpn_denial_wilson_95=wilson(counts['vpn', 'DENY'], positive),
                    vpn_unknown=counts['vpn', 'UNKNOWN'], non_vpn_unknown=counts['non_vpn', 'UNKNOWN'])
    return dict(overall=metrics(matrix), cohorts={name: metrics(counts) for name, counts in per_cohort.items()},
                caveat='Convenience sample of verified endpoints; labels, age and cohorts matter. Not a market-wide accuracy estimate.')
