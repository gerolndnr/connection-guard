#!/usr/bin/env python3
"""Verify the separately licensed addon, without loading/shading any native SDK."""
import hashlib,json,struct,zipfile
from pathlib import Path
from verify_artifact import require
ROOT=Path(__file__).resolve().parents[1]
def main():
 files=list((ROOT/'adapters/libertybans/build/libs').glob('*.jar'));require(len(files)==1,'Expected one optional addon JAR.')
 with zipfile.ZipFile(files[0]) as jar:
  names=jar.namelist();require(jar.testzip() is None,'Corrupt optional addon.')
  prefix='com/github/gerolndnr/connectionguard/addons/libertybans/'
  for name in ('LibertyBansReader','SpigotAddon','BungeeAddon','VelocityAddon'):
   require(names.count(prefix+name+'.class')==1,'Missing/duplicate addon class.')
  for name in names:
   if name.endswith('.class'):
    require(name.startswith(prefix),'Addon bundled a native/core/server/dependency class.')
    require(struct.unpack('>HH',jar.read(name)[4:8])==(0,61),'Addon requires its declared Java 17 bytecode.')
  for resource in ('plugin.yml','bungee.yml','velocity-plugin.json','META-INF/connection-guard-libertybans/LICENSE'):
   require(names.count(resource)==1,'Missing/duplicate addon resource.')
  require(jar.read('META-INF/connection-guard-libertybans/LICENSE')==(ROOT/'adapters/libertybans/LICENSE').read_bytes(),'Addon AGPL license mismatch.')
  meta=json.loads(jar.read('velocity-plugin.json'));require(meta['id']=='connection-guard-libertybans' and meta['version']=='0.1.0-dev','Wrong addon identity.')
 print(json.dumps({'optional_addon_sha256':hashlib.sha256(files[0].read_bytes()).hexdigest(),'native_sdks_bundled':False,'runtime_tested':False}))
if __name__=='__main__':main()
