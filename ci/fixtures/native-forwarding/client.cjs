"use strict";
// Own synthetic clients only. Gateway MAC assertions do not authenticate a Mojang account.
const fs = require("fs"), path = require("path"), crypto = require("crypto"), readline = require("readline");
const root = path.resolve(__dirname, "../../../..");
const mc = require(path.join(root, ".runtime/minecraft-client/node_modules/minecraft-protocol"));
const port = Number(process.argv[2]), secretPath = process.argv[3];
if (!Number.isInteger(port) || port < 1 || port > 65535) throw new Error("Invalid loopback port");
const secret = secretPath ? fs.readFileSync(secretPath, "utf8").trim() : null;
if (secret !== null && !/^[0-9a-f]{64}$/.test(secret)) throw new Error("Invalid private fixture secret");
const clients = new Map();
function varint(n) { const bytes=[]; do { const v=n&127; n>>>=7; bytes.push(v|(n?128:0)); } while(n); return Buffer.from(bytes); }
function string(s) { const b=Buffer.from(s); return Buffer.concat([varint(b.length), b]); }
function id(name) { const b=crypto.createHash("md5").update("OfflinePlayer:"+name).digest(); b[6]=(b[6]&15)|48; b[8]=(b[8]&63)|128; return b; }
function emit(name, kind, value) { process.stdout.write("CLIENT "+name+" "+kind+" "+JSON.stringify(value, (_,v)=>typeof v==="bigint"?v.toString():v)+"\n"); }
readline.createInterface({input:process.stdin}).on("line", line=> {
  const [command,name,mode] = line.trim().split(" ");
  if(command === "stop") { for(const c of clients.values()) c.end(); setTimeout(()=>process.exit(0),100); return; }
  if(command === "close") { const c=clients.get(name); if(c) c.end(); return; }
  if(command!=="connect" || !/^[A-Za-z0-9_]{1,16}$/.test(name) || clients.has(name) || clients.size>=16 || !["proxy","valid","tamper","missing"].includes(mode)) throw new Error("Invalid synthetic command");
  if(mode!=="proxy" && !secret) throw new Error("Private forwarding fixture key required");
  const c=mc.createClient({host:"127.0.0.1",port,username:name,auth:"offline",version:"1.21.11",hideErrors:true}); clients.set(name,c);
  if(mode!=="proxy") {
    c.removeAllListeners("login_plugin_request");
    c.on("login_plugin_request", packet=> {
      if(packet.channel!=="velocity:player_info" || mode==="missing") { c.write("login_plugin_response",{messageId:packet.messageId}); return; }
      const body=Buffer.concat([varint(1),string("203.0.113.10"),id(name),string(name),varint(0)]);
      const mac=crypto.createHmac("sha256",secret).update(body).digest(); if(mode==="tamper") mac[0]^=1;
      c.write("login_plugin_response",{messageId:packet.messageId,data:Buffer.concat([mac,body])}); emit(name,"forwarded",{version:1,mode});
    });
  }
  c.on("login",()=>emit(name,"login",true));
  c.on("end",reason=>{ clients.delete(name); emit(name,"end",reason); });
  c.on("error",err=>emit(name,"error",err.message));
  c.on("packet",(data,meta)=>{ if(meta.name==="disconnect" || meta.name==="kick_disconnect") emit(name,meta.name,data); if(meta.name==="position") c.write("teleport_confirm",{teleportId:data.teleportId}); });
});
