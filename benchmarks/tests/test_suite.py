import copy
import io
import json
import math
from pathlib import Path
import socket
import struct
import tempfile
import os
import shutil
import subprocess
import unittest
from cgbench import analysis, artifacts, model, native, protocol, export

ROOT = Path(__file__).resolve().parents[1]


def result(rows=None):
    return dict(schema=1, kind='measured', product='test-fixture', layer='native_velocity_login_gate',
                suite_sha256=model.fingerprint(model.read(ROOT / 'datasets/controlled-v1.json')), adapter_sha256='b' * 64, artifact_sha256='c' * 64,
                environment_sha256='d' * 64, profile='controlled_enforce', cache_policy='disabled',
                sampling=dict(rounds=1, samples=1, warmups=1), rows=rows or [])


def row(outcome='ALLOW', duration=1_000_000, **extra):
    value = dict(case_id='vpn_negative_v4', round=0, phase='measure', sample=0, status='measured',
                 outcome=outcome, duration_ns=duration, requests=1)
    value.update(extra); return value


class SchemaTest(unittest.TestCase):
    def setUp(self):
        self.suite = model.read(ROOT / 'datasets/controlled-v1.json')
    def test_public_plan_contains_required_failure_ipv6_cache_and_geo_tracks(self):
        model.validate_suite(self.suite)
        self.assertEqual(25, len(self.suite['cases']))
        self.assertEqual({'rules', 'provider', 'geo', 'cache', 'concurrency'}, {c['track'] for c in self.suite['cases']})
        self.assertIn('vpn_mapped_v4', {c['id'] for c in self.suite['cases']})
    def test_arbitrary_public_targets_are_rejected_before_execution(self):
        self.suite['cases'][0]['ip'] = '8.8.8.8'
        with self.assertRaises(model.Invalid): model.validate_suite(self.suite)
    def test_concurrency_booleans_and_oversized_batches_are_rejected(self):
        for value in [True, 0, 33]:
            self.suite['cases'][0]['concurrency'] = value
            with self.assertRaises(model.Invalid): model.validate_suite(self.suite)
    def test_duplicate_ids_and_unknown_oracles_are_rejected(self):
        self.suite['cases'].append(copy.deepcopy(self.suite['cases'][0]))
        with self.assertRaises(model.Invalid): model.validate_suite(self.suite)
    def test_duplicate_json_keys_and_nan_are_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'x.json'
            for value in ['{"x":1,"x":2}', '{"x":NaN}']:
                path.write_text(value)
                with self.assertRaises(model.Invalid): model.read(path)
    def test_existing_measurements_are_never_overwritten(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'x.json'; model.write(path, {'a': 1})
            with self.assertRaises(model.Invalid): model.write(path, {'a': 2})
            self.assertEqual({'a': 1}, model.read(path))
    def test_unbound_evidence_cannot_be_a_measured_result(self):
        value = result(); del value['artifact_sha256']
        with self.assertRaises(model.Invalid): model.validate_result(value)
    def test_duplicate_timing_samples_are_rejected(self):
        with self.assertRaises(model.Invalid): model.validate_result(result([row(), row()]))
    def test_negative_latency_and_synthetic_rankings_are_rejected(self):
        with self.assertRaises(model.Invalid): model.validate_result(result([row(duration=-1)]))
        value = result(); value['kind'] = 'simulated'
        with self.assertRaises(model.Invalid): model.validate_result(value)


class AnalysisTest(unittest.TestCase):
    def setUp(self): self.suite = model.read(ROOT / 'datasets/controlled-v1.json')
    def test_percentiles_include_the_tail_and_handle_an_empty_set(self):
        self.assertAlmostEqual(95, analysis.quantile([0, 100], .95))
        self.assertIsNone(analysis.quantile([], .5))
    def test_missing_competitors_are_not_given_zero_latency_or_failure(self):
        summary = analysis.summarize(result(), self.suite)
        self.assertEqual({'not_tested': 25}, summary['coverage'])
        self.assertIsNone(summary['cases'][0]['p95_ms'])
        self.assertIsNone(summary['production_detection_accuracy'])
    def test_unsupported_is_separate_from_product_failure(self):
        value = row(status='unsupported'); value.pop('duration_ns')
        report = analysis.summarize(result([value]), self.suite)
        self.assertEqual('unsupported', report['cases'][1]['verdict'])
    def test_harness_timeouts_are_kept_in_latency_and_failure_counts(self):
        report = analysis.summarize(result([row('TIMEOUT', 3_000_000_000)]), self.suite)
        self.assertEqual('fail', report['cases'][1]['verdict'])
        self.assertEqual(3000, report['cases'][1]['p95_ms'])
        self.assertEqual(1, report['cases'][1]['timed_out'])
    def test_http_call_batches_are_not_multiplied_by_concurrent_clients(self):
        rows = [row('DENY', case_id='same_ip_16', sample=i, requests=None, batch_id='batch', batch_requests=1) for i in range(16)]
        summary = analysis.summarize(result(rows), self.suite)
        case = next(c for c in summary['cases'] if c['case_id'] == 'same_ip_16')
        self.assertEqual([1], case['batch_requests']); self.assertTrue(case['request_limit_passed'])
    def test_excess_provider_requests_fail_the_declared_budget_case(self):
        summary = analysis.summarize(result([row('DENY', case_id='same_ip_16', sample=i, batch_id='b', batch_requests=16) for i in range(16)]), self.suite)
        self.assertEqual('fail', next(c for c in summary['cases'] if c['case_id'] == 'same_ip_16')['verdict'])
    def test_core_oracle_does_not_count_a_vpn_fact_as_a_native_kick(self):
        value = result([row('NEGATIVE')]); value['layer'] = 'core_lookup'
        self.assertEqual('pass', analysis.summarize(value, self.suite)['cases'][1]['verdict'])
    def test_different_layers_environments_and_datasets_are_not_ranked(self):
        for key in ['layer', 'environment_sha256', 'profile', 'sampling']:
            left = result([row()]); right = copy.deepcopy(left)
            right[key] = 'core_lookup' if key == 'layer' else 'e' * 64 if key.endswith('sha256') else dict(rounds=2, samples=1, warmups=1) if key == 'sampling' else 'different'
            comparison = analysis.compare(left, right, self.suite)
            self.assertFalse(comparison['comparable']); self.assertIn(key, comparison['reasons'])
    def test_timeout_and_changed_outcome_cannot_become_speed_wins(self):
        left = result([row()]); right = result([row('TIMEOUT', duration=1)])
        self.assertEqual(0, analysis.compare(left, right, self.suite)['rows'][0]['paired_rounds'])
    def test_bootstrap_uses_independent_rounds_not_every_retry(self):
        left = result([row(round=i) for i in range(3)])
        right = result([row(duration=2_000_000, round=i) for i in range(3)])
        left['sampling']['rounds'] = right['sampling']['rounds'] = 3
        value = analysis.compare(left, right, self.suite)['rows'][0]
        self.assertEqual(3, value['paired_rounds']); self.assertEqual([1, 1], value['paired_round_bootstrap_95'])
    def test_one_round_is_not_given_a_confidence_interval(self):
        value = analysis.compare(result([row()]), result([row(duration=2_000_000)]), self.suite)['rows'][0]
        self.assertIsNone(value['paired_round_bootstrap_95'])
    def test_synthetic_labels_cannot_enter_the_accuracy_study(self):
        with self.assertRaises(model.Invalid): analysis.accuracy([dict(kind='synthetic', label='vpn', outcome='DENY')])
    def test_retries_do_not_inflate_real_endpoint_sample_size(self):
        value = dict(kind='verified_endpoint', endpoint_id='owned-a', evidence_sha256='a' * 64, label='vpn', outcome='DENY', verification_method='owned_vpn_exit', verified_at='2026-10-05T10:00:00Z', evaluated_at='2026-10-05T11:00:00Z', cohort='owned-datacenter-v4')
        with self.assertRaises(model.Invalid): analysis.accuracy([value, value])
    def test_empty_accuracy_cohort_is_unavailable_and_not_one_hundred_percent(self):
        value = analysis.accuracy([])['overall']
        self.assertIsNone(value['vpn_denial_rate']); self.assertIsNone(value['observed_false_block_rate'])
    def test_wilson_interval_remains_wide_for_one_endpoint(self):
        self.assertGreater(analysis.wilson(0, 1)[1], .7)


    def test_partial_or_permission_denied_run_cannot_pass_or_win_latency(self):
        value = result([row()]); value['sampling']['rounds'] = 2
        self.assertEqual('error', analysis.summarize(value, self.suite)['cases'][1]['verdict'])
        denied = result([row()]); denied['environment_errors'] = [dict(case_id='vpn_negative_v4', round=0, reason='fixture_permission_denial')]
        self.assertEqual('error', analysis.summarize(denied, self.suite)['cases'][1]['verdict'])
        self.assertFalse(analysis.compare(denied, result([row()]), self.suite)['comparable'])
    def test_different_source_paths_are_not_ranked_as_plugin_speed(self):
        left, right = result([row()]), result([row()])
        left['case_conditions'] = {'vpn_negative_v4': {'source': 'local_mmdb'}}
        right['case_conditions'] = {'vpn_negative_v4': {'source': 'owned_http'}}
        self.assertFalse(analysis.compare(left, right, self.suite)['comparable'])
    def test_matching_wrong_outcomes_cannot_win_latency(self):
        self.assertFalse(analysis.compare(result([row('DENY')]), result([row('DENY', 1)]), self.suite)['comparable'])
    def test_conflicting_batch_counts_are_rejected(self):
        rows = [row('DENY', case_id='same_ip_16', sample=i, batch_id='b', batch_requests=i) for i in range(16)]
        with self.assertRaises(model.Invalid): analysis.summarize(result(rows), self.suite)
    def test_environment_evidence_is_bound_to_its_hash(self):
        value = result(); value['environment'] = {'runtime': 'invented'}
        with self.assertRaises(model.Invalid): model.validate_result(value)
    def test_export_contains_unavailable_cases_and_artifact_evidence(self):
        value = result([row()]); value['version'] = '1.0'
        markdown, csv_text = export.render({'fixture': value}, self.suite)
        self.assertIn('not_tested', markdown); self.assertIn('c' * 64, csv_text)
        self.assertIn('not a production VPN accuracy study', markdown)
    def test_accuracy_rejects_provider_inferred_or_stale_labels(self):
        value = dict(kind='verified_endpoint', endpoint_id='owned', evidence_sha256='a' * 64, label='vpn', outcome='DENY',
            verification_method='asn_guess', verified_at='2026-10-05T10:00:00Z', evaluated_at='2026-10-05T11:00:00Z', cohort='test')
        with self.assertRaises(model.Invalid): analysis.accuracy([value])
        value.update(verification_method='owned_vpn_exit', evaluated_at='2026-10-13T11:00:00Z')
        with self.assertRaises(model.Invalid): analysis.accuracy([value])

class WireTest(unittest.TestCase):
    def test_proxy_v2_ipv4_uses_the_fixture_ip_and_actual_loopback_destination(self):
        packet = protocol.proxy_v2('192.0.2.10', 1000, 2000)
        self.assertEqual(b'\r\n\r\n\x00\r\nQUIT\n', packet[:12])
        self.assertEqual(b'\x21\x11\x00\x0c', packet[12:16])
        self.assertEqual(socket.inet_aton('192.0.2.10') + socket.inet_aton('127.0.0.1'), packet[16:24])
    def test_proxy_v2_ipv6_and_mapped_ipv4_are_not_silently_rewritten(self):
        for ip in ['2001:db8::10', '::ffff:192.0.2.10']:
            data = protocol.proxy_v2(ip, 1, 2)
            self.assertEqual(0x21, data[13]); self.assertEqual(36, struct.unpack('>H', data[14:16])[0])
    def test_incomplete_packet_fails_instead_of_spinning(self):
        class Closed:
            def recv(self, count): return b''
        with self.assertRaises(EOFError): protocol.exact(Closed(), 1)
    def test_corrupt_varint_is_bounded(self):
        class Corrupt:
            def recv(self, count): return b'\x80'
        with self.assertRaises(ValueError): protocol.read_varint(Corrupt())
    def test_enforce_closed_fixtures_have_no_cloud_or_external_provider(self):
        # Read the checked-in config without needing a downloaded competitor or a server.
        import zipfile
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'fixture.jar'
            with zipfile.ZipFile(path, 'w') as jar:
                jar.write(ROOT.parent / 'core/src/main/resources/config.yml', 'config.yml')
                jar.write(ROOT.parent / 'core/src/main/resources/translation/en.yml', 'translation/en.yml')
            import yaml
            value = yaml.safe_load(native.cg_config(path, '503_closed', 'http://127.0.0.1:12345')['config.yml'])
            self.assertFalse(value['cloud']['enabled']); self.assertEqual('CLOSED', value['failure-policy']['vpn'])
            self.assertFalse(value['provider']['vpn']['proxycheck']['enabled'])
            self.assertEqual('http://127.0.0.1:12345/%IP%', value['provider']['vpn']['custom']['request-url'])
    def test_benchmark_policy_does_not_grant_external_socket_connections(self):
        policy = native.security_policy(Path('/tmp/owned'), Path('/opt/java/bin/java'), Path('/tmp/runtime.jar'))
        self.assertNotIn('AllPermission', policy)
        self.assertNotIn('SocketPermission "*", "connect', policy)
        self.assertIn('SocketPermission "127.0.0.1:*"', policy)


    def test_georestrict_adapter_sets_the_real_gateway_and_timeout_floor(self):
        import yaml
        value = yaml.safe_load(native.georestrict_config('503_closed', 'http://127.0.0.1:12345')['config.yml'])
        self.assertEqual(500, value['connectionTimeoutMs']); self.assertTrue(value['blockOnLookupFailure'])
        self.assertEqual('http://127.0.0.1:12345', value['gatewayUrl']); self.assertFalse(value['updateCheck'])
    def test_java_guard_rejects_external_targets_and_relative_traversal_offline(self):
        java = os.environ.get('CGBENCH_JAVA') or shutil.which('java')
        if not java: self.skipTest('Java 21 not available')
        version = subprocess.run([java, '-version'], text=True, capture_output=True, check=True).stderr
        if 'version "21' not in version: self.skipTest('Guard requires Java 21')
        javac = str(Path(java).resolve().with_name('javac'))
        with tempfile.TemporaryDirectory() as directory:
            work = Path(directory).resolve(); classes = work / 'classes'; classes.mkdir()
            subprocess.run([javac, '--release', '21', '-d', str(classes), str(ROOT / 'java/LoopbackSecurityManager.java'), str(ROOT / 'java/GuardContract.java')], check=True, capture_output=True)
            policy = work / 'policy'; policy.write_text(native.security_policy(work, Path(java).resolve(), work / 'runtime.jar', classes))
            outcome = subprocess.run([java, '-Djava.security.manager=bench.fixture.LoopbackSecurityManager', '-Djava.security.policy==' + str(policy),
                '-Dbench.audit.path=' + str(work / 'audit.log'), '-Duser.dir=' + str(work), '-cp', str(classes), 'bench.fixture.GuardContract'],
                cwd=work, text=True, capture_output=True, timeout=10)
            self.assertEqual(0, outcome.returncode, outcome.stderr); self.assertIn('Guard contract passed', outcome.stdout)

class FailureRecoveryTest(unittest.TestCase):
    def setUp(self):
        self.suite = model.validate_suite(model.read(ROOT / 'datasets/failure-recovery-v1.json'))
        self.case = self.suite['cases'][3]  # malformed / CLOSED
        self.value = result([row('DENY', case_id=self.case['id'])])
        self.value['suite_sha256'] = model.fingerprint(self.suite)
        self.value['recovery_proofs'] = [dict(case_id=self.case['id'], round=0, cache_retained=True,
            initial_source_calls=2, positive_source_calls=1, recovery_wait_ms=20, paused=[],
            recovered=dict(outcome='DENY', requests=1, duration_ns=1000),
            cached_replay=dict(outcome='DENY', requests=0, duration_ns=1000))]
    def verdict(self):
        return next(c for c in analysis.summarize(self.value, self.suite)['cases'] if c['case_id'] == self.case['id'])
    def test_all_three_closed_gaps_have_paired_open_unknown_oracles(self):
        failures = self.suite['cases'][:6]
        self.assertEqual({'429_open', '429_closed', 'malformed_open', 'malformed_closed', 'missing_open', 'missing_closed'}, {c['fixture'] for c in failures})
        for c in failures:
            self.assertEqual('UNKNOWN', c['expected_core'])
            self.assertEqual('DENY' if c['fixture'].endswith('closed') else 'ALLOW', c['expected'])
    def test_closed_denial_requires_successful_retained_cache_recovery(self):
        self.assertEqual('pass', self.verdict()['verdict'])
        self.value['recovery_proofs'][0]['positive_source_calls'] = 0
        self.assertEqual('fail', self.verdict()['verdict'])
        self.assertFalse(self.verdict()['latency_qualified'])
    def test_missing_recovery_proof_cannot_pass_from_denials_alone(self):
        self.value['recovery_proofs'] = []
        self.assertEqual('error', self.verdict()['verdict'])
    def test_cached_negative_or_recovery_without_cache_reuse_is_a_failure(self):
        for key in ['recovered', 'cached_replay']:
            value = copy.deepcopy(self.value)
            self.value['recovery_proofs'][0][key]['outcome'] = 'ALLOW'
            self.assertEqual('fail', self.verdict()['verdict'])
            self.value = value
        self.value['recovery_proofs'][0]['cached_replay']['requests'] = 1
        self.assertEqual('fail', self.verdict()['verdict'])
    def test_429_must_preserve_the_declared_pause_and_policy(self):
        self.case = self.suite['cases'][1]
        self.value['rows'][0]['case_id'] = self.case['id']
        proof = self.value['recovery_proofs'][0]; proof['case_id'] = self.case['id']
        proof['paused'] = [dict(outcome='DENY', requests=0, duration_ns=1000) for _ in range(3)]
        self.assertEqual('pass', self.verdict()['verdict'])
        proof['paused'][0]['requests'] = 1
        self.assertEqual('fail', self.verdict()['verdict'])
    def test_unknown_with_a_negative_or_wrong_reason_does_not_pass(self):
        self.value['layer'] = 'core_lookup'
        self.value['recovery_proofs'] = []
        self.value['rows'][0].update(outcome='UNKNOWN', source_reasons=['INVALID_RESPONSE'])
        self.assertEqual('pass', self.verdict()['verdict'])
        self.value['rows'][0]['source_reasons'] = ['NONE']
        self.assertEqual('fail', self.verdict()['verdict'])
        self.value['rows'][0].update(outcome='NEGATIVE', source_reasons=['INVALID_RESPONSE'])
        self.assertEqual('fail', self.verdict()['verdict'])
    def test_recovery_proofs_cannot_reference_an_unmeasured_suite_case(self):
        self.value['recovery_proofs'][0]['case_id'] = 'invented_case'
        with self.assertRaises(model.Invalid): self.verdict()
    def test_duplicate_recovery_or_non_retained_cache_is_invalid_evidence(self):
        self.value['recovery_proofs'] *= 2
        with self.assertRaises(model.Invalid): model.validate_result(self.value)
        self.value['recovery_proofs'] = self.value['recovery_proofs'][:1]
        self.value['recovery_proofs'][0]['cache_retained'] = False
        with self.assertRaises(model.Invalid): model.validate_result(self.value)
    def test_recovery_cases_enable_actual_sqlite_without_other_sources(self):
        import zipfile, yaml
        with tempfile.TemporaryDirectory() as directory:
            jar = Path(directory) / 'fixture.jar'
            with zipfile.ZipFile(jar, 'w') as archive:
                archive.write(ROOT.parent / 'core/src/main/resources/config.yml', 'config.yml')
                archive.write(ROOT.parent / 'core/src/main/resources/translation/en.yml', 'translation/en.yml')
            for case in self.suite['cases'][:6]:
                config = yaml.safe_load(native.cg_config(jar, case['fixture'], 'http://127.0.0.1:12345', cache_recovery=True)['config.yml'])
                self.assertEqual('SQLite', config['provider']['cache']['type'])
                self.assertFalse(config['cloud']['enabled'])
                self.assertEqual(['custom'], [k for k,v in config['provider']['vpn'].items() if v['enabled']])
    def test_http_fixture_retains_the_counter_when_switching_to_positive(self):
        import urllib.request
        fixture = native.ProviderFixture('429_closed', 2)
        try:
            endpoint = 'http://127.0.0.1:' + str(fixture.server.server_port)
            opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
            with self.assertRaises(urllib.error.HTTPError) as failure:
                opener.open(endpoint, timeout=2)
            self.assertEqual('2', failure.exception.headers['Retry-After'])
            failure.exception.close()
            fixture.set_behavior('positive')
            with opener.open(endpoint, timeout=2) as response:
                self.assertTrue(json.load(response)['data']['isVpn'])
            self.assertEqual(2, fixture.calls)
            self.assertIsNotNone(fixture.last_rate_limit_ns)
        finally: fixture.close()

if __name__ == '__main__': unittest.main()
