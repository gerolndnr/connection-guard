#!/usr/bin/env python3
"""25 offline migration assertions against the exact combined JAR."""
import argparse, hashlib, json, subprocess
from pathlib import Path

ROOT=Path(__file__).resolve().parents[3]
parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--artifact',type=Path,required=True)
parser.add_argument('--work',type=Path,required=True)
parser.add_argument('--java',type=Path,required=True)
args=parser.parse_args()
artifact=args.artifact.resolve(strict=True)
assert artifact.is_relative_to(ROOT/'build/libs')
work=args.work.resolve();assert not work.exists();work.mkdir(parents=True,mode=0o700)
classes=work/'classes';classes.mkdir()
source=Path(__file__).with_name('MigrationJarCases.java')
subprocess.run([str(args.java.with_name('javac')),'-encoding','UTF-8','-cp',str(artifact),'-d',str(classes),str(source)],check=True,timeout=30)
result=subprocess.run([str(args.java),'-cp',str(artifact)+':'+str(classes),'MigrationJarCases',str(work)],check=True,text=True,capture_output=True,timeout=30)
assert 'migration-jar-cases=25;' in result.stdout
receipt={'schema':1,'kind':'offline-actual-jar-migrations','artifact_sha256':hashlib.sha256(artifact.read_bytes()).hexdigest(),
         'assertions':25,'sources':['foxgate','proxyshield','vpnguard','kaurivpn','advancedantivpn'],
         'driver_sha256':hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
         'fixture_sha256':hashlib.sha256(source.read_bytes()).hexdigest(),'network_requests':0,
         'native_command_tested':False,'real_player_login_tested':False,'competitive_accuracy_tested':False}
(work/'receipt.json').write_text(json.dumps(receipt,indent=2)+'\n')
print(result.stdout.strip());print(json.dumps(receipt,indent=2))
