#!/usr/bin/env python3
"""Synthetic local observation commands and replay against the exact shaded JAR; no network."""
import argparse, hashlib, json, subprocess
from pathlib import Path
ROOT = Path(__file__).resolve().parents[3]
EXPECTED = {
    'default_off_has_no_capture_file', 'capture_requires_separate_permission',
    'authenticated_inputs_captured_without_login_io', 'default_sample_redacts_real_address_and_uuid',
    'saved_replay_has_no_provider_requests', 'redacted_placeholder_cannot_match_literal_rule',
    'explicit_identity_mode_preserves_required_local_inputs', 'identity_mode_replays_address_denial',
    'historical_command_does_not_activate_candidate', 'restart_preserves_archive_without_resuming_capture',
    'shutdown_discards_unsaved_sample',
}
def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--artifact', type=Path, required=True); parser.add_argument('--java', type=Path, required=True)
    parser.add_argument('--work', type=Path, required=True); args = parser.parse_args()
    artifact = args.artifact.resolve(strict=True); assert artifact.is_relative_to(ROOT / 'build/libs')
    work = args.work.resolve(); assert not work.exists(); work.mkdir(parents=True, mode=0o700)
    classes = work / 'classes'; classes.mkdir(); source = Path(__file__).with_name('ObservationJarCases.java')
    subprocess.run([str(args.java.with_name('javac')), '-encoding', 'UTF-8', '-cp', str(artifact), '-d', str(classes), str(source)], check=True, timeout=30)
    run = subprocess.run([str(args.java), '-cp', str(artifact) + ':' + str(classes), 'ObservationJarCases', str(work)], check=True, text=True, capture_output=True, timeout=30)
    cases = [line.partition(' ')[2] for line in run.stdout.splitlines() if line.startswith('OBSERVATION_JAR_CASE ')]
    assert set(cases) == EXPECTED and len(cases) == len(EXPECTED)
    digest = lambda path: hashlib.sha256(path.read_bytes()).hexdigest()
    receipt = dict(schema=1, kind='offline-actual-jar-policy-observations', artifact_sha256=digest(artifact),
                   cases=cases, java=str(args.java), driver_sha256=digest(Path(__file__)), fixture_sha256=digest(source),
                   provider_requests=0, synthetic_inputs=True, native_login_tested=False, competitive_accuracy_tested=False)
    (work / 'receipt.json').write_text(json.dumps(receipt, indent=2) + '\n'); print(json.dumps(receipt, indent=2))
if __name__ == '__main__': main()
