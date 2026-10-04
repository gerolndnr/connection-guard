#!/usr/bin/env python3
"""Owned native IPQS/decimal probe: fixed original JAR, loopback fixture seam, real Velocity."""
import argparse, hashlib, json, re, socket, subprocess, threading, time, zipfile
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlparse
import yaml
from release_velocity_test import ROOT, JAVA, PROXY, PROXY_SHA, login
from release_redis_velocity_test import Proxy

def main():
    parser=argparse.ArgumentParser();parser.add_argument('--artifact',type=Path,required=True);parser.add_argument('--sha256',required=True);parser.add_argument('--fixture',required=True);args=parser.parse_args()
    artifact=args.artifact.resolve();assert artifact.is_relative_to(ROOT/'repository/build/libs')
    assert re.fullmatch('[a-z0-9-]+',args.fixture) and hashlib.sha256(artifact.read_bytes()).hexdigest()==args.sha256
    assert hashlib.sha256(PROXY.read_bytes()).hexdigest()==PROXY_SHA
    work=ROOT/'.runtime/ipqs-smoke'/args.fixture;assert not work.exists();work.mkdir(parents=True,mode=0o700)
    source=ROOT/'repository/ci/fixtures/ipqualityscore';classes=work/'classes';classes.mkdir()
    subprocess.run([str(Path(JAVA).with_name('javac')),'--release','17','-proc:none','-cp',str(artifact)+':'+str(PROXY),'-d',str(classes),str(source/'NativeIpqsFixture.java')],check=True,timeout=30)
    addon=work/'addon.jar'
    with zipfile.ZipFile(addon,'w') as archive:
        for path in sorted(classes.rglob('*.class')):archive.write(path,path.relative_to(classes).as_posix())
        archive.write(source/'velocity-plugin.json','velocity-plugin.json')
    with zipfile.ZipFile(addon) as archive:assert all(not name.startswith('com/github/gerolndnr/') for name in archive.namelist())
    with zipfile.ZipFile(artifact) as archive:settings=yaml.safe_load(archive.read('config.yml'))
    for provider in settings['provider']['vpn'].values():provider['enabled']=False
    native=settings['provider']['vpn']['ipqualityscore'];native.update({'enabled':True,'api-key':'synthetic-runtime-key','daily-budget':30,'minute-budget':30})
    settings['provider']['geo']['service']='Disabled';settings['provider']['cache']['type']='SQLite'
    settings['behavior']['vpn']['exemptions']=[];settings['behavior']['vpn']['notify-staff']=False
    settings['behavior']['geo']['exemptions']=['127.0.0.1'];settings['operation']['mode']='ENFORCE'
    settings['failure-policy']={'vpn':'CLOSED','geo':'OPEN'}
    settings['integrations']['observers']={'enabled':True,'ids':['ipqs-fixture']}
    settings['lookup']['circuit']['failures']=100
    state={'calls':0,'status':200,'vpn':True,'risk':79.999,'body':None,'invalid':False}
    class Handler(BaseHTTPRequestHandler):
        def do_GET(self):
            uri=urlparse(self.path);values=parse_qs(uri.query)
            valid=(uri.path=='/api/json/ip' and values=={'ip':['127.0.0.1'],'strictness':['0'],'allow_public_access_points':['true'],'fast':['true']}
                   and self.headers.get('IPQS-KEY')=='synthetic-runtime-key' and 'synthetic-runtime-key' not in self.path)
            state['invalid']|=not valid;state['calls']+=1
            payload=state['body'] if state['body'] is not None else json.dumps({'success':True,'vpn':state['vpn'],'proxy':state['vpn'],'tor':False,'fraud_score':state['risk'],'ASN':64500,'ISP':'Synthetic runtime ISP','country_code':'PT'})
            body=payload.encode();self.send_response(state['status']);self.send_header('Content-Type','application/json');self.send_header('Content-Length',str(len(body)));self.end_headers();self.wfile.write(body)
        def log_message(self,*args):pass
    http=ThreadingHTTPServer(('127.0.0.1',0),Handler);thread=threading.Thread(target=http.serve_forever,daemon=True);thread.start()
    backend=socket.socket();backend.bind(('127.0.0.1',0)) # Own a nonlistening target; no unowned backend contact.
    result={'artifact_sha256':args.sha256,'addon_sha256':hashlib.sha256(addon.read_bytes()).hexdigest(),
            'fixture_source_sha256':hashlib.sha256((source/'NativeIpqsFixture.java').read_bytes()).hexdigest(),
            'driver_sha256':hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),'proxy':'Velocity 3.4.0 build 566','proxy_sha256':PROXY_SHA,'java':21,
            'backend_join_tested':False,'authenticated_identity_tested':False,'live_ipqs_tested':False,'http_fixture_endpoint_only':True,'cost_eur':0,'cases':[]}
    proxy=None
    try:
        proxy=Proxy(work/'proxy',artifact,settings,addon=addon,reserved_backend_port=backend.getsockname()[1]);proxy.ready()
        def fixture(command='status'):
            line=proxy.command('fixture-ipqs '+command,'IPQS_FIXTURE')
            value=dict(re.findall(r'(\w+)=([^ ]+)',line.strip()));assert value.get('rejected')!='true',line;return value
        def idle():
            deadline=time.monotonic()+3
            while time.monotonic()<deadline:
                if fixture()['idle']=='true':return
                time.sleep(.02)
            raise AssertionError('Native runtime not idle')
        def bind():idle();fixture('bind '+str(http.server_port))
        def clear():proxy.command('cg clear 127.0.0.1','Cleared the cache entries for');idle()
        def reload():idle();proxy.write(settings);proxy.command('cg reload','Config has been reloaded!');bind()
        def calls(expected):assert state['calls']==expected,(state['calls'],expected);assert not state['invalid']
        def observation(score,status,cached=False):
            deadline=time.monotonic()+2
            while time.monotonic()<deadline:
                value=fixture()
                if value['risk']==score and value['status']==status and value['cached']==str(cached).lower():return
                time.sleep(.02)
            raise AssertionError(value)
        def denied(name):
            response=login(proxy.port,name);assert 'unavailable' in response.lower() or 'temporarily' in response.lower(),response
        def explain(marker):
            start=len(proxy.transcript);proxy.command('cg explain 127.0.0.1',marker);proxy.wait('GEO rule=none');return ''.join(proxy.transcript[start:])
        reload()
        assert 'VPN' in login(proxy.port,'CGIpqsPositive');calls(1);observation('79.999','POSITIVE')
        assert 'VPN' in login(proxy.port,'CGIpqsPosCache');calls(1);observation('79.999','POSITIVE',True)
        trace=explain('IpQualityScoreVpnProvider#0=POSITIVE');assert 'risk=79.999' in trace and 'cached=true' in trace and 'asn=64500' in trace and 'operator=UNKNOWN' in trace
        result['cases']+=['native-header-options-real-login','positive-cache-and-decimal-observer','cached-explain-native-source-facts']
        state['vpn']=False;clear();assert login(proxy.port,'CGIpqsNegative')=='LOGIN_SUCCESS';calls(2);observation('79.999','NEGATIVE')
        assert login(proxy.port,'CGIpqsNegCache')=='LOGIN_SUCCESS';calls(2);observation('79.999','NEGATIVE',True)
        result['cases'].append('negative-cache-retains-decimal-facts')
        line=proxy.command('cg deny add risk:IpQualityScoreVpnProvider#0:80 vpn permanent Synthetic-boundary','Stored ')
        rule=re.search(r'Stored (\S+)',line).group(1);clear()
        assert login(proxy.port,'CGIpqsBelow')=='LOGIN_SUCCESS';calls(3)
        state['risk']=80.001;clear();response=login(proxy.port,'CGIpqsAbove');assert 'policy' in response.lower(),response;calls(4);observation('80.001','NEGATIVE')
        result['cases'].append('actual-admission-source-decimal-rule-boundary')
        proxy.command('cg deny remove '+rule,'Rule removed.');clear();assert login(proxy.port,'CGIpqsRiskOnly')=='LOGIN_SUCCESS';calls(5)
        result['cases'].append('high-risk-alone-never-invents-vpn-vote')
        settings['provider']['vpn']['ipqualityscore']['daily-budget']=6;reload();clear()
        assert login(proxy.port,'CGIpqsBudgetOne')=='LOGIN_SUCCESS';calls(6);clear();denied('CGIpqsBudgetTwo');calls(6)
        assert 'BUDGET_EXHAUSTED' in explain('IpQualityScoreVpnProvider#0=UNKNOWN');calls(6)
        result['cases'].append('local-budget-retained-through-real-reload')
        settings['provider']['vpn']['ipqualityscore']['daily-budget']=30;reload()
        state['status']=503;clear();denied('CGIpqsHttp503');calls(7)
        state['status']=200;state['body']='synthetic invalid JSON';clear();denied('CGIpqsBadJson');calls(8)
        result['cases'].append('http-and-schema-failures-never-negative')
        state['body']=None;state['risk']=0;clear();assert login(proxy.port,'CGIpqsRecovered')=='LOGIN_SUCCESS';calls(9);observation('0','NEGATIVE')
        result['cases'].append('unknown-not-cached-and-explicit-zero-preserved')
        state['body']='{"success":false,"message":"You have insufficient credits for the synthetic fixture."}';clear();denied('CGIpqsCredits');calls(10)
        denied('CGIpqsPaused');calls(10);assert 'CIRCUIT_OPEN' in explain('IpQualityScoreVpnProvider#0=UNKNOWN');calls(10)
        result['cases'].append('http-200-credit-exhaustion-pauses-native-lookups')
        assert not state['invalid'];assert 'synthetic-runtime-key' not in ''.join(proxy.transcript)
        result['http_requests']=state['calls'];result['header_or_options_invalid']=False
        proxy.close();assert proxy.process.returncode==0
        assert not re.search(r'\b(ERROR|Exception|FAILURE)\b',''.join(proxy.transcript)),'Inspect private runtime log'
        result['shutdown_passed']=True
    finally:
        if proxy is not None:proxy.close()
        http.shutdown();http.server_close();thread.join(timeout=3);backend.close()
        assert not thread.is_alive()
    result['owned_http_stopped']=True
    result['console_sha256']=hashlib.sha256((work/'proxy/smoke.log').read_bytes()).hexdigest()
    (work/'result.json').write_text(json.dumps(result,indent=2)+'\n');print(json.dumps(result,indent=2),flush=True)
if __name__=='__main__':main()
