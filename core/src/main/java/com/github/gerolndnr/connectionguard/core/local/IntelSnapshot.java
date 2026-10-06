package com.github.gerolndnr.connectionguard.core.local;

import com.github.gerolndnr.connectionguard.core.lookup.*;
import com.github.gerolndnr.connectionguard.core.vpn.VpnResult;
import com.google.gson.*;
import java.time.Instant;
import java.util.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;

/** Four authenticated immutable indexes, published as one generation. */
public final class IntelSnapshot {
    public static final String ID="connectionguard-intel";
    public final String generation;
    public final long asOf, fetchedAt;
    private final Map<LocalSource.Kind,LocalSnapshot> lists;
    public final IntelSettings settings;
    private IntelSnapshot(IntelSettings settings,String generation,long asOf,long fetchedAt,Map<LocalSource.Kind,LocalSnapshot> lists){
        this.settings=settings;this.generation=generation;this.asOf=asOf;this.fetchedAt=fetchedAt;
        this.lists=Collections.unmodifiableMap(new EnumMap<>(lists));
    }
    public static IntelSnapshot missing(IntelSettings settings){return new IntelSnapshot(settings,"missing",0,0,new EnumMap<>(LocalSource.Kind.class));}
    static JsonObject manifest(byte[] bytes,long now){
        if(bytes.length==0||bytes.length>65536)throw new IllegalArgumentException("Intel manifest exceeds its bound.");
        try {
            String text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            JsonObject json=JsonParser.parseString(text).getAsJsonObject();
            if(number(json,"schema",1)!=1||!json.get("evaluation_only").isJsonPrimitive()||!json.get("evaluation_only").getAsJsonPrimitive().isBoolean()||json.get("evaluation_only").getAsBoolean())throw new IllegalArgumentException();
            if(!json.get("as_of").isJsonPrimitive()||!json.get("as_of").getAsJsonPrimitive().isString())throw new IllegalArgumentException();
            long asOf=Instant.parse(json.get("as_of").getAsString()).toEpochMilli();
            if(asOf<=0||asOf>now+300000)throw new IllegalArgumentException();
            JsonObject lists=json.getAsJsonObject("lists");
            if(!lists.keySet().equals(new HashSet<>(Arrays.asList("VPN","TOR","RELAY","HOSTING"))))throw new IllegalArgumentException();
            for(LocalSource.Kind kind:kinds()){
                JsonObject file=lists.getAsJsonObject(kind.name());String expected=kind.name().toLowerCase(Locale.ROOT)+".txt";
                if(!file.get("file").isJsonPrimitive()||!file.get("file").getAsJsonPrimitive().isString()||!file.get("file").getAsString().equals(expected))throw new IllegalArgumentException();
                if(!file.get("sha256").isJsonPrimitive()||!file.get("sha256").getAsJsonPrimitive().isString()||!file.get("sha256").getAsString().matches("[a-f0-9]{64}"))throw new IllegalArgumentException();
                number(file,"bytes",4*1024*1024);number(file,"networks",NetworkIndex.MAX_RECORDS);
            }
            return json;
        } catch(RuntimeException|CharacterCodingException invalid){throw new IllegalArgumentException("Intel manifest is invalid (contents redacted).");}
    }
    static int number(JsonObject object,String name,int max){
        JsonElement e=object.get(name);
        if(e==null||!e.isJsonPrimitive()||!e.getAsJsonPrimitive().isNumber()||!e.getAsString().matches("[1-9][0-9]{0,8}"))throw new IllegalArgumentException();
        int n=Integer.parseInt(e.getAsString());if(n>max)throw new IllegalArgumentException();return n;
    }
    static List<LocalSource.Kind> kinds(){return Arrays.asList(LocalSource.Kind.VPN,LocalSource.Kind.TOR,LocalSource.Kind.RELAY,LocalSource.Kind.HOSTING);}
    static IntelSnapshot parse(IntelSettings settings,byte[] manifest,Map<LocalSource.Kind,byte[]> contents,long fetchedAt,long now){
        JsonObject json=manifest(manifest,now);long asOf=Instant.parse(json.get("as_of").getAsString()).toEpochMilli();
        Map<LocalSource.Kind,LocalSnapshot> parsed=new EnumMap<>(LocalSource.Kind.class);
        for(LocalSource.Kind kind:kinds()){
            JsonObject file=json.getAsJsonObject("lists").getAsJsonObject(kind.name());byte[] bytes=contents.get(kind);
            if(bytes==null||bytes.length!=number(file,"bytes",4*1024*1024)||!LocalSource.hash(bytes).equals(file.get("sha256").getAsString()))throw new IllegalArgumentException("Intel list hash/size mismatch.");
            LocalSource source=new LocalSource("cg-intel-"+kind.name().toLowerCase(Locale.ROOT),kind,"https://intel.connectionguard.net/","Per-source attribution in signed manifest","Connection Guard Intel; upstream source terms apply",settings.maxAgeHours,"");
            LocalSnapshot snapshot=LocalSnapshot.parse(source,bytes,asOf,fetchedAt,"SIGNED_MANIFEST",now);
            if(snapshot.recordCount()!=number(file,"networks",NetworkIndex.MAX_RECORDS))throw new IllegalArgumentException("Intel network count mismatch.");
            parsed.put(kind,snapshot);
        }
        return new IntelSnapshot(settings,LocalSource.hash(manifest),asOf,fetchedAt,parsed);
    }
    public FailureReason readiness(long now){return asOf==0?FailureReason.NO_EVIDENCE:now>=validUntil()?FailureReason.STALE_DATA:FailureReason.NONE;}
    public long validUntil(){return asOf==0?0:asOf+settings.maxAgeHours*3600000L;}
    public VpnResult vpn(String ip,long now){
        FailureReason ready=readiness(now);Map<DetectionDetails.Type,Boolean> types=new EnumMap<>(DetectionDetails.Type.class);
        if(ready==FailureReason.NONE)for(LocalSource.Kind kind:kinds())if(Boolean.TRUE.equals(lists.get(kind).vpn(ip,now).getDetails().get(DetectionDetails.Type.valueOf(kind.name()))))types.put(DetectionDetails.Type.valueOf(kind.name()),true);
        boolean positive=types.containsKey(DetectionDetails.Type.VPN)||types.containsKey(DetectionDetails.Type.TOR)||settings.relay==IntelSettings.Relay.VPN&&types.containsKey(DetectionDetails.Type.RELAY);
        boolean relayAllowed=!positive&&types.containsKey(DetectionDetails.Type.RELAY)&&settings.relay==IntelSettings.Relay.ALLOW;
        VpnResult result=new VpnResult(ip,positive,positive?Optional.of("Listed as "+types.keySet()+" (Connection Guard Intel, "+Instant.ofEpochMilli(asOf)+")"):Optional.empty());
        result.setDetails(new DetectionDetails(types,null,null,null,null,null,null).withDataAsOf(asOf==0?null:asOf));
        if(!positive&&!relayAllowed)result.setUnknown(ready==FailureReason.NONE?FailureReason.NO_EVIDENCE:ready);
        result.setSourceVersion(generation.equals("missing")?null:generation);result.setValidUntil(ready==FailureReason.NONE?validUntil():0);return result;
    }
    public String describe(long now){return ID+" state="+readiness(now)+" as_of="+(asOf==0?"UNKNOWN":Instant.ofEpochMilli(asOf))+" generation="+generation+" relay="+settings.relay+" fetchAgeMs="+(fetchedAt==0?"UNKNOWN":Math.max(0,now-fetchedAt));}
}
