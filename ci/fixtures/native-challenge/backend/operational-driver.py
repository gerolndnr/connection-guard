#!/usr/bin/env python3
"""Owned actual map/chat challenge through Velocity to explicitly approved Paper/Folia backends."""
import argparse
import hashlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
from pathlib import Path
import re
import secrets
import subprocess
import threading
import time
import uuid
import zipfile
import yaml
from backend_fixture import Backend, RUNTIMES
from release_native_challenge_test import Clients, FIXTURE, NATIVE, NATIVE_SHA
from release_redis_velocity_test import Proxy
from release_velocity_test import ROOT, JAVA, PROXY, PROXY_SHA

TIMEOUT = 6

def offline_id(name):
    data = bytearray(hashlib.md5(('OfflinePlayer:' + name).encode()).digest())
    data[6] = (data[6] & 15) | 48
    data[8] = (data[8] & 63) | 128
    return str(uuid.UUID(bytes=bytes(data)))

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--fixture', required=True)
    parser.add_argument('--artifact', type=Path, required=True)
    parser.add_argument('--sha256', required=True)
    parser.add_argument('--addon-sha256', required=True)
    parser.add_argument('--platform', choices=RUNTIMES, required=True)
    args = parser.parse_args()
    assert re.fullmatch('[a-z0-9-]+', args.fixture)
    assert hashlib.sha256(PROXY.read_bytes()).hexdigest() == PROXY_SHA
    assert hashlib.sha256(NATIVE.read_bytes()).hexdigest() == NATIVE_SHA
    artifact = args.artifact.resolve()
    assert artifact.is_relative_to(ROOT/'repository/build/libs') and not args.artifact.is_symlink()
    assert re.fullmatch('[0-9a-f]{64}',args.sha256) and hashlib.sha256(artifact.read_bytes()).hexdigest()==args.sha256
    addon = next((ROOT/'repository/adapters/limbo/build/libs').glob('*.jar'))
    assert re.fullmatch('[0-9a-f]{64}',args.addon_sha256) and hashlib.sha256(addon.read_bytes()).hexdigest()==args.addon_sha256
    work = ROOT/'.runtime/backend-smoke'/args.fixture
    assert not work.exists()
    work.mkdir(parents=True, mode=0o700)
    classes = work/'classes'
    classes.mkdir()
    libraries = sorted((ROOT/'.runtime/backend-fixture/compile-libraries').glob('*.jar'))
    source = FIXTURE/'backend/CGChallengeBackendFixture.java'
    subprocess.run([str(Path(JAVA).with_name('javac')), '--release','21','-proc:none',
                    '-cp', ':'.join(map(str,libraries)), '-d',str(classes),str(source)], check=True, timeout=30)
    backend_addon = work/'backend-events.jar'
    with zipfile.ZipFile(backend_addon,'w') as z:
        for path in sorted(classes.rglob('*.class')):
            z.write(path,path.relative_to(classes).as_posix())
        z.write(FIXTURE/'backend/plugin.yml','plugin.yml')
    # Independent solver reads the actual received map, never addon state or its response receipt.
    subprocess.run([str(Path(JAVA).with_name('javac')), '-d',str(ROOT/'.runtime/native-integration-fixture'),
                    str(FIXTURE/'MapDigits.java')], check=True, timeout=30)
    with zipfile.ZipFile(artifact) as z:
        settings = yaml.safe_load(z.read('config.yml'))
    for provider in settings['provider']['vpn'].values():
        provider['enabled'] = False
    settings['provider']['cache']['type'] = 'disabled'
    settings['provider']['geo']['service'] = 'Disabled'
    settings['operation'] = {'mode':'ENFORCE'}
    settings['behavior']['vpn'].update({'exemptions':[],'notify-staff':False,'use-permission-exemption':False})
    settings['behavior']['geo'].update({'exemptions':['127.0.0.1'],'notify-staff':False,'use-permission-exemption':False})
    settings['failure-policy'] = {'vpn':'CLOSED','geo':'OPEN'}
    requests = []
    positive = {'value':True}
    class Handler(BaseHTTPRequestHandler):
        def do_GET(self):
            requests.append(time.monotonic())
            body = json.dumps({'data':{'isVpn':positive['value'],'vpnProvider':'Synthetic backend challenge provider'}}).encode()
            self.send_response(200)
            self.send_header('Content-Type','application/json')
            self.send_header('Content-Length',str(len(body)))
            self.end_headers()
            self.wfile.write(body)
        def log_message(self,*args): pass
    http = ThreadingHTTPServer(('127.0.0.1',0),Handler)
    threading.Thread(target=http.serve_forever,daemon=True).start()
    settings['provider']['vpn']['custom'].update({'enabled':True,'request-url':'http://127.0.0.1:'+str(http.server_port)+'/%IP%','request-header':[]})
    backend_settings = yaml.safe_load(yaml.safe_dump(settings))
    backend_settings['provider']['vpn']['custom']['enabled'] = False
    backend_settings['failure-policy']['vpn'] = 'OPEN'
    secret = secrets.token_hex(32)
    configs = {
        'limboapi/config.yml':'main:\n  check-for-updates: false\n  logging-enabled: false\n  prepare-min-version: "1_21_11"\n  prepare-max-version: "1_21_11"\n  view-distance: 2\n  simulation-distance: 2\n  chunk-radius-send-on-spawn: 1\n',
        'connection-guard-limbo/challenge.properties':'enabled=true\ntimeout-seconds='+str(TIMEOUT)+'\nmaximum-attempts=3\nmaximum-sessions=1\n',
    }
    result = {'status':'running','platform':args.platform,'runtime':RUNTIMES[args.platform][0],
              'runtime_sha256':RUNTIMES[args.platform][1],'java':21,'proxy_sha256':PROXY_SHA,
              'main_sha256':hashlib.sha256(artifact.read_bytes()).hexdigest(),
              'addon_sha256':hashlib.sha256(addon.read_bytes()).hexdigest(),'native_sha256':NATIVE_SHA,
              'backend_events_addon_sha256':hashlib.sha256(backend_addon.read_bytes()).hexdigest(),
              'backend_events_source_sha256':hashlib.sha256(source.read_bytes()).hexdigest(),
              'client_source_sha256':hashlib.sha256((FIXTURE/'client.cjs').read_bytes()).hexdigest(),
              'client_version':'1.21.11','actual_authenticated_accounts':False,
              'backend_join_tested':False,'modern_forwarding':'fresh owned synthetic gateway MAC; not account authentication',
              'timeout_seconds':TIMEOUT,'maximum_sessions':1,'cases':[]}
    backend = None
    proxy = None
    clients = None
    def passed(case):
        result['cases'].append(case)
        print('PASS '+case,flush=True)
    def joins():
        return [line for line in backend.transcript if 'CHALLENGE_BACKEND_JOIN ' in line]
    def wait_join(name,start):
        end = time.monotonic()+15
        marker = 'CHALLENGE_BACKEND_JOIN name='+name+' uuid='+offline_id(name)
        while time.monotonic()<end:
            if any(marker in line for line in backend.transcript[start:]): return
            if backend.process.poll() is not None or proxy.process.poll() is not None:
                raise RuntimeError('Owned runtime exited before actual backend join')
            time.sleep(.025)
        raise RuntimeError('No actual backend join for '+name)
    def alive(name):
        clients.command('state '+name)
        state = clients.wait(name,'connection_state')
        assert state == {'state':'play','socketDestroyed':False}, state
        line = backend.command('fixture-challenge-presence '+name,'CHALLENGE_BACKEND_PRESENT name='+name)
        assert 'present=true uuid='+offline_id(name) in line, line
    try:
        backend = Backend(work/'server',args.platform,artifact,backend_settings,addon=backend_addon,native_forwarding_secret=secret)
        backend.ready()
        proxy = Proxy(work/'proxy',artifact,settings,extra_plugins=[addon,NATIVE],extra_plugin_configs=configs,
                      owned_backend_port=backend.port,native_forwarding_secret=secret)
        proxy.ready()
        assert any('challenge enabled; no identity' in line for line in proxy.transcript)
        clients = Clients(proxy.port,work,version='1.21.11')
        clients.command('connect CGUnsolved')
        code = clients.solve('CGUnsolved')
        time.sleep(.25)
        assert not joins() and not requests
        clients.command('close CGUnsolved')
        clients.wait('CGUnsolved','end')
        passed('actual pending modern client never reaches backend or provider')
        clients.command('connect CGWrong')
        code = clients.solve('CGWrong')
        wrong = str((int(code[0])+1)%10)+code[1:]
        for _ in range(3): clients.command('answer CGWrong '+wrong)
        clients.wait('CGWrong','end')
        assert not joins() and not requests
        passed('actual wrong chat responses refuse before backend')
        clients.command('connect CGExpired')
        clients.solve('CGExpired')
        clients.wait('CGExpired','end',TIMEOUT+5)
        assert not joins() and not requests
        passed('actual pending expiry refuses before backend')
        clients.command('connect CGVpn')
        code = clients.solve('CGVpn')
        clients.command('answer CGVpn '+code)
        clients.wait('CGVpn','end')
        assert len(requests)==1 and not joins()
        assert any('VPN' in str(e['value']) for e in clients.saved if e['name']=='CGVpn' and e['kind'] in ('kick_disconnect','disconnect'))
        passed('solved actual challenge still retains normal VPN refusal before backend')
        positive['value'] = False
        start = len(backend.transcript)
        clients.command('connect CGJoinedOne')
        old_code = clients.solve('CGJoinedOne')
        first_map_at = time.monotonic()
        clients.command('answer CGJoinedOne '+old_code)
        wait_join('CGJoinedOne',start)
        assert len(requests)==2 and len(joins())==1
        passed('actual solved challenge reaches genuine backend PlayerJoinEvent with native forwarded synthetic UUID')
        # Capacity=1: while the first admitted player stays online, another owned session must get a map.
        clients.command('connect CGJoinedTwo')
        code = clients.solve('CGJoinedTwo')
        second_map_at = time.monotonic()
        start = len(backend.transcript)
        clients.command('answer CGJoinedTwo '+code)
        wait_join('CGJoinedTwo',start)
        assert len(requests)==3 and len(joins())==2
        passed('real backend admission consumes pending capacity while earlier player remains connected')
        time.sleep(max(0,TIMEOUT+1.25-(time.monotonic()-max(first_map_at,second_map_at))))
        alive('CGJoinedOne')
        alive('CGJoinedTwo')
        passed('both genuinely joined clients remain online past challenge deadline without delayed kick')
        clients.command('close CGJoinedOne')
        clients.wait('CGJoinedOne','end')
        backend.wait('CHALLENGE_BACKEND_QUIT name=CGJoinedOne')
        before = len(joins())
        start = len(backend.transcript)
        clients.command('connect CGJoinedOne')
        new_code = clients.solve('CGJoinedOne')
        assert new_code != old_code, 'Random collision; no distinct-code replay claim'
        clients.wait('CGJoinedOne','system_chat')
        clients.command('answer CGJoinedOne '+old_code)
        message = clients.wait('CGJoinedOne','system_chat')
        assert 'Incorrect verification' in str(message), message
        assert len(requests)==3 and len(joins())==before
        clients.command('answer CGJoinedOne '+new_code)
        wait_join('CGJoinedOne',start)
        assert len(requests)==4 and len(joins())==3
        passed('same-name reconnect needs its new actual challenge and cannot reuse previous admitted receipt')
        clients.command('connect CGReload')
        clients.solve('CGReload')
        proxy.command('cg reload','Config has been reloaded!')
        clients.wait('CGReload','end',TIMEOUT+5)
        assert len(requests)==4 and len(joins())==3
        alive('CGJoinedOne')
        alive('CGJoinedTwo')
        passed('policy reload rejects pending challenge without removing already admitted backend players')
        settings['operation']['mode']='OBSERVE'
        proxy.write(settings)
        proxy.command('cg reload','Config has been reloaded!')
        positive['value']=True
        start = len(backend.transcript)
        clients.command('connect CGObserved')
        wait_join('CGObserved',start)
        assert len(joins())==4
        assert not any(e['name']=='CGObserved' and e['kind']=='map' for e in clients.saved)
        alive('CGObserved')
        passed('OBSERVE bypasses optional challenge enforcement and actually joins backend')
        result.update(status='passed',backend_join_tested=True,actual_provider_requests=len(requests),actual_backend_joins=len(joins()),
                      expired_admitted_receipt_never_kicked=True,maximum_sessions_consumed_on_join=True)
    except BaseException as error:
        result.update(status='failed',error=type(error).__name__+': '+str(error)[:700])
        raise
    finally:
        if clients is not None: clients.close()
        if proxy is not None: proxy.close()
        if backend is not None: backend.close()
        http.shutdown()
        http.server_close()
        result['all_own_processes_stopped'] = all(p is None or p.process.poll() is not None for p in [clients,proxy,backend])
        result['exit_codes'] = {name:p.process.returncode for name,p in [('clients',clients),('proxy',proxy),('backend',backend)] if p is not None}
        result['logs'] = {str(p.relative_to(work)):hashlib.sha256(p.read_bytes()).hexdigest() for p in [work/'client.log',work/'proxy/smoke.log',work/'server/smoke.log'] if p.is_file()}
        (work/'result.json').write_text(json.dumps(result,indent=2)+'\n')
        print(json.dumps(result,indent=2),flush=True)
    assert result['status']=='passed' and result['all_own_processes_stopped'] and all(c==0 for c in result['exit_codes'].values())

if __name__=='__main__': main()
