#!/usr/bin/env python3
"""Opt-in native dashboard notices on owned loopback; see README for EULA/runtime scope."""
import argparse, copy, gzip, hashlib, json, os, re, subprocess, sys, threading, time, zipfile
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
import yaml

def digest(path): return hashlib.sha256(path.read_bytes()).hexdigest()
def until(predicate, label, seconds=35):
    end=time.monotonic()+seconds
    while time.monotonic()<end:
        value=predicate()
        if value: return value
        time.sleep(.05)
    raise AssertionError('Missing owned fixture evidence: '+label)

def main():
    parser=argparse.ArgumentParser(); parser.add_argument('--operations-root',type=Path,required=True)
    parser.add_argument('--artifact',type=Path,required=True); parser.add_argument('--sha256',required=True)
    parser.add_argument('--platform',choices=['paper','folia','velocity','bungee'],required=True)
    parser.add_argument('--fixture',required=True); args=parser.parse_args()
    root=args.operations_root.resolve(); checkout=Path(__file__).resolve().parents[3]
    artifact=args.artifact.resolve(); assert artifact.is_relative_to(checkout/'build/libs') and digest(artifact)==args.sha256
    assert re.fullmatch('[a-z0-9-]{1,100}',args.fixture)
    assert 'Ja, EULA akzeptieren und diese lokalen Tests durchführen' in (root/'delivery/backend-runtime-scope.md').read_text()
    assert 'CONNECTIONGUARD_CLOUD' not in os.environ, 'Owned loopback Cloud must be explicitly enabled'
    os.environ['CONNECTIONGUARD_TOR_REFRESH']='false'
    sys.path.insert(0,str(root/'tools'))
    from backend_fixture import Backend, RUNTIMES
    from release_backend_test import Clients
    from release_redis_velocity_test import Proxy
    from release_native_bungee_test import Bungee, RUNTIME, RUNTIME_SHA
    from release_velocity_test import JAVA, PROXY, PROXY_SHA
    from pr77_runtime_cases import restart_process
    family='backend-smoke' if args.platform in RUNTIMES else 'native-bungee-smoke' if args.platform=='bungee' else 'policy-smoke'
    work=root/'.runtime'/family/args.fixture; assert not work.exists(); work.mkdir(parents=True,mode=0o700)
    state={'claimed':False,'records':[],'installs':0}; lock=threading.Lock()
    class LocalServer(ThreadingHTTPServer): daemon_threads=True
    class Handler(BaseHTTPRequestHandler):
        def do_POST(self):
            assert self.path in ['/v1/installs','/v1/sync']
            size=int(self.headers['Content-Length']); assert 0<size<262144
            body=json.loads(gzip.decompress(self.rfile.read(size)))
            with lock:
                claimed=state['claimed']; state['records'].append(body)
                if self.path=='/v1/installs': state['installs']+=1
            reply={'claimed':claimed,'network_name':'Owned fixture' if claimed else None,
                'link_code':None if claimed else '7KQM-4P2X',
                'link_url':None if claimed else 'http://127.0.0.1:'+str(self.server.server_port)+'/link/7KQM-4P2X',
                'next_sync_in':5,'live':False,'accept_events':False,'commands':[]}
            if self.path=='/v1/installs': reply.update(install_id='ins_'+'A'*24,secret='cgs_'+'s'*48)
            raw=json.dumps(reply).encode(); self.send_response(201 if self.path=='/v1/installs' else 200)
            self.send_header('Content-Type','application/json'); self.send_header('Content-Length',str(len(raw))); self.end_headers()
            try: self.wfile.write(raw)
            except (BrokenPipeError,ConnectionResetError): pass
        def log_message(self,*unused): pass
    http=LocalServer(('127.0.0.1',0),Handler); threading.Thread(target=http.serve_forever,daemon=True).start()
    result={'status':'running','platform':args.platform,'artifact_sha256':args.sha256,'driver_sha256':digest(Path(__file__)),
        'cases':[],'loopback_only':True,'live_cloud_provider_or_account_tested':False,'java':21}
    def record(case):
        result['cases'].append(case); (work/'result.json').write_text(json.dumps(result,indent=2)+'\n'); print('PASS '+args.platform+' '+case,flush=True)
    fixture=Path(__file__).parent; classes=work/'classes'; classes.mkdir()
    native_backend=args.platform in RUNTIMES
    wrapper='NoticeBackendFixture' if native_backend else 'NoticeVelocityFixture' if args.platform=='velocity' else 'NoticeBungeeFixture'
    descriptor='plugin.yml' if native_backend else 'velocity-plugin.json' if args.platform=='velocity' else 'bungee.yml'
    libraries=sorted((root/'.runtime/backend-fixture/compile-libraries').glob('*.jar')) if native_backend else [PROXY if args.platform=='velocity' else RUNTIME]
    subprocess.run([str(Path(JAVA).with_name('javac')),'--release','21','-proc:none','-cp',':'.join(map(str,[artifact,*libraries])),
        '-d',str(classes),str(fixture/(wrapper+'.java'))],check=True,timeout=30)
    addon=work/'addon.jar'
    with zipfile.ZipFile(addon,'w') as archive:
        for file in sorted(classes.rglob('*.class')): archive.write(file,file.relative_to(classes).as_posix())
        archive.write(fixture/descriptor,descriptor)
    result['addon_sha256']=digest(addon); result['fixture_source_sha256']=digest(fixture/(wrapper+'.java'))
    with zipfile.ZipFile(artifact) as archive: settings=yaml.safe_load(archive.read('config.yml'))
    for provider in settings['provider']['vpn'].values(): provider['enabled']=False
    settings['provider']['geo']['service']='Disabled'; settings['provider']['cache']['type']='Disabled'
    settings['operation']['mode']='OBSERVE'; settings['message-language']='en'
    settings['cloud']={'enabled':True,'endpoint':'http://127.0.0.1:'+str(http.server_port),'network-token':''}
    for scope in ['vpn','geo']:
        settings['behavior'][scope]['notify-staff']=False
        settings['behavior'][scope]['execute-command']['enabled']=False
        settings['behavior'][scope]['send-webhook']['enabled']=False
    instance=backend=clients=None
    try:
        if native_backend:
            instance=Backend(work/'server',args.platform,artifact,settings,addon)
            result['runtime']=RUNTIMES[args.platform][0]; result['runtime_sha256']=RUNTIMES[args.platform][1]
        else:
            backend_work=root/'.runtime/backend-smoke'/(args.fixture+'-backend'); assert not backend_work.exists()
            backend_settings=copy.deepcopy(settings); backend_settings['cloud']['enabled']=False
            backend=Backend(backend_work,'paper',artifact,backend_settings); backend.ready()
            if args.platform=='velocity':
                assert digest(PROXY)==PROXY_SHA
                instance=Proxy(work/'server',artifact,settings,addon,owned_backend_port=backend.port)
                result.update(runtime=PROXY.name,runtime_sha256=PROXY_SHA)
            else:
                assert digest(RUNTIME)==RUNTIME_SHA
                class OwnedBungee(Bungee):
                    def write(self,settings):
                        super().write(settings)
                        config=yaml.safe_load((self.directory/'config.yml').read_text())
                        config['servers']['owned-unused']['address']='127.0.0.1:'+str(backend.port)
                        (self.directory/'config.yml').write_text(yaml.safe_dump(config,sort_keys=False))
                instance=OwnedBungee(work/'server',artifact,addon,settings,[],{},False)
                result.update(runtime=RUNTIME.name,runtime_sha256=RUNTIME_SHA)
        instance.ready()
        until(lambda:any('http://127.0.0.1:'+str(http.server_port)+'/link/7KQM-4P2X?src=console' in line for line in instance.transcript),'console setup link')
        assert any('FREE CLOUD DASHBOARD' in line for line in instance.transcript)
        assert sum('============================================================' in line for line in instance.transcript)>=2
        record('framed-console-banner-with-confirmed-console-source-link')
        clients=Clients(instance)
        def connect(name):
            begin=len(clients.transcript); started=time.monotonic(); clients.write('connect '+name)
            clients.wait('CLIENT '+name+' login')
            until(lambda:any('NOTICE_JOIN '+name in line for line in instance.transcript),'post-join event')
            return begin,started
        def close(name):
            clients.write('close '+name); clients.wait('CLIENT '+name+' end'); time.sleep(.2)
        def hints(begin,name): return [line for line in clients.transcript[begin:] if line.startswith('CLIENT '+name+' system_chat ') and 'src=join' in line]
        begin,started=connect('CGStaff')
        link=until(lambda:hints(begin,'CGStaff'),'staff clickable setup link')[0]
        elapsed=time.monotonic()-started; assert elapsed>=1.5 and elapsed<15
        for token in ['open_url','green','bold','underlined']: assert token in link, link
        assert ('127.0.0.1:'+str(http.server_port)) in link
        result['staff_hint_after_connect_ms']=round(elapsed*1000)
        if native_backend:
            delayed=until(lambda:next((line for line in instance.transcript if 'NOTICE_DELAY CGStaff ms=' in line),None),'owning entity region delayed task')
            assert int(re.search('ms=([0-9]+)',delayed)[1])>=1500
        record('actual-staff-join-receives-delayed-native-green-clickable-component')
        close('CGStaff'); begin,_=connect('CGStaff'); time.sleep(3); assert not hints(begin,'CGStaff'); close('CGStaff')
        record('actual-rejoin-does-not-repeat-hint')
        begin,_=connect('CGRegular'); time.sleep(3); assert not hints(begin,'CGRegular'); close('CGRegular')
        record('ordinary-player-receives-no-hint')
        begin,_=connect('CGOperator'); until(lambda:hints(begin,'CGOperator'),'operator hint'); close('CGOperator')
        record('operator-or-proxy-cloud-permission-receives-hint')
        clients.close(); clients=None
        restart_process(instance,work)
        until(lambda:any('?src=console' in line for line in instance.transcript),'confirmed unlinked restart banner')
        clients=Clients(instance); begin,_=connect('CGStaff'); time.sleep(3); assert not hints(begin,'CGStaff'); close('CGStaff')
        record('clean-native-restart-preserves-once-per-installation')
        if native_backend:
            settings['identity']['trust-forwarded-uuid']=True; instance.write(settings); instance.command('cg reload','Configuration reloaded!')
            begin,_=connect('CGBackend'); time.sleep(3); assert not hints(begin,'CGBackend'); close('CGBackend')
            settings['identity']['trust-forwarded-uuid']=False; instance.write(settings); instance.command('cg reload','Configuration reloaded!')
            begin,_=connect('CGBackend'); until(lambda:hints(begin,'CGBackend'),'standalone hint after proxy suppression'); close('CGBackend')
            record('forwarded-backend-suppression-does-not-consume-once-only-hint')
        with lock: state['claimed']=True
        until(lambda:any('Connection Guard Cloud: linked to' in line for line in instance.transcript),'native confirmed linking')
        before=sum('FREE CLOUD DASHBOARD' in line for line in instance.transcript)
        begin,_=connect('CGLinked'); time.sleep(3); assert not hints(begin,'CGLinked'); close('CGLinked')
        assert before==sum('FREE CLOUD DASHBOARD' in line for line in instance.transcript)
        record('confirmed-linked-installation-stays-quiet')
        instance.command('cg cloud disable','Cloud disabled')
        begin,_=connect('CGOff'); time.sleep(3); assert not hints(begin,'CGOff'); close('CGOff')
        record('explicit-cloud-off-stays-quiet')
        clients.close(); clients=None; instance.close(); assert instance.process.returncode==0
        result.update(status='passed',native_exit_code=instance.process.returncode,console_sha256=digest(instance.directory/'smoke.log'))
        if backend: backend.close(); assert backend.process.returncode==0
        (work/'result.json').write_text(json.dumps(result,indent=2)+'\n'); print(json.dumps(result),flush=True)
    finally:
        if clients: (work/'clients.log').write_text(''.join(clients.transcript)); clients.close()
        if instance: instance.close()
        if backend: backend.close()
        http.shutdown(); http.server_close()

if __name__=='__main__': main()
