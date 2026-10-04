import assert from 'node:assert/strict';
import {readFileSync,writeFileSync,mkdirSync} from 'node:fs';
import {spawn} from 'node:child_process';
import {createServer} from 'node:http';
import {gunzipSync} from 'node:zlib';
import {createHash} from 'node:crypto';
import {resolve} from 'node:path';
import {parseArgs} from 'node:util';
const {values:args}=parseArgs({options:{'operations-root':{type:'string'},'cloud-copy':{type:'string'},fixture:{type:'string'},operator:{type:'string'}}});
assert(args['operations-root']&&args['cloud-copy']&&/^[a-z0-9-]{1,100}$/.test(args.fixture||'')&&args.operator&&args.operator.length<=64);
const root=resolve(args['operations-root']);const apiCopy=resolve(args['cloud-copy']);
assert(apiCopy.startsWith(root+'/.runtime/')); // never run in the other agent's Cloud checkout
const work=root+'/.runtime/cloud-gui-smoke/'+args.fixture;
const receipt=JSON.parse(readFileSync(work+'/receipt.json'));const base='http://localhost:8798';const t0=Date.now();
const {chromium}=await import(apiCopy+'/node_modules/playwright-core/index.mjs');
let proxy,browser,context,page;let consoleText='';let offline=false;const requests=[];const external=[];
const sha=(value)=>createHash('sha256').update(value).digest('hex');
const sleep=(ms)=>new Promise(r=>setTimeout(r,ms));
const wait=async(fn,ms,label)=>{let until=Date.now()+ms;while(Date.now()<until){const val=await fn();if(val)return val;await sleep(250);}throw Error('Bounded timeout: '+label);};
const record=(name)=>{receipt.cases.push({name,elapsed_seconds:Math.round((Date.now()-t0)/1000)});console.log('PASS '+name);writeFileSync(work+'/receipt.json',JSON.stringify(receipt,null,2)+'\n');};
const intercept=createServer(async(req,res)=>{
  try{
    assert(['POST'].includes(req.method)&&['/v1/installs','/v1/sync'].includes(req.url));
    const chunks=[];let size=0;for await(const chunk of req){size+=chunk.length;assert(size<=262144);chunks.push(chunk);}
    const body=Buffer.concat(chunks);const parsed=JSON.parse(gunzipSync(body));
    const item={path:req.url,body:parsed,at:Date.now()};requests.push(item);
    if(offline){res.destroy();return;}
    const headers={...req.headers};delete headers.host;delete headers.connection;
    const upstream=await fetch('http://127.0.0.1:8798'+req.url,{method:'POST',headers,body});
    const out=Buffer.from(await upstream.arrayBuffer());item.status=upstream.status;item.reply=JSON.parse(out);
    res.writeHead(upstream.status,{'content-type':'application/json','content-length':out.length});res.end(out);
  }catch(error){if(!res.headersSent)res.writeHead(500);res.end('{"error":"fixture"}');}
});
await new Promise((resolve,reject)=>{intercept.once('error',reject);intercept.listen(0,'127.0.0.1',resolve);});
const endpoint='http://127.0.0.1:'+intercept.address().port;
const configFile=work+'/proxy/plugins/connection-guard/config.yml';let config=readFileSync(configFile,'utf8');assert(config.includes('http://127.0.0.1:0'));config=config.replace('http://127.0.0.1:0',endpoint);writeFileSync(configFile,config);
const originalConfig=sha(readFileSync(configFile));const languageFile=work+'/proxy/plugins/connection-guard/translation/de.yml';const languageHash=sha(readFileSync(languageFile));
const command=async(text,marker)=>{const start=consoleText.length;proxy.stdin.write(text+'\n');return await wait(()=>{const newer=consoleText.slice(start);return newer.includes(marker)?newer:false;},15000,'console '+marker);};
const login=async(name)=>{
  const script="import importlib.util,sys;spec=importlib.util.spec_from_file_location('fixture',sys.argv[1]);m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m);print(m.login(int(sys.argv[2]),sys.argv[3]))";
  const p=spawn('/Library/Frameworks/Python.framework/Versions/3.14/bin/python3',['-c',script,receipt.driver,String(receipt.port),name],{env:{...process.env,PYTHONDONTWRITEBYTECODE:'1'}});let out='',err='';p.stdout.on('data',d=>out+=d);p.stderr.on('data',d=>err+=d);
  const exit=await new Promise(r=>p.once('exit',r));assert.equal(exit,0,err);return out;
};
const configView=async(id)=>await(await page.request.get(base+'/api/installs/'+id+'/config')).json();
let id,network;
const save=async(values)=>{const response=await page.request.put(base+'/api/installs/'+id+'/config',{headers:{origin:base},data:{values,secrets:{},apply_to:'server'}});assert.equal(response.status(),200);const result=await response.json();const version=result.versions[id];await wait(async()=>{const cfg=await configView(id);return cfg.applied_version>=version&&!cfg.pending&&cfg;},65000,'applied settings');return version;};
try{
  receipt.status='running';
  const health=await fetch(base+'/api/config');assert.equal(health.status,200);const healthData=await health.json();assert.equal(healthData.turnstile_site_key,'');
  proxy=spawn(receipt.java,['-Xms64m','-Xmx256m','-Dio.netty.eventLoopThreads=2','-Dterminal.jline=false','-Dterminal.ansi=false','-jar','velocity.jar'],{cwd:work+'/proxy'});
  proxy.stdout.on('data',d=>consoleText+=d);proxy.stderr.on('data',d=>consoleText+=d);
  await wait(()=>consoleText.includes('Done ('),60000,'native startup');await command('cg reload','Konfiguration neu geladen!');
  await wait(()=>requests.some(r=>r.path==='/v1/sync'&&r.status===200),65000,'first anonymous sync');
  const anonymous=requests.find(r=>r.path==='/v1/sync');assert.equal(anonymous.body.events.length,0);assert.equal(anonymous.body.status.config,null);assert.deepEqual(anonymous.body.status.managed,[]);
  assert(!JSON.stringify(anonymous.body).includes('203.0.113.'));assert(!JSON.stringify(anonymous.body).includes('cgs_'));record('actual-unlinked-gzip-sync-has-no-personal-events-config-or-secret');
  const code=await wait(()=>consoleText.match(/\/link\/([0-9A-Z]{4}-[0-9A-Z]{4})/)?.[1],10000,'private fixture link');
  browser=await chromium.launch({channel:'chrome'});context=await browser.newContext({viewport:{width:1280,height:860},reducedMotion:'reduce'});
  await context.route('**/*',route=>{const url=new URL(route.request().url());if(url.origin===base)return route.continue();external.push(url.origin);return route.abort();});
  page=await context.newPage();const response=await page.request.post(base+'/api/auth/dev-login',{headers:{origin:base},data:{name:args.operator}});assert.equal(response.status(),200);
  await page.goto(base+'/link/'+code);await page.getByText('Data processing').first().waitFor();await page.getByLabel('Network name',{exact:true}).fill('Synthetic Integration');await page.getByPlaceholder('e.g. Lobby, Survival, Proxy').fill('Qualification');await page.getByRole('checkbox').check();await page.getByRole('button',{name:'Link server',exact:true}).click();await page.waitForURL(/\/setup/,{timeout:15000});
  id=new URL(page.url()).searchParams.get('server');network=new URL(page.url()).pathname.split('/')[2];assert(id);assert(network);record('browser-link-with-local-dev-session-and-fixture-terms-enters-existing-setup');
  await page.getByText('What should Connection Guard keep out?').waitFor();await page.screenshot({path:work+'/setup-goals.png'});await page.getByRole('button',{name:'Continue',exact:true}).click();await page.getByText('Which services should check players?').waitFor();await page.getByRole('button',{name:'Continue',exact:true}).click();await page.getByText('Start gently?').waitFor();await page.screenshot({path:work+'/setup-observe.png'});await page.getByRole('button',{name:/Finish setup/}).click();await page.getByText("You're protected").waitFor({timeout:90000});
  let view=await configView(id);assert.equal(view.mode,'OBSERVE');assert.equal(sha(readFileSync(configFile)),originalConfig);assert.equal(sha(readFileSync(languageFile)),languageHash);record('wizard-observe-settings-apply-through-real-native-reload-with-files-preserved');
  const controlled={'operation.mode':'OBSERVE','provider.vpn.proxycheck.enabled':false,'provider.vpn.ip-api.enabled':false,'provider.vpn.iphub.enabled':false,'provider.vpn.vpnapi.enabled':false,'provider.geo.service':'Disabled','required-positive-flags':1};await save(controlled);
  await page.getByRole('button',{name:/Try it out/}).click();await page.getByText(/Try it: join/).waitFor();assert((await login('CloudObserve')).includes('LOGIN_SUCCESS'));await page.getByText('It works',{exact:true}).waitFor({timeout:65000});await page.screenshot({path:work+'/setup-native-observation.png'});record('actual-offline-login-cloud-decision-reaches-wizard-with-only-owned-detector');
  await page.getByRole('button',{name:'Go to overview',exact:true}).click();await page.goto(base+'/n/'+network+'/settings?server='+id);await page.getByRole('heading',{name:/^Protection mode/}).waitFor();await page.getByRole('radio',{name:/^Enforce/}).click();await page.getByRole('button',{name:'Save and apply',exact:true}).click();await page.getByText('Waiting for Qualification to apply it').waitFor({timeout:10000});await page.screenshot({path:work+'/settings-waiting.png'});await page.getByText('Applied on Qualification.').waitFor({timeout:65000});await page.screenshot({path:work+'/settings-applied.png'});
  view=await configView(id);assert.equal(view.mode,'ENFORCE');assert((await login('CloudEnforce')).includes('Betreiberblock CloudEnforce'));assert.equal(sha(readFileSync(languageFile)),languageHash);record('browser-enforce-apply-produces-real-localized-denial-and-keeps-custom-language');
  const invalid=await page.request.put(base+'/api/installs/'+id+'/config',{headers:{origin:base},data:{values:{...controlled,'operation.mode':'OBSERVE','required-positive-flags':16},secrets:{},apply_to:'server'}});assert.equal(invalid.status(),200);const desired=(await invalid.json()).versions[id];
  await wait(async()=>{const value=await configView(id);return value.error?.version===desired&&value;},65000,'native rejection reported');view=await configView(id);assert.equal(view.mode,'ENFORCE');assert.equal(view.effective['required-positive-flags'],1);assert(view.error.message.includes('values redacted'));assert((await login('CloudRejected')).includes('Betreiberblock CloudRejected'));assert.equal(sha(readFileSync(configFile)),originalConfig);record('invalid-whole-draft-rejected-and-old-enforce-policy-language-overlay-retained');
  const reset=await page.request.post(base+'/api/installs/'+id+'/config/reset',{headers:{origin:base},data:{}});assert.equal(reset.status(),200);const resetVersion=(await reset.json()).version;await wait(async()=>{const value=await configView(id);return value.applied_version>=resetVersion&&!value.pending&&value;},65000,'native reset');assert((await login('CloudReset')).includes('LOGIN_SUCCESS'));assert.equal(sha(readFileSync(configFile)),originalConfig);assert.equal(sha(readFileSync(languageFile)),languageHash);record('remote-reset-restores-local-observe-config-without-writing-config-or-language');
  await command('cg cloud disable','bleibt nach Neustarts aus');await sleep(500);const count=requests.length;await sleep(6000);assert.equal(requests.length,count);assert((await login('CloudDisabled')).includes('LOGIN_SUCCESS'));record('command-disable-stops-cloud-requests-and-leaves-actual-login-behavior');
  offline=true;await command('cg cloud enable','Erster Sync innerhalb einer Minute');await wait(()=>requests.length>count,30000,'owned offline cloud request');
  const configText=readFileSync(configFile,'utf8');assert(configText.includes('mode: OBSERVE'));writeFileSync(configFile,configText.replace('mode: OBSERVE','mode: ENFORCE'));await command('cg reload','Konfiguration neu geladen!');const started=Date.now();assert((await login('CloudOffline')).includes('Betreiberblock CloudOffline'));const elapsed=Date.now()-started;assert(elapsed<1500);receipt.offline_login_duration_ms=elapsed;record('cloud-outage-does-not-change-denial-or-wait-for-cloud-http-timeout');
  assert.equal(sha(readFileSync(languageFile)),languageHash);assert.equal(external.length,0);receipt.browser_external_requests=external;receipt.browser_verified=true;receipt.status='passed';
}catch(error){if(page){await page.screenshot({path:work+'/failure-page.png',fullPage:true}).catch(()=>{});receipt.failure_page_url=page.url();}receipt.status='failed';receipt.error=String(error.message).slice(0,1500);console.error(receipt.error);process.exitCode=1;}
finally{
  if(proxy){if(proxy.exitCode===null){proxy.stdin.write('shutdown\n');await wait(()=>proxy.exitCode!==null,18000,'clean proxy shutdown').catch(()=>proxy.kill('SIGTERM'));}receipt.proxy_exit_code=proxy.exitCode;}
  if(browser)await browser.close();intercept.closeAllConnections();await new Promise(resolve=>intercept.close(resolve));
  writeFileSync(work+'/console.log',consoleText.replace(/\/link\/[0-9A-Z]{4}-[0-9A-Z]{4}/g,'/link/[fixture-redacted]'));
  receipt.console_sha256=sha(readFileSync(work+'/console.log'));receipt.driver_sha256=sha(readFileSync(new URL(import.meta.url)));receipt.request_count=requests.length;
  receipt.screenshots=['setup-goals.png','setup-observe.png','setup-native-observation.png','settings-waiting.png','settings-applied.png'].filter(name=>{try{return readFileSync(work+'/'+name);}catch{return false;}}).map(name=>({name,sha256:sha(readFileSync(work+'/'+name))}));
  writeFileSync(work+'/receipt.json',JSON.stringify(receipt,null,2)+'\n');console.log('Native GUI fixture '+receipt.status+'; owned proxy stopped; production and other checkouts untouched.');
}
