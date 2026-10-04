from pathlib import Path
import hashlib,subprocess,zipfile,re,socket,yaml,json
import argparse
parser=argparse.ArgumentParser(description='Prepare an owned pinned Velocity/browser Cloud fixture; no runtime starts here.')
parser.add_argument('--operations-root',type=Path,required=True);parser.add_argument('--artifact',type=Path,required=True);parser.add_argument('--sha256',required=True);parser.add_argument('--fixture',required=True);parser.add_argument('--cloud-source',default='3e8771ee8ba19de0d59ec7f8f20764b05082a2e7')
a=parser.parse_args();root=a.operations_root.resolve();r=Path(__file__).resolve().parents[3]
assert re.fullmatch('[a-z0-9-]{1,100}',a.fixture)
assert re.fullmatch('[a-f0-9]{40}',a.cloud_source)
assert a.artifact.resolve().is_relative_to(r/'build/libs') and hashlib.sha256(a.artifact.read_bytes()).hexdigest()==a.sha256
work=root/'.runtime/cloud-gui-smoke'/a.fixture;assert not work.exists();work.mkdir(parents=True,mode=0o700)
artifact=a.artifact.resolve();proxy=root/'.runtime/proxy-smoke/velocity-3.4.0-566/velocity.jar';java=Path('/opt/homebrew/Cellar/openjdk@21/21.0.9/libexec/openjdk.jdk/Contents/Home/bin/java');source=r/'ci/fixtures/webhooks'
assert hashlib.sha256(proxy.read_bytes()).hexdigest()=='fb599cbda6a6d01decce5e281f71f51cae7cacffcfafca32a09601f407b0583e'
classes=work/'classes';classes.mkdir();subprocess.run([str(java.with_name('javac')),'--release','17','-proc:none','-cp',str(artifact)+':'+str(proxy),'-d',str(classes),str(source/'NativeWebhookFixture.java')],check=True,timeout=30)
addon=work/'fixture.jar'
with zipfile.ZipFile(addon,'w') as z:
    for p in sorted(classes.rglob('*.class')):z.write(p,p.relative_to(classes).as_posix())
    z.write(source/'velocity-plugin.json','velocity-plugin.json')
dir=work/'proxy';data=dir/'plugins/connection-guard';(data/'translation').mkdir(parents=True);(dir/'plugins/bStats').mkdir();(dir/'plugins/bStats/config.txt').write_text('enabled=false\n')
import shutil
shutil.copyfile(artifact,dir/'plugins/connection-guard.jar');shutil.copyfile(addon,dir/'plugins/fixture.jar');shutil.copyfile(proxy,dir/'velocity.jar')
with zipfile.ZipFile(proxy) as z:config=z.read('default-velocity.toml').decode()
with socket.socket() as reserve:reserve.bind(('127.0.0.1',0));port=reserve.getsockname()[1]
with socket.socket() as reserve:reserve.bind(('127.0.0.1',0));backend=reserve.getsockname()[1]
config,count=re.subn(r'^bind = "[^\"]+"$','bind = "127.0.0.1:'+str(port)+'"',config,flags=re.M);assert count==1
for before,after in [('online-mode = true','online-mode = false'),('force-key-authentication = true','force-key-authentication = false'),('login-ratelimit = 3000','login-ratelimit = 0'),('compression-threshold = 256','compression-threshold = -1')]:assert before in config;config=config.replace(before,after)
config,count=re.subn(r'^player-info-forwarding-mode = "(?:NONE|none|MODERN|modern)"$','player-info-forwarding-mode = "none"',config,flags=re.M);assert count==1
config,count=re.subn(r'^(lobby|factions|minigames) = "[^\"]+"$',lambda m:m.group(1)+' = "127.0.0.1:'+str(backend)+'"',config,flags=re.M);assert count==3
(dir/'velocity.toml').write_text(config)
with zipfile.ZipFile(artifact) as z:settings=yaml.safe_load(z.read('config.yml'))
for provider in settings['provider']['vpn'].values():provider['enabled']=False
settings['provider']['geo']['service']='Disabled';settings['provider']['cache']['type']='SQLite';settings['operation']['mode']='OBSERVE';settings['cloud']={'enabled':True,'endpoint':'http://127.0.0.1:0','network-token':''}
settings['integrations']['providers']={'enabled':True,'sources':[{'id':'webhook-fixture','voting':True,'daily-budget':100,'minute-budget':100}]};settings['integrations']['observers']={'enabled':False,'ids':[]}
settings['required-positive-flags']=1
for scope in ['vpn','geo']:
    settings['behavior'][scope]['exemptions']=[];settings['behavior'][scope]['notify-staff']=False;settings['behavior'][scope]['send-webhook']['enabled']=False
settings['behavior']['vpn']['kick-player']=True;settings['behavior']['geo']['kick-player']=False;settings['message-language']='de'
(data/'config.yml').write_text(yaml.safe_dump(settings,sort_keys=False));(data/'translation/de.yml').write_text('messages:\n  vpn-block: Betreiberblock %NAME%\n')
receipt={'prepare_source_sha256':hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),'status':'prepared-not-run','artifact_sha256':hashlib.sha256(artifact.read_bytes()).hexdigest(),'addon_sha256':hashlib.sha256(addon.read_bytes()).hexdigest(),'proxy_sha256':hashlib.sha256(proxy.read_bytes()).hexdigest(),'port':port,'backend_port':backend,'java':str(java),'driver':str(r/'ci/fixtures/webhooks/driver.py'),'cloud_source':a.cloud_source,'cases':[],'new_cost_eur':0,'accounts_authenticated':False,'live_upstream_tested':False,'backend_joined':False,'other_agent_checkouts_modified':False}
(work/'receipt.json').write_text(json.dumps(receipt,indent=2)+'\n');print('Owned pinned Velocity fixture prepared; no player/account/backend or live provider connection started.')
