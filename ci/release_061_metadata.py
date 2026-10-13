import json, urllib.request, hashlib, concurrent.futures
from pathlib import Path
from datetime import datetime, timezone
OUT=Path('release-metadata');OUT.mkdir()
UA='ConnectionGuardOperations/0.6.1 (+https://github.com/gerolndnr/connection-guard)'
URLS={'mojang':'https://piston-meta.mojang.com/mc/game/version_manifest_v2.json','modrinth-game':'https://api.modrinth.com/v2/tag/game_version','paper':'https://fill.papermc.io/v3/projects/paper','folia':'https://fill.papermc.io/v3/projects/folia','velocity':'https://fill.papermc.io/v3/projects/velocity','hangar-previous':'https://hangar.papermc.io/api/v1/projects/connection-guard/versions/0.6.0'}
def fetch(pair):
 key,url=pair
 req=urllib.request.Request(url,headers={'User-Agent':UA,'Accept':'application/json'})
 with urllib.request.urlopen(req,timeout=40) as r:data=json.load(r)
 (OUT/(key+'.json')).write_text(json.dumps(data,indent=2)+'\n');return key,data
with concurrent.futures.ThreadPoolExecutor(max_workers=6) as pool: data=dict(pool.map(fetch,URLS.items()))
latest=data['mojang']['latest']['release'];print('Latest Minecraft release:',latest)
flat=lambda k:[v for group in data[k]['versions'].values() for v in group]
stable=lambda k:next(v for v in flat(k) if v[0].isdigit() and all(p.isdigit() for p in v.split('.')))
selected={'paper':stable('paper'),'folia':stable('folia'),'velocity':stable('velocity'),'velocity-development':next(v for v in flat('velocity') if v.endswith('-SNAPSHOT'))}
records={}
for label,version in selected.items():
 project='velocity' if label.startswith('velocity') else label
 url='https://fill.papermc.io/v3/projects/'+project+'/versions/'+version+'/builds'
 key,builds=fetch((label+'-builds',url))
 build=next((b for b in builds if b['channel']=='STABLE'),builds[0]);download=build['downloads']['server:default'];file=OUT/download['name']
 req=urllib.request.Request(download['url'],headers={'User-Agent':UA})
 with urllib.request.urlopen(req,timeout=90) as r:body=r.read(120000000)
 assert len(body)<120000000 and hashlib.sha256(body).hexdigest()==download['checksums']['sha256']
 file.write_bytes(body);records[label]={'version':version,'build':build['id'],'filename':download['name'],'sha256':download['checksums']['sha256'],'source_url':url,'download_url':download['url'],'channel':build['channel']}
 print(label,version,build['id'])
(OUT/'downloaded-runtimes.json').write_text(json.dumps(records,indent=2)+'\n')
(OUT/'sources.json').write_text(json.dumps({'checked_at_utc':datetime.now(timezone.utc).isoformat(),'urls':URLS,'no_secrets_used':True,'player_or_test_ip_queries':0},indent=2)+'\n')
