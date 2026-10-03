'use strict';
// Owned loopback synthetic gateway clients. Native encrypted data is supplied in private files.
const fs=require('fs');const path=require('path');const readline=require('readline');
const port=Number(process.argv[2]);
if (!Number.isInteger(port)||port<1||port>65535) throw new Error('Owned loopback port required');
const modulePath=path.resolve(process.argv[3]);
const mc=require(modulePath);const clients=new Map();
const hosts={CGNativeAllowed:fs.readFileSync(process.argv[4],'utf8'),CGNativeWrong:fs.readFileSync(process.argv[5],'utf8'),CGCorrupt:fs.readFileSync(process.argv[6],'utf8')};
for (const host of Object.values(hosts)) if (host.length<1||host.length>4096) throw new Error('Owned gateway header bounds');
function emit(name,kind,value) { process.stdout.write('CLIENT '+name+' '+kind+' '+JSON.stringify(value)+'\n'); }
readline.createInterface({input:process.stdin}).on('line',line => {
 const [command,name]=line.trim().split(' ');
 if (command==='stop') { for(const client of clients.values()) client.end();setTimeout(()=>process.exit(0),100);return; }
 if (command==='close') { const client=clients.get(name);if(client)client.end();return; }
 if(command!=='connect'||!/^[A-Za-z0-9_]{1,16}$/.test(name)||clients.has(name)||clients.size>=4)throw new Error('Synthetic fixture command');
 const client=mc.createClient({host:'127.0.0.1',port,username:name,auth:'offline',version:'1.21.11',fakeHost:hosts[name],hideErrors:true});clients.set(name,client);
 client.on('login',()=>emit(name,'login',true));client.on('end',()=>{clients.delete(name);emit(name,'end',true);});
 client.on('error',()=>emit(name,'error',true));
 client.on('packet',(data,meta)=>{
  if(meta.name==='disconnect'||meta.name==='kick_disconnect')emit(name,meta.name,data);
  if(meta.name==='position')client.write('teleport_confirm',{teleportId:data.teleportId});
 });
});
