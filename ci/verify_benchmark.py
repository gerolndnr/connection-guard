#!/usr/bin/env python3
"""CI gate for the actual CG JAR's typed-source benchmark, not a competitor rank."""
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'benchmarks'))
from cgbench.analysis import summarize
from cgbench.model import fingerprint, read, require, validate_suite

if __name__ == '__main__':
    result = read(sys.argv[1])
    suite = validate_suite(read(ROOT / 'benchmarks/datasets/controlled-v1.json'))
    require(result['product'] == 'connection-guard' and result['layer'] == 'core_lookup', 'Wrong benchmark gate layer/product')
    require(result['suite_sha256'] == fingerprint(suite), 'Wrong benchmark dataset')
    cases = {c['case_id']: c for c in summarize(result, suite)['cases']}
    mandatory = [c['id'] for c in suite['cases'] if 'expected_core' in c]
    require(all(cases[name]['verdict'] == 'pass' and cases[name]['complete'] for name in mandatory), 'Controlled CG core contract failed; inspect receipt')
    print(f'{len(mandatory)} complete controlled core cases passed; no native/production accuracy claim.')
