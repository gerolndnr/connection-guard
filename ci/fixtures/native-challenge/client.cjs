'use strict';
// Controlled offline loopback protocol760/774 client. No account token, production state or server completion API.
const path=require('path'); const readline=require('readline');
const mc=require(path.resolve(process.argv[3])); const port=Number(process.argv[2]);
if(!Number.isInteger(port)||port<1||port>65535)throw Error('Invalid owned proxy port');
const version=process.argv[4]||'1.19.2';
if(!['1.19.2','1.21.11'].includes(version))throw Error('Unqualified fixture version');
const clients=new Map(); const generations=new Map();
function emit(name,generation,kind,value){process.stdout.write(JSON.stringify({name,generation,kind,value},(_,v)=>typeof v==='bigint'?v.toString():v)+'\n');}
readline.createInterface({input:process.stdin}).on('line',line=>{
 const [command,name,value]=line.trim().split(' ');
 if(command==='stop'){for(const c of clients.values())c.end();setTimeout(()=>process.exit(0),100);return;}
 if(command==='close'){const c=clients.get(name);if(c)c.end();return;}
 if(command==='state'){const c=clients.get(name);if(!c)throw Error('Missing owned client');emit(name,generations.get(name),'connection_state',{state:c.state,socketDestroyed:c.socket.destroyed});return;}
 if(command==='answer'){if(!/^[0-9]{6}$/.test(value)||!clients.has(name))throw Error('Invalid answer');clients.get(name).chat(value);return;}
 if(command!=='connect'||!/^[A-Za-z0-9_]{1,16}$/.test(name)||clients.has(name)||clients.size>=16)throw Error('Invalid fixture command');
 const generation=(generations.get(name)||0)+1;generations.set(name,generation);
 const c=mc.createClient({host:'127.0.0.1',port,username:name,auth:'offline',version,hideErrors:true});clients.set(name,c);
 c.on('login',()=>emit(name,generation,'login',true));c.on('end',reason=>{clients.delete(name);emit(name,generation,'end',reason);});c.on('error',e=>emit(name,generation,'error',e.message));
 c.on('packet',(d,m)=>{
  if(['disconnect','kick_disconnect','system_chat'].includes(m.name))emit(name,generation,m.name,d);
  if(m.name==='position')c.write('teleport_confirm',{teleportId:d.teleportId});
  if(m.name==='map')emit(name,generation,'map',{columns:d.columns,rows:d.rows,x:d.x,y:d.y,data:Buffer.from(d.data).toString('base64')});
 });
});
