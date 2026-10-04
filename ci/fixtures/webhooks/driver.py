#!/usr/bin/env python3
"""Portable owned TLS receiver + real Velocity offline-login qualification; no Discord traffic."""
import argparse, copy, hashlib, json, queue, re, shutil, socket, ssl, struct, subprocess, threading, time, zipfile
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
import yaml

PROXY_SHA='fb599cbda6a6d01decce5e281f71f51cae7cacffcfafca32a09601f407b0583e'
def varint(value):
    result=b''
    while True:
        part=value & 127;value>>=7;result+=bytes([part | (128 if value else 0)])
        if not value:return result
def text(value):
    data=value.encode();return varint(len(data))+data
def readvar(conn):
    value=0
    for shift in range(0,35,7):
        data=conn.recv(1)
        if not data:raise EOFError()
        value|=(data[0]&127)<<shift
        if not data[0]&128:return value
    raise ValueError('Bounded varint exceeded')
def login(port,name):
    assert re.fullmatch('[A-Za-z0-9_]{1,16}',name)
    with socket.create_connection(('127.0.0.1',port),timeout=5) as conn:
        handshake=varint(0)+varint(760)+text('127.0.0.1')+struct.pack('>H',port)+varint(2)
        conn.sendall(varint(len(handshake))+handshake)
        start=varint(0)+text(name)+b'\x00\x00';conn.sendall(varint(len(start))+start)
        size=readvar(conn);assert 0<size<=65536;packet=b''
        while len(packet)<size:
            chunk=conn.recv(size-len(packet))
            if not chunk:raise EOFError()
            packet+=chunk
        if packet[0]==2:return 'LOGIN_SUCCESS'
        assert packet[0]==0,'Unexpected encryption/compression packet in the offline fixture'
        return packet[1:].decode(errors='replace')
class Proxy:
    def __init__(self,directory,artifact,proxy,java,settings,addon,backend):
        self.directory=directory;directory.mkdir(mode=0o700);self.transcript=[];self.lines=queue.Queue()
        data=directory/'plugins/connection-guard';(data/'translation').mkdir(parents=True)
        (directory/'plugins/bStats').mkdir();(directory/'plugins/bStats/config.txt').write_text('enabled=false\n')
        shutil.copyfile(artifact,directory/'plugins/connection-guard.jar');shutil.copyfile(addon,directory/'plugins/webhook-fixture.jar')
        with zipfile.ZipFile(artifact) as z:(data/'translation/en.yml').write_bytes(z.read('translation/en.yml'))
        with zipfile.ZipFile(proxy) as z:config=z.read('default-velocity.toml').decode()
        config,count=re.subn(r'^bind = "[^\"]+"$','bind = "127.0.0.1:0"',config,flags=re.M);assert count==1
        for before,after in [('online-mode = true','online-mode = false'),('force-key-authentication = true','force-key-authentication = false'),('login-ratelimit = 3000','login-ratelimit = 0'),('compression-threshold = 256','compression-threshold = -1')]:
            assert before in config;config=config.replace(before,after)
        config,count=re.subn(r'^player-info-forwarding-mode = "(?:NONE|none|MODERN|modern)"$','player-info-forwarding-mode = "none"',config,flags=re.M);assert count==1
        config,count=re.subn(r'^(lobby|factions|minigames) = "[^\"]+"$',lambda m:m.group(1)+' = "127.0.0.1:'+str(backend)+'"',config,flags=re.M);assert count==3
        (directory/'velocity.toml').write_text(config);self.write(settings)
        self.process=subprocess.Popen([str(java),'-Xms64m','-Xmx256m','-Dio.netty.eventLoopThreads=2','-Dterminal.jline=false','-Dterminal.ansi=false','-jar',str(proxy)],cwd=directory,stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True,bufsize=1)
        def consume():
            for line in self.process.stdout:
                line=re.sub(r'\x1b\[[0-9;]*[A-Za-z]','',line);self.transcript.append(line);self.lines.put(line)
            self.lines.put(None)
        self.reader=threading.Thread(target=consume,daemon=True);self.reader.start()
    def wait(self,marker,seconds=40):
        deadline=time.monotonic()+seconds
        while time.monotonic()<deadline:
            try:line=self.lines.get(timeout=.2)
            except queue.Empty:continue
            if line is None:raise AssertionError('Owned proxy exited before '+marker)
            if marker in line:return line
        raise AssertionError('Owned proxy timeout: '+marker)
    def command(self,value,marker):self.process.stdin.write(value+'\n');self.process.stdin.flush();return self.wait(marker)
    def write(self,settings):(self.directory/'plugins/connection-guard/config.yml').write_text(yaml.safe_dump(settings,sort_keys=False))
    def ready(self):
        self.wait('Done (',60);self.port=int(re.search(r'127\.0\.0\.1:(\d+)',next(line for line in self.transcript if 'Listening on ' in line)).group(1))
    def close(self):
        if self.process.poll() is None:
            self.process.stdin.write('shutdown\n');self.process.stdin.flush()
            try:self.process.wait(timeout=15)
            except subprocess.TimeoutExpired:
                self.process.terminate()
                try:self.process.wait(timeout=5)
                except subprocess.TimeoutExpired:self.process.kill();self.process.wait(timeout=5)
        self.reader.join(timeout=3);assert not self.reader.is_alive();(self.directory/'console.log').write_text(''.join(self.transcript))
def main():
    parser=argparse.ArgumentParser();parser.add_argument('--artifact',type=Path,required=True);parser.add_argument('--sha256',required=True)
    parser.add_argument('--proxy',type=Path,required=True);parser.add_argument('--java',type=Path,required=True);parser.add_argument('--work-dir',type=Path,required=True);args=parser.parse_args()
    artifact=args.artifact.resolve();proxy=args.proxy.resolve();java=args.java.resolve();work=args.work_dir.resolve();source=Path(__file__).resolve().parent
    assert re.fullmatch('[0-9a-f]{64}',args.sha256) and artifact.is_file() and hashlib.sha256(artifact.read_bytes()).hexdigest()==args.sha256
    assert hashlib.sha256(proxy.read_bytes()).hexdigest()==PROXY_SHA and not work.exists();work.mkdir(parents=True,mode=0o700)
    version=subprocess.run([str(java),'-version'],capture_output=True,text=True,check=True,timeout=10);assert re.search(r'version "21\.',version.stderr)
    classes=work/'classes';classes.mkdir();subprocess.run([str(java.with_name('javac')),'--release','17','-proc:none','-cp',str(artifact)+':'+str(proxy),'-d',str(classes),str(source/'NativeWebhookFixture.java')],check=True,timeout=30)
    addon=work/'fixture.jar'
    with zipfile.ZipFile(addon,'w') as z:
        for path in sorted(classes.rglob('*.class')):z.write(path,path.relative_to(classes).as_posix())
        z.write(source/'velocity-plugin.json','velocity-plugin.json')
    with zipfile.ZipFile(addon) as z:assert all(not name.startswith('com/github/') for name in z.namelist())
    cert=work/'cert.pem';key=work/'key.pem'
    subprocess.run(['openssl','req','-x509','-newkey','rsa:2048','-nodes','-sha256','-days','1','-subj','/CN=127.0.0.1','-addext','subjectAltName=IP:127.0.0.1','-keyout',str(key),'-out',str(cert)],capture_output=True,check=True,timeout=20);key.chmod(0o600)
    pin=hashlib.sha256(ssl.PEM_cert_to_DER_cert(cert.read_text())).hexdigest()
    state={'status':204,'body':'','headers':{},'gate':None};received=[];lock=threading.Lock()
    class Handler(BaseHTTPRequestHandler):
        protocol_version='HTTP/1.1'
        def do_POST(self):
            count=int(self.headers.get('Content-Length','0'));assert 0<count<=65536
            body=json.loads(self.rfile.read(count));assert self.path.startswith('/fixture/')
            with lock:received.append({'path':self.path,'body':body});status=state['status'];payload=state['body'].encode();headers=dict(state['headers']);gate=state['gate']
            try:
                if gate is not None:assert gate.wait(4),'Owned HTTPS gate expired'
                self.send_response(status);self.send_header('Connection','close')
                for name,value in headers.items():self.send_header(name,value)
                self.send_header('Content-Length',str(len(payload) if status!=204 else 0));self.end_headers()
                if status!=204:self.wfile.write(payload)
            except (BrokenPipeError,ConnectionResetError,ssl.SSLError):pass
        def log_message(self,*args):pass
    http=ThreadingHTTPServer(('127.0.0.1',0),Handler);http.daemon_threads=False;http.block_on_close=True
    tls=ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER);tls.load_cert_chain(cert,key);http.socket=tls.wrap_socket(http.socket,server_side=True)
    thread=threading.Thread(target=http.serve_forever,daemon=True);thread.start()
    backend=socket.socket();backend.bind(('127.0.0.1',0));instance=None
    result={'artifact_sha256':args.sha256,'addon_sha256':hashlib.sha256(addon.read_bytes()).hexdigest(),'fixture_source_sha256':hashlib.sha256((source/'NativeWebhookFixture.java').read_bytes()).hexdigest(),'driver_sha256':hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),'proxy':'Velocity 3.4.0 build 566','proxy_sha256':PROXY_SHA,'java':21,'cost_eur':0,'real_discord_tested':False,'live_detection_tested':False,'account_authentication_tested':False,'backend_join_tested':False,'cases':[]}
    try:
        with zipfile.ZipFile(artifact) as z:settings=yaml.safe_load(z.read('config.yml'))
        for value in settings['provider']['vpn'].values():value['enabled']=False
        settings['provider']['geo']['service']='Disabled';settings['provider']['cache']['type']='SQLite';settings['operation']['mode']='ENFORCE'
        settings['integrations']['providers']={'enabled':True,'sources':[{'id':'webhook-fixture','voting':True,'daily-budget':100,'minute-budget':100}]}
        settings['integrations']['observers']={'enabled':False,'ids':[]};settings['required-positive-flags']=1
        settings['behavior']['vpn']['exemptions']=[];settings['behavior']['geo']['exemptions']=['127.0.0.1'];settings['behavior']['vpn']['notify-staff']=False
        vpn=settings['behavior']['vpn']['send-webhook'];vpn.update(enabled=True,url='https://127.0.0.1:'+str(http.server_port)+'/fixture/vpn',format='EMBED',**{'include-ip':False,'cooldown-ms':0,'events':['FLAG','DENY','ERROR']})
        settings['failure-policy']={'vpn':'OPEN','geo':'OPEN'}
        instance=Proxy(work/'proxy',artifact,proxy,java,settings,addon,backend.getsockname()[1]);shutil.copyfile(cert,instance.directory/'fixture-cert.pem');instance.ready()
        def fixture(command='status'):
            line=instance.command('fixture-webhook '+command,'WH_FIXTURE');assert 'rejected=true' not in line,line
            return dict(re.findall(r'(\w+)=([^ ]+)',line.strip()))
        def idle(sender=True):
            deadline=time.monotonic()+5
            while time.monotonic()<deadline:
                value=fixture()
                if value['lookupIdle']=='true' and (not sender or value['queued']=='0' and value['active']=='0'):return value
                time.sleep(.02)
            raise AssertionError('Owned workers not idle')
        def reload():idle(sender=False);instance.write(settings);instance.command('cg reload','Config has been reloaded!')
        def clear():instance.command('cg clear 127.0.0.1','Cleared the cache entries for');idle(sender=False)
        def count():
            with lock:return len(received)
        def wait_count(expected):
            deadline=time.monotonic()+3
            while count()<expected and time.monotonic()<deadline:time.sleep(.01)
            assert count()==expected,(count(),expected)
        def send(name,denied=True):
            response=login(instance.port,name);assert (response!='LOGIN_SUCCESS')==denied,(name,response);return response
        def body(index=-1):
            with lock:return copy.deepcopy(received[index]['body'])
        def check(expected,contains=None):
            wait_count(expected);idle();value=body();serialized=json.dumps(value)
            assert value['allowed_mentions']=={'parse':[]} and len(value.get('embeds',[]))==1
            assert all(hidden not in serialized for hidden in ['synthetic-provider-isp','synthetic-provider-operator','synthetic-private-token'])
            if contains:assert contains in serialized
            return serialized
        reload();send('CGUntrustedTLS');value=idle();assert count()==0 and int(value['failed'])==1
        result['cases'].append('production-tls-rejects-untrusted-certificate-before-post')
        fixture('bind '+str(http.server_port)+' '+pin);send('CGWebhookDenied');payload=check(1,'VPN_FLAG')
        assert '127.0.0.1' not in payload and '79.999' in payload and 'cached: true' in payload and fixture()['events']=='0'
        result['cases'].append('native-final-denial-cached-decimal-privacy-without-observer')
        settings['behavior']['vpn']['kick-player']=False;reload();send('CGFlagAllowed',False);check(2,'FLAG_ALLOWED');result['cases'].append('flag-allowed-is-not-reported-as-blocked')
        fixture('mode negative');clear();send('CGNegative',False);idle();assert count()==2;result['cases'].append('ordinary-negative-is-quiet')
        fixture('mode unknown');clear();send('CGUnknownQuiet',False);idle();assert count()==2
        vpn['events']=['UNKNOWN'];reload();send('CGUnknownEvent',False);payload=check(3,'UNKNOWN_ALLOWED');assert 'Risk (source value): UNKNOWN' in payload
        result['cases'].append('unknown-opt-in-preserves-missing-facts-and-allowed-result')
        vpn['events']=['DENY'];settings['failure-policy']['vpn']='CLOSED';reload();send('CGUnknownDeny');check(4,'LOOKUP_UNAVAILABLE');result['cases'].append('strict-unknown-denial-reports-source-status')
        settings['operation']['mode']='OBSERVE';vpn['events']=['FLAG','DENY','ERROR','UNKNOWN'];fixture('mode positive');clear();settings['behavior']['vpn']['kick-player']=True;reload()
        send('CGObserve',False);idle();assert count()==4;result['cases'].append('observe-positive-has-zero-webhook-actions')
        settings['operation']['mode']='ENFORCE';settings['failure-policy']['vpn']='OPEN';reload()
        geo=settings['behavior']['geo']['send-webhook'];geo.update(copy.deepcopy(vpn));geo['include-ip']=False;vpn['include-ip']=True
        reload();line=instance.command('cg deny add 127.0.0.1 all permanent Synthetic-manual','Stored ');rule=re.search(r'Stored (\S+)',line).group(1)
        before=int(fixture()['calls']);send('CGBothScopes');payload=check(5,'ACCESS_RULE');assert int(fixture()['calls'])==before and '127.0.0.1' not in payload and '[VPN, GEO]' in payload and 'NOT_CHECKED' in payload
        result['cases'].append('manual-deny-before-lookup-combines-identical-endpoints-private')
        geo['url']='https://127.0.0.1:'+str(http.server_port)+'/fixture/geo';reload();send('CGSeparate');wait_count(7);idle()
        pairs=received[-2:];by_path={p['path']:json.dumps(p['body']) for p in pairs};assert '127.0.0.1' in by_path['/fixture/vpn'] and '127.0.0.1' not in by_path['/fixture/geo']
        result['cases'].append('separate-recipient-address-opt-ins-remain-independent')
        instance.command('cg deny remove '+rule,'Rule removed.');geo['enabled']=False;vpn['include-ip']=False;vpn['cooldown-ms']=60000;reload()
        # Previous messages charged zero cooldown; first selected cooldown arms the suppression window.
        send('CGCooldownOne');check(8);send('CGCooldownTwo');idle();assert count()==8;result['cases'].append('native-cooldown-suppresses-repeated-flags')
        # A new URL owns a distinct local cooldown bucket for the reload/queue test.
        vpn['cooldown-ms']=0;vpn['url']='https://127.0.0.1:'+str(http.server_port)+'/fixture/old';reload()
        gate=threading.Event();state['gate']=gate;send('CGActive');wait_count(9);send('CGQueued')
        deadline=time.monotonic()+2
        while int(fixture()['queued'])<1 and time.monotonic()<deadline:time.sleep(.01)
        assert int(fixture()['queued'])==1
        vpn['url']='https://127.0.0.1:'+str(http.server_port)+'/fixture/new';reload();gate.set();state['gate']=None;idle();assert count()==9
        send('CGAfterReload');check(10);assert received[-1]['path']=='/fixture/new';result['cases'].append('actual-reload-retires-queued-old-recipient-without-delaying-admission')
        state['status']=503;settings['integrations']['observers']={'enabled':True,'ids':['webhook-observer']};reload();before=int(fixture()['events']);send('CGFailedPost');check(11)
        deadline=time.monotonic()+2;value=fixture()
        while int(value['events'])<before+1 and time.monotonic()<deadline:time.sleep(.01);value=fixture()
        assert int(value['events'])==before+1 and value['outcome']=='DENY'
        result['cases'].append('failed-http-delivery-does-not-change-guard-or-observer')
        state['status']=204;gate=threading.Event();state['gate']=gate;send('CGPostTimeout');wait_count(12);value=idle();assert int(value['failed'])>=3 and count()==12;gate.set();state['gate']=None
        result['cases'].append('ambiguous-http-timeout-has-no-automatic-retry')
        state['status']=429;state['body']='{"retry_after":60.125,"global":true,"message":"synthetic-private-token"}';state['headers']={}
        send('CGRateOne');check(13);vpn['url']='https://127.0.0.1:'+str(http.server_port)+'/fixture/rate-other';reload();send('CGRateOther');idle();assert count()==13
        result['cases'].append('actual-json-global-429-pauses-a-different-recipient-without-retry')
        assert 'synthetic-private-token' not in ''.join(instance.transcript) and 'synthetic-provider-isp' not in ''.join(instance.transcript)
        result['https_requests']=count();result['observer_was_initially_unselected']=True;result['guard_idle_at_end']=fixture()['lookupIdle']=='true'
        instance.close();assert instance.process.returncode==0;result['shutdown_passed']=True
    finally:
        if state.get('gate') is not None:state['gate'].set()
        if instance is not None:instance.close()
        http.shutdown();http.server_close();thread.join(timeout=3);backend.close();assert not thread.is_alive()
    result['owned_https_and_proxy_stopped']=True;result['console_sha256']=hashlib.sha256((work/'proxy/console.log').read_bytes()).hexdigest()
    (work/'result.json').write_text(json.dumps(result,indent=2)+'\n');print(json.dumps(result,indent=2),flush=True)
if __name__=='__main__':main()
