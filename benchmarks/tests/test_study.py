import copy
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
from cgbench import matrix, model, study

ROOT = Path(__file__).resolve().parents[1]


class StudyTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.protocol = model.read(ROOT / 'studies/latency-v1.json')
        self.protocol_path = self.root / 'protocol.json'
        self.runtime = self.root / 'velocity.jar'
        self.runtime.write_bytes(b'owned-test-runtime')
        self.protocol['runtime_sha256'] = model.sha(self.runtime)
        self.products = []
        for i, expected in enumerate(self.protocol['products']):
            artifact = self.root / (expected['id'] + '.jar')
            artifact.write_bytes(('owned-fixture-' + str(i)).encode())
            expected['artifact_sha256'] = None if i == 1 else model.sha(artifact)
            product = dict(id=expected['id'], adapter=expected['adapter'], artifact=str(artifact), artifact_sha256=model.sha(artifact))
            if i < 2:
                assets = self.root / (expected['id'] + '-lib'); assets.mkdir()
                (assets / 'dependency.jar').write_bytes(b'identical-test-dependency')
                product['assets'] = str(assets)
            self.products.append(product)
        self.manifest = dict(schema=1, study=str(self.protocol_path), suite=str(ROOT / 'datasets/latency-v1.json'),
                             runtime=str(self.runtime), java='/unused/test/java', rounds=6, samples=30, warmups=20, products=self.products)
        self.work = self.root / 'not-created'
        self.save_protocol()
        for target, value in [('cgbench.study.shutil.disk_usage', type('Disk', (), {'free': 7 * 1024**3})()),
                              ('cgbench.study.os.getloadavg', (0.1, 0.2, 0.3)), ('cgbench.study.os.cpu_count', 8)]:
            p = patch(target, return_value=value); p.start(); self.addCleanup(p.stop)

    def save_protocol(self):
        self.protocol_path.write_text(json.dumps(self.protocol))

    def test_complete_preflight_is_hash_bound_and_starts_nothing(self):
        plan = study.preflight(self.manifest, self.work)
        self.assertEqual(72, plan['planned_proxies'])
        self.assertEqual(10260, plan['planned_measured_logins'])
        self.assertEqual(6840, plan['planned_warmup_logins'])
        self.assertEqual(model.fingerprint(self.protocol), plan['protocol_sha256'])
        self.assertEqual(model.fingerprint(self.manifest), plan['manifest_sha256'])
        self.assertFalse(plan['runtime_started'])
        self.assertFalse(plan['idle_throughout_run_verified'])
        self.assertFalse(self.work.exists())

    def test_exploratory_or_boolean_sampling_cannot_be_a_study(self):
        for key, bad in [('rounds', 3), ('samples', 4), ('warmups', 2), ('rounds', True)]:
            m = copy.deepcopy(self.manifest); m[key] = bad
            with self.subTest(key=key, value=bad), self.assertRaises(model.Invalid): study.preflight(m, self.work)

    def test_altered_dataset_fails_before_any_proxy_or_output(self):
        suite = model.read(self.manifest['suite']); suite['cases'][0]['request_limit'] = 2
        path = self.root / 'changed.json'; path.write_text(json.dumps(suite))
        self.manifest['suite'] = str(path)
        with patch('cgbench.matrix.native.run') as run:
            with self.assertRaises(model.Invalid): matrix.run(self.manifest, self.work)
            run.assert_not_called(); self.assertFalse(self.work.exists())

    def test_mutated_or_unpinned_artifact_is_rejected(self):
        for i in range(3):
            m = copy.deepcopy(self.manifest); m['products'][i]['artifact_sha256'] = 'f' * 64
            with self.subTest(product=i), self.assertRaises(model.Invalid): study.preflight(m, self.work)
        Path(self.products[1]['artifact']).write_bytes(b'mutated')
        with self.assertRaises(model.Invalid): study.preflight(self.manifest, self.work)

    def test_stable_and_candidate_cannot_be_the_same_jar(self):
        self.products[1]['artifact'] = self.products[0]['artifact']
        self.products[1]['artifact_sha256'] = self.products[0]['artifact_sha256']
        with self.assertRaises(model.Invalid): study.preflight(self.manifest, self.work)

    def test_dependency_tradeoff_is_not_hidden_in_a_speed_study(self):
        Path(self.products[1]['assets'], 'dependency.jar').write_bytes(b'different')
        with self.assertRaises(model.Invalid): study.preflight(self.manifest, self.work)

    def test_reordered_products_or_changed_adapter_are_rejected(self):
        m = copy.deepcopy(self.manifest); m['products'].reverse()
        with self.assertRaises(model.Invalid): study.preflight(m, self.work)
        m = copy.deepcopy(self.manifest); m['products'][1]['adapter'] = 'sqidgeon-antivpn'
        with self.assertRaises(model.Invalid): study.preflight(m, self.work)

    def test_wrong_runtime_is_rejected(self):
        self.runtime.write_bytes(b'different-runtime')
        with self.assertRaises(model.Invalid): study.preflight(self.manifest, self.work)

    def test_insufficient_storage_and_busy_host_fail_before_native_work(self):
        for target, value in [('cgbench.study.shutil.disk_usage', type('Disk', (), {'free': 5 * 1024**3})()),
                              ('cgbench.study.os.getloadavg', (3, 0, 0)), ('cgbench.study.os.getloadavg', (0, 3, 0))]:
            with self.subTest(target=target), patch(target, return_value=value), patch('cgbench.matrix.native.run') as run:
                with self.assertRaises(model.Invalid): matrix.run(self.manifest, self.work)
                run.assert_not_called(); self.assertFalse(self.work.exists())

    def test_acceptance_rule_cannot_be_weakened(self):
        self.protocol['acceptance']['minimum_primary_reduction_fraction'] = 0.01
        self.save_protocol()
        with self.assertRaises(model.Invalid): study.preflight(self.manifest, self.work)

    def test_prior_evidence_is_preserved(self):
        self.work.mkdir(); marker = self.work / 'keep'; marker.write_text('prior evidence')
        with self.assertRaises(model.Invalid): study.preflight(self.manifest, self.work)
        self.assertEqual('prior evidence', marker.read_text())

    def test_plan_is_saved_before_first_proxy_and_receipts_are_bound(self):
        def observed_run(*args):
            self.assertTrue((self.work / 'study-plan.json').is_file())
            plan = model.read(self.work / 'study-plan.json')
            adapter = args[7]
            index = 2 if adapter == 'georestrict' else (0 if Path(args[1]).name.startswith('cg-stable') else 1)
            return dict(artifact_sha256=self.products[index]['artifact_sha256'], sampling={}, rows=[], logs=[], environment_errors=[],
                        config_sha256=[], recovery_proofs=[], environment_sha256='a'*64, adapter_sha256='b'*64,
                        asset_sha256=plan['dependency_sha256'].get(self.products[index]['id'], {}),
                        environment=dict(runtime_sha256=self.protocol['runtime_sha256']),
                        input_sha256={**{'cgbench/'+name: digest for name, digest in plan['runner_input_sha256'].items()},
                                      'java/LoopbackSecurityManager.java': plan['transport_guard_sha256']},
                        profile='controlled_enforce', cache_policy='declared_per_case')
        with patch('cgbench.matrix.native.run', side_effect=observed_run), patch('cgbench.matrix.analysis.summarize', return_value={}):
            receipts, _ = matrix.run(self.manifest, self.work)
        plan = model.read(self.work / 'study-plan.json')
        for value in receipts.values(): self.assertEqual(model.fingerprint(plan), value['study_plan_sha256'])
        self.assertEqual(6, len(model.read(self.work / 'schedule.json')))

    def test_post_preflight_artifact_change_cannot_produce_a_study_receipt(self):
        def changed(*args):
            self.assertTrue((self.work / 'study-plan.json').is_file())
            return dict(artifact_sha256='f'*64)
        with patch('cgbench.matrix.native.run', side_effect=changed):
            with self.assertRaises(model.Invalid): matrix.run(self.manifest, self.work)
        self.assertTrue((self.work / 'study-plan.json').exists())
        self.assertFalse((self.work / 'cg-stable.json').exists())

    def test_changed_dependencies_or_runtime_cannot_enter_bound_receipts(self):
        for field in ['asset_sha256', 'environment', 'input_sha256']:
            work = self.root / ('changed-' + field)
            def changed(*args):
                plan = model.read(work / 'study-plan.json')
                value = dict(artifact_sha256=self.products[0]['artifact_sha256'],
                             asset_sha256=plan['dependency_sha256']['cg-stable'],
                             environment=dict(runtime_sha256=self.protocol['runtime_sha256']),
                             input_sha256={**{'cgbench/'+name: digest for name, digest in plan['runner_input_sha256'].items()},
                                           'java/LoopbackSecurityManager.java': plan['transport_guard_sha256']})
                value[field] = {}
                return value
            with self.subTest(field=field), patch('cgbench.matrix.native.run', side_effect=changed):
                with self.assertRaises(model.Invalid): matrix.run(self.manifest, work)
            self.assertFalse((work / 'cg-stable.json').exists())


if __name__ == '__main__': unittest.main()
