#!/usr/bin/env python3
"""Owned synthetic policy/replay qualification on four pinned runtimes; no live accounts or telemetry."""
import argparse, concurrent.futures, copy, hashlib, json, os, re, socket, subprocess, time, zipfile
from policy_version_runtime_cases import run_versions, restart_process
from pathlib import Path
import yaml
from release_velocity_test import ROOT, JAVA, PROXY, PROXY_SHA, login
from release_redis_velocity_test import Proxy
from release_native_bungee_test import Bungee, RUNTIME, RUNTIME_SHA
from backend_fixture import Backend, RUNTIMES
from release_backend_test import Clients

def digest(path): return hashlib.sha256(path.read_bytes()).hexdigest()
def wait_until(runtime, test, seconds=15):
    end=time.monotonic()+seconds
    while time.monotonic()<end:
        value=test()
        if value: return value
        if runtime.process.poll() is not None: raise RuntimeError('Owned runtime exited before evidence')
        time.sleep(.02)
    raise RuntimeError('Missing owned policy evidence')

def main():
    parser=argparse.ArgumentParser();parser.add_argument('--checkout',choices=['pr77-integration'],default='pr77-integration');parser.add_argument('--platform',choices=['velocity','bungee','paper','folia'],required=True)
    parser.add_argument('--fixture',required=True);parser.add_argument('--artifact',type=Path,required=True);parser.add_argument('--sha256',required=True);args=parser.parse_args()
    checkout=ROOT/args.checkout;artifact=args.artifact.resolve()
    assert artifact.is_relative_to(checkout/'build/libs') and digest(artifact)==args.sha256
    ci=json.loads((ROOT/'delivery/pr-integration/77/ci-verified.json').read_text())
    assert ci['source']==subprocess.check_output(['git','-C',str(checkout),'rev-parse','HEAD'],text=True).strip()
    assert ci['conclusion']=='success' and ci['archives'][0]['sha256']==args.sha256
    # This explicit merge request authorizes the bounded native qualification.
    # Run it before the new comparative measurement so the JVMs cannot distort timing.
    active = subprocess.check_output(['docker', 'ps', '--format', '{{.Names}}'], text=True).strip()
    assert not active, 'Native qualification must not overlap Docker measurements'
    os.environ['CONNECTIONGUARD_TOR_REFRESH']='false'
    os.environ['CONNECTIONGUARD_INTEL_REFRESH']='false'

    assert re.fullmatch('[a-z0-9-]+',args.fixture)
    os.environ['CONNECTIONGUARD_CLOUD']='false'
    backend=args.platform in RUNTIMES
    base=ROOT/'.runtime'/('backend-smoke' if backend else 'native-bungee-smoke' if args.platform=='bungee' else 'policy-smoke')
    work=base/args.fixture;assert not work.exists();work.mkdir(parents=True,mode=0o700)
    fixtures=checkout/'ci/fixtures/policy';classes=work/'classes';classes.mkdir()
    if backend:
        libraries=sorted((ROOT/'.runtime/backend-fixture/compile-libraries').glob('*.jar'))
        wrapper='PolicyBackendFixture';descriptor='plugin.yml';runtime_name,runtime_sha=RUNTIMES[args.platform]
    elif args.platform=='velocity':
        assert digest(PROXY)==PROXY_SHA;libraries=[PROXY];wrapper='PolicyVelocityFixture';descriptor='velocity-plugin.json';runtime_name,runtime_sha=PROXY.name,PROXY_SHA
    else:
        assert digest(RUNTIME)==RUNTIME_SHA;libraries=[RUNTIME];wrapper='PolicyBungeeFixture';descriptor='bungee.yml';runtime_name,runtime_sha=RUNTIME.name,RUNTIME_SHA
    sources=[fixtures/'PolicyFixtureState.java',fixtures/(wrapper+'.java')]
    subprocess.run([str(Path(JAVA).with_name('javac')),'--release','21','-proc:none','-cp',':'.join(map(str,[artifact,*libraries])),'-d',str(classes),*map(str,sources)],check=True,timeout=30)
    addon=work/'addon.jar'
    with zipfile.ZipFile(addon,'w') as archive:
        for file in sorted(classes.rglob('*.class')):archive.write(file,file.relative_to(classes).as_posix())
        archive.write(fixtures/descriptor,descriptor)
    with zipfile.ZipFile(artifact) as archive:settings=yaml.safe_load(archive.read('config.yml'))
    for provider in settings['provider']['vpn'].values():provider['enabled']=False
    settings['integrations']['providers']={'enabled':True,'sources':[{'id':'policy-fixture','voting':True}]}
    settings['integrations']['observers']={'enabled':True,'ids':['policy-observer']}
    settings['integrations']['admission']={'enabled':True,'ids':['policy-admission'],'failure-policy':'OPEN'}
    settings['provider']['geo']['service']='Disabled';settings['provider']['cache']['type']='Disabled'
    settings['operation']['mode']='ENFORCE';settings['failure-policy']={'vpn':'CLOSED','geo':'OPEN'}
    settings['lookup']['deadline-ms']=4000;settings['lookup']['http-timeout-ms']=1000
    for scope in ['vpn','geo']:
        settings['behavior'][scope]['exemptions']=[];settings['behavior'][scope]['use-permission-exemption']=False
        settings['behavior'][scope]['notify-staff']=False;settings['behavior'][scope]['send-webhook']['enabled']=False
        settings['behavior'][scope]['execute-command']={'enabled':True,'command':'fixture-policy action'}
        settings['behavior'][scope]['kick-player']=True
    original_settings=copy.deepcopy(settings)
    result={'checkout_commit':subprocess.check_output(['git','-C',str(checkout),'rev-parse','HEAD'],text=True).strip(),
        'checkout_tree':subprocess.check_output(['git','-C',str(checkout),'rev-parse','HEAD^{tree}'],text=True).strip(),
        'checkout_dirty':bool(subprocess.check_output(['git','-C',str(checkout),'status','--porcelain','--untracked-files=normal'],text=True).strip()),
        'artifact_sha256':args.sha256,'runtime':runtime_name,'runtime_sha256':runtime_sha,'java':21,'addon_sha256':digest(addon),
        'fixture_source_sha256':{file.name:digest(file) for file in sources},'driver_sha256':digest(Path(__file__)),
        'artifact_ci_run':ci['run'],'artifact_source':ci['source'],
        'version_case_driver_sha256':digest(ROOT/'tools/policy_version_runtime_cases.py'),
        'synthetic_offline_clients':True,'real_account_authentication_tested':False,'telemetry_submission_tested':False,'cases':[]}
    runtime=clients=None;reservation=None
    try:
        if backend:runtime=Backend(work/'server',args.platform,artifact,settings,addon=addon)
        elif args.platform=='bungee':runtime=Bungee(work/'proxy',artifact,addon,settings,[],{},False)
        else:
            reservation=socket.socket();reservation.bind(('127.0.0.1',0))
            runtime=Proxy(work/'proxy',artifact,settings,addon=addon,reserved_backend_port=reservation.getsockname()[1])
        runtime.ready();runtime.command('cg reload','Config has been reloaded!')
        if backend:clients=Clients(runtime)
        def state():return {k:int(v) for k,v in re.findall(r'(calls|geoCalls|actions|admissionCalls)=(\d+)',runtime.command('fixture-policy status','POLICY_STATUS'))}
        def configure():
            runtime.write(settings);runtime.command('cg reload','Config has been reloaded!')
        def connect(name,expected,reason):
            start=len(runtime.transcript)
            if backend:
                clients.write('connect '+name)
                if expected=='ALLOW':
                    clients.wait('CLIENT '+name+' login');wait_until(runtime,lambda:any('POLICY_JOIN '+name in line for line in runtime.transcript[start:]))
                    clients.write('close '+name);clients.wait('CLIENT '+name+' end')
                else:assert expected in clients.denial(name),name
            else:
                message=login(runtime.port,name)
                assert message=='LOGIN_SUCCESS' if expected=='ALLOW' else expected in message,(name,message)
            records=wait_until(runtime,lambda:[line for line in runtime.transcript[start:] if 'POLICY_OBS ' in line])
            assert len(records)==1 and 'reason='+reason in records[0] and 'outcome='+('ALLOW' if expected=='ALLOW' else 'DENY') in records[0],records
            return records[0]
        before=state();runtime.command('cg policy test','Synthetic cases: 8; changed outcomes: 0;')
        runtime.command('fixture-policy status','POLICY_STATUS')
        assert state()==before
        result['cases'].append('bundled_command_replay_has_no_provider_calls_or_live_actions')
        data=runtime.directory/'plugins'/('connection-guard' if args.platform=='velocity' else 'ConnectionGuard')/'policy';data.mkdir()
        candidate=json.loads((fixtures/'candidate.json').read_text());candidate.update(kick_vpn=False,kick_geo=False,vpn_failure='CLOSED',geo_failure='OPEN',countries=[],rules=[])
        (data/'candidate.json').write_text(json.dumps(candidate))
        runtime.command('cg policy test examples candidate','Synthetic cases: 8; changed outcomes: 1;')
        assert state()==before;result['cases'].append('candidate_comparison_has_no_activation_provider_calls_or_live_actions')
        if args.checkout=='pr77-integration':
            before=state();runtime.command('cg policy shadow start candidate 5m','Shadow ACTIVE: compared 0;')
            assert state()==before
            connect('CGShadowDenied','VPN','VPN_FLAG');wait_until(runtime,lambda:state()['actions']==before['actions']+1)
            after=state();assert after['calls']==before['calls']+1 and after['geoCalls']==before['geoCalls']
            runtime.command('cg policy shadow status','Shadow ACTIVE: compared 1; changed outcomes: 1;')
            assert state()==after
            result['cases'].append('live_denial_preserved_with_one_provider_call_action_and_observation_while_candidate_allows')
            runtime.command('cg policy shadow stop','Shadow STOPPED: compared 1;')
            before=state();connect('CGShadowStopped','VPN','VPN_FLAG');wait_until(runtime,lambda:state()['actions']==before['actions']+1)
            runtime.command('cg policy shadow status','Shadow STOPPED: compared 1;')
            result['cases'].append('stopped_window_does_not_count_subsequent_real_login')
            settings['operation']['mode']='OBSERVE';configure()
            candidate.update(kick_vpn=True,kick_geo=True)
            (data/'candidate.json').write_text(json.dumps(candidate))
            runtime.command('cg policy shadow start candidate','Shadow ACTIVE: compared 0;')
            before=state();connect('CGShadowAllowed','ALLOW','FLAG_ALLOWED');after=state()
            assert after['calls']==before['calls']+1 and after['geoCalls']==before['geoCalls'] and after['actions']==before['actions']
            runtime.command('cg policy shadow status','Shadow ACTIVE: compared 1; changed outcomes: 1;')
            result['cases'].append('candidate_denial_never_kicks_or_adds_actions_to_actual_allowed_login')
            settings['operation']['mode']='INVALID';runtime.write(settings);runtime.command('cg reload','Reload rejected; active settings preserved.')
            before=state();connect('CGShadowBad','ALLOW','FLAG_ALLOWED');assert state()['actions']==before['actions']
            runtime.command('cg policy shadow status','Shadow ACTIVE: compared 2; changed outcomes: 2;')
            result['cases'].append('invalid_reload_preserves_active_shadow_and_live_policy')
            settings['operation']['mode']='ENFORCE';configure()
            runtime.command('cg policy shadow status','Shadow BASE_CHANGED: compared 2;')
            result['cases'].append('successful_reload_stops_shadow_without_mixing_base_policies')
            runtime.command('cg policy shadow start candidate','Shadow ACTIVE: compared 0;')
            runtime.command('fixture-policy geo','POLICY_STATUS')
            runtime.command('cg policy shadow status','Shadow BASE_CHANGED: compared 0;')
            result['cases'].append('changed_geo_provider_stops_shadow_before_next_comparison')
            configure()
        before=state();connect('CGPolicyVpn','VPN','VPN_FLAG');wait_until(runtime,lambda:state()['actions']==before['actions']+1)
        result['cases'].append('real_positive_login_obeys_unchanged_active_vpn_kick')
        settings['operation']['mode']='OBSERVE';configure();before=state()
        connect('CGPolicyObserve','ALLOW','FLAG_ALLOWED');assert state()['actions']==before['actions']
        result['cases'].append('observe_allows_actual_positive_without_flag_command')
        settings['operation']['mode']='ENFORCE';settings['behavior']['geo']['type']='BLACKLIST';settings['behavior']['geo']['list']=['DE'];configure()
        runtime.command('fixture-policy negative','POLICY_STATUS');runtime.command('fixture-policy geo','POLICY_STATUS')
        before=state();connect('CGPolicyGeo','not allowed','GEO_FLAG');wait_until(runtime,lambda:state()['actions']==before['actions']+1)
        result['cases'].append('real_geo_blacklist_uses_shared_policy')
        settings['behavior']['geo']['type']='WHITELIST';configure();runtime.command('fixture-policy geo','POLICY_STATUS')
        connect('CGPolicyWhite','ALLOW','CHECKS_COMPLETE');result['cases'].append('real_geo_whitelist_allows_listed_country')
        settings['behavior']['geo']['list']=[];configure();runtime.command('fixture-policy geo','POLICY_STATUS')
        connect('CGPolicyEmpty','not allowed','GEO_FLAG');result['cases'].append('real_empty_geo_whitelist_blocks_known_country')
        settings['behavior']['geo']['list']=['de'];runtime.write(settings);runtime.command('cg reload','Reload rejected; active settings preserved.')
        connect('CGPolicyInvalid','not allowed','GEO_FLAG');result['cases'].append('invalid_country_draft_preserves_actual_active_policy')
        settings['behavior']['geo']['list']=[];settings['behavior']['geo']['type']='BLACKLIST'
        settings['behavior']['vpn']['exemptions']=['127.0.0.1'];configure();runtime.command('fixture-policy geo','POLICY_STATUS')
        runtime.command('fixture-policy hold','POLICY_STATUS');start=len(runtime.transcript)
        def pending_connect():
            if backend:
                clients.write('connect CGPolicyLate');return None
            return login(runtime.port,'CGPolicyLate')
        with concurrent.futures.ThreadPoolExecutor(max_workers=1) as pool:
            future=pool.submit(pending_connect)
            wait_until(runtime,lambda:any('POLICY_GEO_WAITING' in line for line in runtime.transcript[start:]))
            runtime.command('cg deny add 127.0.0.1 vpn 5m Synthetic late deny','Stored rule-')
            runtime.command('fixture-policy release','POLICY_STATUS')
            if backend:assert 'access policy' in clients.denial('CGPolicyLate')
            else:assert 'access policy' in future.result(timeout=10)
        records=wait_until(runtime,lambda:[line for line in runtime.transcript[start:] if 'POLICY_OBS ' in line])
        assert len(records)==1 and 'reason=ACCESS_RULE' in records[0] and 'vpn=EXEMPT' in records[0],records
        result['cases'].append('deny_added_during_pending_geo_overrides_prior_vpn_exemption')
        # Each suite starts from the native decision fields and legacy rules.
        # Removing the stock late-DENY preserves its completed proof above.
        for rule in json.loads((data.parent/'access-rules.json').read_text()):
            runtime.command('cg '+rule['effect'].lower()+' remove '+rule['id'],'Rule removed.')
        settings.clear();settings.update(original_settings);configure()
        runtime.command('fixture-policy positive','POLICY_STATUS')
        def restart():
            nonlocal clients
            if clients:
                clients.close();(work/'before-restart-client.log').write_text(''.join(clients.transcript));clients=None
            restart_process(runtime,work)
            # Native addons register after CG's initial settings preparation.
            # Select their real handles before the post-restart login.
            runtime.command('cg reload','Config has been reloaded!')
            if backend:clients=Clients(runtime)
        def version_pending(name):
            if backend:
                clients.write('connect '+name);return clients.denial(name)
            return login(runtime.port,name)
        run_versions(runtime,connect,state,configure,restart,settings,data.parent,result,version_pending)
        result['state']=state()
        if clients:clients.close();(work/'client.log').write_text(''.join(clients.transcript))
        runtime.close();assert runtime.process.returncode==0
        previous_log=work/'before-restart.log'
        combined=(previous_log.read_text() if previous_log.exists() else '')+''.join(runtime.transcript)
        assert not re.search(r'Error occurred while|Error enabling plugin|Unable to initialize plugin|NoClassDefFoundError|NoSuchMethodError|Could not dispatch event|Exception in thread|Thread failed main thread check',combined)
        result['before_restart_log_sha256']=digest(previous_log)
        result['actual_decision_observations']=combined.count('POLICY_OBS ')
        result['actual_backend_join_events']=combined.count('POLICY_JOIN ')
        result['state_counts_scope']='last native process after restart; observations/joins include both process logs'

        result['shutdown_passed']=True;result['log_sha256']=digest(runtime.directory/'smoke.log')
        result['deployed_jar_unchanged']=digest(runtime.directory/'plugins/connection-guard.jar')==args.sha256
        assert result['deployed_jar_unchanged']
        (work/'result.json').write_text(json.dumps(result,indent=2)+'\n');print(json.dumps(result,indent=2),flush=True)
    finally:
        if runtime and runtime.process.poll() is None:
            runtime.process.stdin.write('fixture-policy release-admission\nfixture-policy release\n');runtime.process.stdin.flush()
        if clients:clients.close()
        if runtime:runtime.close()
        if reservation:reservation.close()
if __name__=='__main__':main()
