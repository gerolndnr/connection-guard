#!/usr/bin/env python3
"""Verify separate public-API challenge transport; this is packaging, never runtime proof."""
import hashlib,json,struct,zipfile
from pathlib import Path
from verify_artifact import require
ROOT=Path(__file__).resolve().parents[1]
def main():
 files=list((ROOT/'adapters/limbo/build/libs').glob('*.jar'));require(len(files)==1,'Expected one challenge addon JAR.')
 with zipfile.ZipFile(files[0]) as jar:
  names=jar.namelist();require(jar.testzip() is None,'Corrupt challenge addon.')
  prefix='com/github/gerolndnr/connectionguard/addons/limbo/'
  for name in ('VelocityChallengeAddon','NativeLimboTransport'):
   require(names.count(prefix+name+'.class')==1,'Missing/duplicate challenge class.')
  for name in names:
   if name.endswith('.class'):
    require(name.startswith(prefix),'Challenge addon bundled native/core/dependency classes.')
    require(struct.unpack('>HH',jar.read(name)[4:8])==(0,61),'Challenge addon must use Java17 bytecode.')
  for resource in ('velocity-plugin.json','challenge.properties','META-INF/connection-guard-limbo/LICENSE','META-INF/connection-guard-limbo/LIMBOAPI-API-MIT.txt'):
   require(names.count(resource)==1,'Missing/duplicate challenge resource.')
  for name in ('LICENSE','LIMBOAPI-API-MIT.txt'):
   require(jar.read('META-INF/connection-guard-limbo/'+name)==(ROOT/'adapters/limbo'/name).read_bytes(),'Challenge license mismatch.')
  meta=json.loads(jar.read('velocity-plugin.json'));require(meta['id']=='connection-guard-limbo' and meta['version']=='0.1.0-dev','Wrong challenge identity.')
  dependencies={d['id']:d.get('optional',False) for d in meta['dependencies']}
  require(dependencies=={'connection-guard':False,'limboapi':True},'Wrong challenge dependency boundaries.')
  require(b'enabled=false' in jar.read('challenge.properties'),'Challenge must default disabled.')
 print(json.dumps({'optional_challenge_sha256':hashlib.sha256(files[0].read_bytes()).hexdigest(),'native_sdks_bundled':False,'runtime_tested':False}))
if __name__=='__main__':main()
