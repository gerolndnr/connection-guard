#!/usr/bin/env python3
"""Workspace-specific owned native profile/quota check; pinned local runtimes, no provider API."""
import argparse,copy,hashlib,json,os,re,socket,subprocess,sys,time,threading,zipfile
from pathlib import Path
from datetime import datetime,timezone
from http.server import BaseHTTPRequestHandler,ThreadingHTTPServer
CHECKOUT=Path(__file__).resolve().parents[3];ROOT=CHECKOUT.parent;sys.path.insert(0,str(ROOT/'tools'))
import yaml
from release_velocity_test import JAVA,PROXY,PROXY_SHA,login
from release_redis_velocity_test import Proxy
from release_native_bungee_test import Bungee,RUNTIME,RUNTIME_SHA
from backend_fixture import Backend,RUNTIMES
from release_backend_test import Clients

def digest(path):return hashlib.sha256(path.read_bytes()).hexdigest()
def wait_until(runtime,test,seconds=20):
    end=time.monotonic()+seconds
    while time.monotonic()<end:
        value=test()
        if value:return value
        if runtime.process.poll() is not None:raise RuntimeError('Owned runtime ended before evidence')
        time.sleep(.02)
    raise RuntimeError('Owned native profile evidence missing')

def main():
    p=argparse.ArgumentParser();p.add_argument('--platform',choices=['velocity','bungee','paper','folia'],required=True)
    p.add_argument('--artifact',type=Path,required=True);p.add_argument('--sha256',required=True);p.add_argument('--fixture',required=True);a=p.parse_args()
    artifact=a.artifact.resolve(strict=True);assert artifact.is_relative_to(CHECKOUT/'build/libs') and digest(artifact)==a.sha256
    assert re.fullmatch('[a-z0-9-]+',a.fixture)
    source=subprocess.check_output(['git','rev-parse','HEAD'],cwd=CHECKOUT,text=True).strip()
    assert not subprocess.check_output(['git','status','--porcelain'],cwd=CHECKOUT,text=True).strip()
    for field in ['CONNECTIONGUARD_CLOUD','CONNECTIONGUARD_INTEL_REFRESH','CONNECTIONGUARD_TOR_REFRESH']:os.environ[field]='false'
    backend=a.platform in RUNTIMES;scope='backend-smoke' if backend else 'native-bungee-smoke' if a.platform=='bungee' else 'api-profile-smoke'
    work=ROOT/'.runtime'/scope/a.fixture;assert not work.exists();work.mkdir(parents=True,mode=0o700)
    fixtures=CHECKOUT/'ci/fixtures/policy';classes=work/'classes';classes.mkdir()
    if backend:
        libs=sorted((ROOT/'.runtime/backend-fixture/compile-libraries').glob('*.jar'));wrapper='PolicyBackendFixture';descriptor='plugin.yml';runtime_name,runtime_sha=RUNTIMES[a.platform]
    elif a.platform=='bungee':
        assert digest(RUNTIME)==RUNTIME_SHA;libs=[RUNTIME];wrapper='PolicyBungeeFixture';descriptor='bungee.yml';runtime_name,runtime_sha=RUNTIME.name,RUNTIME_SHA
    else:
        assert digest(PROXY)==PROXY_SHA;libs=[PROXY];wrapper='PolicyVelocityFixture';descriptor='velocity-plugin.json';runtime_name,runtime_sha='Velocity 3.4.0-566',PROXY_SHA
    subprocess.run([str(Path(JAVA).with_name('javac')),'--release','21','-proc:none','-cp',':'.join(map(str,[artifact,*libs])),'-d',str(classes),str(fixtures/'PolicyFixtureState.java'),str(fixtures/(wrapper+'.java'))],check=True,timeout=30)
    addon=work/'addon.jar'
    with zipfile.ZipFile(addon,'w') as z:
        for file in sorted(classes.rglob('*.class')):z.write(file,file.relative_to(classes).as_posix())
        z.write(fixtures/descriptor,descriptor)
    state={'requests':0}
    class Handler(BaseHTTPRequestHandler):
        def do_GET(self):
            assert self.client_address[0]=='127.0.0.1';state['requests']+=1;body=b'{"vpn":true}'
            self.send_response(200);self.send_header('Content-Length',str(len(body)));self.end_headers();self.wfile.write(body)
        def log_message(self,*args):pass
    http=ThreadingHTTPServer(('127.0.0.1',0),Handler);threading.Thread(target=http.serve_forever,daemon=True).start()
    with zipfile.ZipFile(artifact) as z:settings=yaml.safe_load(z.read('config.yml'))
    for provider in settings['provider']['vpn'].values():provider['enabled']=False
    settings['provider']['vpn']['profile-api']={'enabled':True,'request-url':'http://127.0.0.1:'+str(http.server_port)+'/fixture/%IP%',
        'response-format':{'is-vpn-field':{'field-name':'vpn'}}}
    settings['provider']['geo']['service']='Disabled';settings['provider']['cache']['type']='Disabled'
    settings['provider']['local']['connectionguard-intel']['enabled']=False
    settings['lookup']['api-policy']='CONFIGURED';settings['lookup']['api-daily-budget']=0
    settings['operation']['mode']='ENFORCE';settings['failure-policy']={'vpn':'OPEN','geo':'OPEN'}
    settings['integrations']['providers']={'enabled':False,'sources':[]};settings['integrations']['admission']={'enabled':False,'ids':[]}
    settings['integrations']['observers']={'enabled':True,'ids':['policy-observer']}
    for scope_name in ['vpn','geo']:
        s=settings['behavior'][scope_name];s['exemptions']=[];s['use-permission-exemption']=False;s['notify-staff']=False;s['send-webhook']['enabled']=False
        s['execute-command']={'enabled':True,'command':'fixture-policy action'};s['kick-player']=True
    result=dict(schema=1,source=source,artifact_sha256=a.sha256,runtime=runtime_name,runtime_sha256=runtime_sha,addon_sha256=digest(addon),
                driver_sha256=digest(Path(__file__)),synthetic_offline_clients=True,real_account_authentication=False,telemetry=False,cases=[])
    runtime=clients=reservation=None
    def passed(name):result['cases'].append(name)
    try:
        if backend:runtime=Backend(work/'server',a.platform,artifact,settings,addon=addon)
        elif a.platform=='bungee':runtime=Bungee(work/'proxy',artifact,addon,settings,[],{},False)
        else:
            reservation=socket.socket();reservation.bind(('127.0.0.1',0));runtime=Proxy(work/'proxy',artifact,settings,addon=addon,reserved_backend_port=reservation.getsockname()[1])
        runtime.ready();runtime.command('cg reload','Config has been reloaded!')
        if backend:clients=Clients(runtime)
        data=runtime.directory/'plugins'/('connection-guard' if a.platform=='velocity' else 'ConnectionGuard')
        def status():return {key:int(value) for key,value in re.findall(r'(calls|geoCalls|actions)=(\d+)',runtime.command('fixture-policy status','POLICY_STATUS'))}
        def connect(name,allow,reason):
            offset=len(runtime.transcript);before=status()
            if backend:
                clients.write('connect '+name)
                if allow:
                    clients.wait('CLIENT '+name+' login');wait_until(runtime,lambda:any('POLICY_JOIN '+name in line for line in runtime.transcript[offset:]));clients.write('close '+name);clients.wait('CLIENT '+name+' end')
                else:assert 'VPN' in clients.denial(name)
            else:assert login(runtime.port,name)=='LOGIN_SUCCESS' if allow else 'VPN' in login(runtime.port,name)
            records=wait_until(runtime,lambda:[line for line in runtime.transcript[offset:] if 'POLICY_OBS ' in line])
            assert len(records)==1 and 'reason='+reason in records[0] and 'outcome='+('ALLOW' if allow else 'DENY') in records[0]
            if not allow:wait_until(runtime,lambda:status()['actions']==before['actions']+1)
            after=status();assert after['calls']==0 and after['geoCalls']==0 and after['actions']==before['actions']+(0 if allow else 1)
        connect('CGProfileBase',False,'VPN_FLAG');assert state['requests']==1;passed('native_configured_default_keeps_one_actual_http_denial')
        old=(data/'config.yml').read_bytes();runtime.command('cg profile show local-only','api-policy: LOCAL_ONLY');runtime.command('cg profile show budgeted-api 1','api-daily-budget: 1')
        runtime.command('cg profile show observe','mode: OBSERVE');assert (data/'config.yml').read_bytes()==old and state['requests']==1;passed('native_profile_previews_preserve_configuration_and_requests')
        settings['lookup']['api-daily-budget']=1;runtime.write(settings);runtime.command('cg reload','Config has been reloaded!')
        for i in range(52):connect('CGQuota'+str(i),True,'UNKNOWN_ALLOWED')
        assert state['requests']==1;runtime.command('cg doctor','API daily reservations=1 limit=1 deniedReservations=52')
        runtime.wait('VPN uncheckedAllowed=52');passed('native_52_beyond_shared_budget_are_unknown_counted_and_make_no_http')
        # A final partial-window warning is a genuine integration check, not a five-minute timing measurement.
        settings['lookup']['api-policy']='LOCAL_ONLY';settings['provider']['vpn']['local']['enabled']=True
        settings['provider']['local']['sources']=[dict(id='profile-local',type='VPN',source='Owned synthetic loopback list',license='MIT',notice='Synthetic only',**{'max-age-hours':24})]
        runtime.write(settings);runtime.command('cg reload','Config has been reloaded!')
        runtime.command('cg local prepare','Private local-data/inbox is ready')
        inbox=data/'local-data/inbox';assert inbox.is_dir();(inbox/'owned-profile.txt').write_text('127.0.0.1/32\n')
        as_of=datetime.now(timezone.utc).isoformat().replace('+00:00','Z')
        runtime.command('cg local import profile-local owned-profile.txt '+as_of,'Validated local generation activated')
        connect('CGProfileLocal',False,'VPN_FLAG');assert state['requests']==1;passed('native_local_list_protects_with_empty_api_budget_and_local_only')
        settings['provider']['vpn-failover']['enabled']=False;settings['required-positive-flags']=2
        runtime.write(settings);runtime.command('cg reload','Config has been reloaded!');connect('CGLocalQuorum',True,'UNKNOWN_ALLOWED')
        assert state['requests']==1;runtime.command('cg doctor','effectiveThreshold=2');passed('native_local_only_retains_two_vote_consensus_and_unknown')
        settings['provider']['vpn']['local']['enabled']=False;settings['provider']['vpn-failover']['enabled']=True;settings['required-positive-flags']=1
        settings['lookup']['api-policy']='CONFIGURED';settings['lookup']['api-daily-budget']=2
        runtime.write(settings);runtime.command('cg reload','Config has been reloaded!');connect('CGRaisedCap',False,'VPN_FLAG');assert state['requests']==2
        passed('native_explicit_raise_allows_one_more_call_without_resetting_usage')
        settings['lookup']['api-daily-budget']=3;runtime.write(settings);runtime.command('cg reload','Config has been reloaded!')
        invalid=copy.deepcopy(settings);invalid['lookup']['api-policy']='INVALID';runtime.write(invalid);runtime.command('cg reload','Reload rejected; active settings preserved.')
        connect('CGInvalidApi',False,'VPN_FLAG');assert state['requests']==3;passed('native_invalid_profile_reload_preserves_prior_policy_and_counter')
        runtime.close()
        if clients:clients.close();clients=None
        assert runtime.process.returncode==0
        assert any('logins admitted without a conclusive VPN check' in line and 'BUDGET_EXHAUSTED=52' in line for line in runtime.transcript)
        passed('native_shutdown_emits_actual_partial_unchecked_summary_with_budget_reason')
        result.update(status='passed',owned_http_requests=state['requests'],external_provider_requests=0,periodic_five_minute_timing_tested=False)
    finally:
        if clients:clients.close()
        if runtime and runtime.process.poll() is None:runtime.close()
        if reservation:reservation.close()
        http.shutdown();http.server_close()
        result.update(stopped=runtime is not None and runtime.process.poll() is not None,runtime_exit_code=runtime.process.returncode if runtime else None)
        (work/'receipt.json').write_text(json.dumps(result,indent=2)+'\n')
    print(json.dumps(result,indent=2))
if __name__=='__main__':main()
