#!/usr/bin/env python3
"""Owned loopback capture/replay qualification; uses the existing hash-pinned local runtimes."""
import argparse, copy, hashlib, json, os, re, socket, subprocess, sys, time, zipfile
from pathlib import Path
CHECKOUT = Path(__file__).resolve().parents[3]
ROOT = CHECKOUT.parent
sys.path.insert(0, str(ROOT / 'tools'))
import yaml
from release_velocity_test import JAVA, PROXY, PROXY_SHA, login
from release_redis_velocity_test import Proxy
from release_native_bungee_test import Bungee, RUNTIME, RUNTIME_SHA
from backend_fixture import Backend, RUNTIMES
from release_backend_test import Clients
from policy_version_runtime_cases import restart_process

def digest(path): return hashlib.sha256(path.read_bytes()).hexdigest()
def wait_until(runtime, test, seconds=15):
    end = time.monotonic() + seconds
    while time.monotonic() < end:
        value = test()
        if value: return value
        if runtime.process.poll() is not None: raise RuntimeError('Owned runtime exited before evidence')
        time.sleep(.02)
    raise RuntimeError('Missing owned observation evidence')

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--platform', choices=['velocity','bungee','paper','folia'], required=True)
    parser.add_argument('--artifact', type=Path, required=True); parser.add_argument('--sha256', required=True)
    parser.add_argument('--fixture', required=True); args = parser.parse_args()
    artifact = args.artifact.resolve(strict=True)
    assert artifact.is_relative_to(CHECKOUT / 'build/libs') and digest(artifact) == args.sha256
    assert re.fullmatch('[a-z0-9-]+', args.fixture)
    source = subprocess.check_output(['git','-C',str(CHECKOUT),'rev-parse','HEAD'], text=True).strip()
    assert not subprocess.check_output(['git','-C',str(CHECKOUT),'status','--porcelain'], text=True).strip(), 'Candidate source must be committed'
    for field in ['CONNECTIONGUARD_CLOUD','CONNECTIONGUARD_TOR_REFRESH','CONNECTIONGUARD_INTEL_REFRESH']:
        os.environ[field] = 'false'
    runtime_scope = 'backend-smoke' if args.platform in RUNTIMES else 'native-bungee-smoke' if args.platform == 'bungee' else 'policy-observation-smoke'
    work = ROOT / '.runtime' / runtime_scope / args.fixture
    assert not work.exists(); work.mkdir(parents=True, mode=0o700)
    classes = work / 'classes'; classes.mkdir(); fixtures = CHECKOUT / 'ci/fixtures/policy'
    backend = args.platform in RUNTIMES
    if backend:
        libraries = sorted((ROOT / '.runtime/backend-fixture/compile-libraries').glob('*.jar'))
        wrapper, descriptor = 'PolicyBackendFixture', 'plugin.yml'; runtime_name, runtime_sha = RUNTIMES[args.platform]
    elif args.platform == 'velocity':
        assert digest(PROXY) == PROXY_SHA
        libraries, wrapper, descriptor, runtime_name, runtime_sha = [PROXY], 'PolicyVelocityFixture', 'velocity-plugin.json', PROXY.name, PROXY_SHA
    else:
        assert digest(RUNTIME) == RUNTIME_SHA
        libraries, wrapper, descriptor, runtime_name, runtime_sha = [RUNTIME], 'PolicyBungeeFixture', 'bungee.yml', RUNTIME.name, RUNTIME_SHA
    sources = [fixtures / 'PolicyFixtureState.java', fixtures / (wrapper + '.java')]
    subprocess.run([str(Path(JAVA).with_name('javac')), '--release', '21', '-proc:none', '-cp', ':'.join(map(str,[artifact,*libraries])), '-d', str(classes), *map(str,sources)], check=True, timeout=30)
    addon = work / 'addon.jar'
    with zipfile.ZipFile(addon,'w') as output:
        for file in sorted(classes.rglob('*.class')): output.write(file, file.relative_to(classes).as_posix())
        output.write(fixtures / descriptor, descriptor)
    with zipfile.ZipFile(artifact) as jar: settings = yaml.safe_load(jar.read('config.yml'))
    for provider in settings['provider']['vpn'].values(): provider['enabled'] = False
    settings['integrations']['providers'] = {'enabled':True, 'sources':[{'id':'policy-fixture','voting':True}]}
    settings['integrations']['observers'] = {'enabled':True, 'ids':['policy-observer']}
    settings['integrations']['admission'] = {'enabled':False, 'ids':[]}
    settings['provider']['geo']['service'] = 'Disabled'; settings['provider']['cache']['type'] = 'Disabled'
    settings['operation']['mode'] = 'ENFORCE'; settings['failure-policy'] = {'vpn':'CLOSED','geo':'OPEN'}
    settings['lookup']['deadline-ms'] = 4000; settings['lookup']['http-timeout-ms'] = 1000
    for scope in ['vpn','geo']:
        settings['behavior'][scope]['exemptions'] = []; settings['behavior'][scope]['use-permission-exemption'] = False
        settings['behavior'][scope]['notify-staff'] = False; settings['behavior'][scope]['send-webhook']['enabled'] = False
        settings['behavior'][scope]['execute-command'] = {'enabled':True,'command':'fixture-policy action'}
        settings['behavior'][scope]['kick-player'] = True
    result = dict(schema=1, source=source, artifact_sha256=args.sha256, runtime=runtime_name, runtime_sha256=runtime_sha,
                  java=21, synthetic_offline_clients=True, real_account_authentication=False, telemetry=False,
                  addon_sha256=digest(addon), driver_sha256=digest(Path(__file__)), cases=[])
    runtime = clients = reservation = None
    def passed(name): result['cases'].append(name)
    try:
        if backend: runtime = Backend(work / 'server', args.platform, artifact, settings, addon=addon)
        elif args.platform == 'bungee': runtime = Bungee(work / 'proxy', artifact, addon, settings, [], {}, False)
        else:
            reservation = socket.socket(); reservation.bind(('127.0.0.1',0))
            runtime = Proxy(work / 'proxy', artifact, settings, addon=addon, reserved_backend_port=reservation.getsockname()[1])
        runtime.ready()
        runtime.command('cg reload', 'Config has been reloaded!')
        if backend: clients = Clients(runtime)
        data = runtime.directory / 'plugins' / ('connection-guard' if args.platform == 'velocity' else 'ConnectionGuard')
        policy = data / 'policy'; policy.mkdir(exist_ok=True); archive = policy / 'observations.json'
        candidate = json.loads((fixtures / 'candidate.json').read_text())
        candidate.update(kick_vpn=False, kick_geo=False, countries=[], rules=[], vpn_failure='CLOSED', geo_failure='OPEN')
        (policy / 'candidate.json').write_text(json.dumps(candidate))
        def state(): return {key:int(value) for key,value in re.findall(r'(calls|geoCalls|actions)=(\d+)', runtime.command('fixture-policy status','POLICY_STATUS'))}
        def denied(name):
            offset = len(runtime.transcript); before = state()
            if backend:
                clients.write('connect ' + name); assert 'VPN' in clients.denial(name)
            else: assert 'VPN' in login(runtime.port,name)
            wait_until(runtime, lambda: state()['actions'] == before['actions'] + 1)
            after = state(); assert after['calls'] == before['calls'] + 1 and after['geoCalls'] == before['geoCalls']
            observed = wait_until(runtime, lambda: [line for line in runtime.transcript[offset:] if 'POLICY_OBS ' in line])
            assert len(observed) == 1 and 'reason=VPN_FLAG' in observed[0]
            return after
        denied('CGCaptureOff'); assert not archive.exists(); passed('native_default_off_keeps_real_login_and_has_no_archive')
        runtime.command('cg policy capture start 5m facts', 'Local capture ACTIVE:')
        after = denied('CGCaptureFacts'); assert not archive.exists(); passed('native_active_capture_does_not_add_requests_actions_or_observations')
        runtime.command('cg policy capture stop', 'Saved 1 local replay cases')
        content = archive.read_text(); parsed = json.loads(content)
        assert parsed['identity_redacted'] and parsed['cases'][0]['ip'] == '192.0.2.1' and '127.0.0.1' not in content
        passed('native_stop_saves_redacted_final_evaluation_inputs')
        runtime.command('cg policy test observations candidate captured', 'Saved local observations: 1; changed outcomes: 1;')
        assert state() == after; passed('native_captured_replay_has_no_extra_lookup_or_candidate_action')
        before_bytes = archive.read_bytes()
        if clients: clients.close(); clients = None
        restart_process(runtime, work)
        runtime.command('cg reload', 'Config has been reloaded!')
        if backend: clients = Clients(runtime)
        runtime.command('cg policy capture status','saved file=yes')
        assert archive.read_bytes() == before_bytes; passed('native_restart_keeps_archive_and_does_not_resume_capture')
        runtime.command('cg policy capture start 5m facts', 'Local observation capture could not complete')
        assert archive.read_bytes() == before_bytes; passed('native_existing_archive_is_preserved')
        runtime.command('cg policy capture clear','Saved local policy observations deleted')
        runtime.command('cg policy capture start 5m identities','IDENTITIES explicitly selected')
        after = denied('CGCaptureIds'); runtime.command('cg policy capture stop','Saved 1 local replay cases')
        parsed = json.loads(archive.read_text()); assert not parsed['identity_redacted'] and parsed['cases'][0]['ip'] == '127.0.0.1'
        passed('native_explicit_identity_mode_keeps_local_address')
        candidate['rules'] = [dict(id='fixture-literal',effect='DENY',scope='ALL',target='127.0.0.1',expires_at=0,reason='Owned local fixture')]
        (policy / 'candidate.json').write_text(json.dumps(candidate))
        runtime.command('cg policy test observations candidate captured','Saved local observations: 1; changed outcomes: 1;')
        assert state() == after; passed('native_identity_rule_replay_never_activates_or_adds_actions')
        # Qualify the combined D1 workflow: comparison never commits; explicit transitions do.
        candidate['rules'] = []; (policy / 'candidate.json').write_text(json.dumps(candidate))
        saved_bytes = archive.read_bytes()
        runtime.command('cg policy shadow start candidate 5m','Shadow ACTIVE: compared 0;')
        denied('CGSavedShadow')
        runtime.command('cg policy shadow status','Shadow ACTIVE: compared 1; changed outcomes: 1;')
        runtime.command('cg policy shadow stop','Shadow STOPPED: compared 1;')
        assert archive.read_bytes() == saved_bytes; passed('native_saved_replay_and_live_shadow_keep_actual_denial_and_archive')
        fingerprint = re.search(r'candidate=([a-f0-9]{64})', runtime.command('cg policy inspect candidate','candidate='))[1]
        runtime.command('cg policy status','Policy owner=')
        token = re.search(r'expected=([a-f0-9]{64})', runtime.wait('expected='))[1]
        runtime.command('cg policy activate candidate ' + fingerprint + ' ' + token,'Policy committed:')
        runtime.wait('Policy owner='); runtime.wait('expected=')
        original_revision = json.loads((data / 'access-rules.json').read_text())['revisions'][1]['id']
        offset = len(runtime.transcript); before = state()
        if backend:
            clients.write('connect CGCaptureAllow'); clients.wait('CLIENT CGCaptureAllow login')
            wait_until(runtime, lambda: any('POLICY_JOIN CGCaptureAllow' in line for line in runtime.transcript[offset:]))
            clients.write('close CGCaptureAllow'); clients.wait('CLIENT CGCaptureAllow end')
        else: assert login(runtime.port,'CGCaptureAllow') == 'LOGIN_SUCCESS'
        observed = wait_until(runtime, lambda: [line for line in runtime.transcript[offset:] if 'POLICY_OBS ' in line])
        assert len(observed) == 1 and 'reason=FLAG_ALLOWED' in observed[0] and 'outcome=ALLOW' in observed[0]
        # Disabling kicks in ENFORCE retains the configured live flag command.
        # Only comparison is action-free; explicitly activated live policy keeps its semantics.
        wait_until(runtime, lambda: state()['actions'] == before['actions'] + 1)
        after = state(); assert after['calls'] == before['calls'] + 1 and after['actions'] == before['actions'] + 1
        runtime.command('cg policy test observations candidate captured','Saved local observations: 1; changed outcomes: 0;')
        assert state() == after and archive.read_bytes() == saved_bytes
        passed('native_only_explicit_reviewed_activation_changes_admission_then_replay_matches')
        runtime.command('cg policy status','Policy owner=')
        token = re.search(r'expected=([a-f0-9]{64})', runtime.wait('expected='))[1]
        runtime.command('cg policy rollback ' + original_revision + ' ' + token,'Policy committed:')
        runtime.wait('Policy owner='); runtime.wait('expected=')
        denied('CGCaptureRolled'); assert archive.read_bytes() == saved_bytes
        passed('native_explicit_rollback_restores_actual_denial_and_preserves_observations')
        archive.write_text('{invalid}')
        runtime.command('cg doctor','Local policy observation capture status=unavailable')
        denied('CGCorruptSaved'); passed('native_corrupt_optional_archive_keeps_admission_active')
        runtime.command('cg policy capture clear','Saved local policy observations deleted')
        runtime.command('cg policy capture start 5m facts','Local capture ACTIVE:')
        invalid = copy.deepcopy(settings); invalid['operation']['mode'] = 'INVALID'; runtime.write(invalid)
        runtime.command('cg reload','Reload rejected; active settings preserved.')
        denied('CGInvalidDraft'); runtime.command('cg policy capture status','Local capture ACTIVE: session samples=1')
        passed('native_invalid_reload_keeps_existing_capture_and_live_policy')
        runtime.close()
        if clients: clients.close(); clients = None
        assert runtime.process.returncode == 0, 'Owned runtime did not stop cleanly'
        assert not archive.exists(); passed('native_shutdown_discards_unsaved_sample')
        result['status'] = 'passed'
    finally:
        if clients: clients.close()
        if runtime and runtime.process.poll() is None: runtime.close()
        if reservation: reservation.close()
        result['stopped'] = runtime is not None and runtime.process.poll() is not None
        result['runtime_exit_code'] = runtime.process.returncode if runtime is not None else None
        (work / 'receipt.json').write_text(json.dumps(result,indent=2) + '\n')
    print(json.dumps(result,indent=2))
if __name__ == '__main__': main()
