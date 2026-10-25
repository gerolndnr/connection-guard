package com.github.gerolndnr.connectionguard.core.local;

import com.github.gerolndnr.connectionguard.core.lookup.*;
import com.github.gerolndnr.connectionguard.core.vpn.VpnResult;
import com.google.gson.*;
import java.time.Instant;
import java.util.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;

/** Four required authenticated indexes, an optional proxy index and optional label indexes, in one generation. */
public final class IntelSnapshot {
    public enum ProxyState { ABSENT, LOADED, SKIPPED }
    public static final String ID="connectionguard-intel";
    public final String generation;
    public final long asOf, fetchedAt;
    private final Map<LocalSource.Kind,LocalSnapshot> lists;
    private final Map<DetectionDetails.Label,LocalSnapshot> labels;
    public final ProxyState proxyState;
    public final IntelSettings settings;
    private IntelSnapshot(IntelSettings settings,String generation,long asOf,long fetchedAt,Map<LocalSource.Kind,LocalSnapshot> lists,ProxyState proxyState,Map<DetectionDetails.Label,LocalSnapshot> labels){
        this.settings=settings;this.generation=generation;this.asOf=asOf;this.fetchedAt=fetchedAt;
        this.lists=Collections.unmodifiableMap(new EnumMap<>(lists));
        this.labels=Collections.unmodifiableMap(new EnumMap<>(labels));
        this.proxyState=proxyState;
    }
    public static IntelSnapshot missing(IntelSettings settings){return new IntelSnapshot(settings,"missing",0,0,new EnumMap<>(LocalSource.Kind.class),ProxyState.ABSENT,new EnumMap<>(DetectionDetails.Label.class));}
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
    static boolean hasProxy(JsonObject json){
        if(!json.has("additional_lists"))return false;
        JsonElement extra=json.get("additional_lists");
        return !extra.isJsonObject()||extra.getAsJsonObject().has("PROXY");
    }
    /** Invalid optional metadata never affects validation of the four required lists. */
    static JsonObject proxyFile(JsonObject json){
        if(!hasProxy(json))return null;
        try {
            JsonObject file=json.getAsJsonObject("additional_lists").getAsJsonObject("PROXY");
            if(!file.get("file").isJsonPrimitive()||!file.get("file").getAsJsonPrimitive().isString()||!file.get("file").getAsString().equals("proxy.txt"))throw new IllegalArgumentException();
            if(!file.get("sha256").isJsonPrimitive()||!file.get("sha256").getAsJsonPrimitive().isString()||!file.get("sha256").getAsString().matches("[a-f0-9]{64}"))throw new IllegalArgumentException();
            number(file,"bytes",4*1024*1024);number(file,"networks",NetworkIndex.MAX_RECORDS);
            return file;
        }catch(RuntimeException invalid){return null;}
    }
    static String labelName(DetectionDetails.Label label){return label.name().toLowerCase(Locale.ROOT)+".txt";}
    /** A label list named in additional_lists (Intel builds from 25 October 2026); null when absent or invalid. */
    static JsonObject labelFile(JsonObject json,DetectionDetails.Label label){
        try {
            if(!json.has("additional_lists")||!json.getAsJsonObject("additional_lists").has(label.name()))return null;
            JsonObject file=json.getAsJsonObject("additional_lists").getAsJsonObject(label.name());
            if(!file.get("file").isJsonPrimitive()||!file.get("file").getAsJsonPrimitive().isString()||!file.get("file").getAsString().equals(labelName(label)))throw new IllegalArgumentException();
            if(!file.get("sha256").isJsonPrimitive()||!file.get("sha256").getAsJsonPrimitive().isString()||!file.get("sha256").getAsString().matches("[a-f0-9]{64}"))throw new IllegalArgumentException();
            number(file,"bytes",4*1024*1024);number(file,"networks",NetworkIndex.MAX_RECORDS);
            return file;
        }catch(RuntimeException invalid){return null;}
    }
    private static LocalSnapshot parseList(IntelSettings settings,LocalSource.Kind kind,JsonObject file,byte[] bytes,long asOf,long fetchedAt,long now){
        return parseList(settings,kind,kind.name().toLowerCase(Locale.ROOT),file,bytes,asOf,fetchedAt,now);
    }
    private static LocalSnapshot parseList(IntelSettings settings,LocalSource.Kind kind,String name,JsonObject file,byte[] bytes,long asOf,long fetchedAt,long now){
        if(bytes==null||bytes.length!=number(file,"bytes",4*1024*1024)||!LocalSource.hash(bytes).equals(file.get("sha256").getAsString()))throw new IllegalArgumentException("Intel list hash/size mismatch.");
        LocalSource source=new LocalSource("cg-intel-"+name,kind,"https://intel.connectionguard.net/","Per-source attribution in signed manifest","Connection Guard Intel; upstream source terms apply",settings.maxAgeHours,"");
        LocalSnapshot snapshot=LocalSnapshot.parse(source,bytes,asOf,fetchedAt,"SIGNED_MANIFEST",now);
        if(snapshot.recordCount()!=number(file,"networks",NetworkIndex.MAX_RECORDS))throw new IllegalArgumentException("Intel network count mismatch.");
        return snapshot;
    }
    static IntelSnapshot parse(IntelSettings settings,byte[] manifest,Map<LocalSource.Kind,byte[]> contents,long fetchedAt,long now){
        return parse(settings,manifest,contents,Collections.emptyMap(),fetchedAt,now);
    }
    static IntelSnapshot parse(IntelSettings settings,byte[] manifest,Map<LocalSource.Kind,byte[]> contents,Map<DetectionDetails.Label,byte[]> labelContents,long fetchedAt,long now){
        JsonObject json=manifest(manifest,now);long asOf=Instant.parse(json.get("as_of").getAsString()).toEpochMilli();
        Map<LocalSource.Kind,LocalSnapshot> parsed=new EnumMap<>(LocalSource.Kind.class);
        for(LocalSource.Kind kind:kinds()){
            parsed.put(kind,parseList(settings,kind,json.getAsJsonObject("lists").getAsJsonObject(kind.name()),contents.get(kind),asOf,fetchedAt,now));
        }
        ProxyState proxy=hasProxy(json)?ProxyState.SKIPPED:ProxyState.ABSENT;
        JsonObject file=proxyFile(json);
        if(file!=null)try {
            parsed.put(LocalSource.Kind.PROXY,parseList(settings,LocalSource.Kind.PROXY,file,contents.get(LocalSource.Kind.PROXY),asOf,fetchedAt,now));
            proxy=ProxyState.LOADED;
        }catch(RuntimeException invalid){/* Optional list is isolated from authenticated base data. */}
        Map<DetectionDetails.Label,LocalSnapshot> labels=new EnumMap<>(DetectionDetails.Label.class);
        for(DetectionDetails.Label label:DetectionDetails.Label.values()){
            JsonObject labelFile=labelFile(json,label);
            // Labels are review-only like HOSTING; the kind only names the index, it never votes.
            if(labelFile!=null)try {labels.put(label,parseList(settings,LocalSource.Kind.HOSTING,label.name().toLowerCase(Locale.ROOT),labelFile,labelContents.get(label),asOf,fetchedAt,now));}
            catch(RuntimeException invalid){/* Like the proxy list, a broken label list never touches the base lists. */}
        }
        return new IntelSnapshot(settings,LocalSource.hash(manifest),asOf,fetchedAt,parsed,proxy,labels);
    }
    public int recordCount(LocalSource.Kind kind){LocalSnapshot list=lists.get(kind);return list==null?0:list.recordCount();}
    public int recordCount(DetectionDetails.Label label){LocalSnapshot list=labels.get(label);return list==null?0:list.recordCount();}
    public Set<DetectionDetails.Label> loadedLabels(){return labels.isEmpty()?Collections.emptySet():EnumSet.copyOf(labels.keySet());}
    public boolean contains(LocalSource.Kind kind,String ip,long now){return readiness(now)==FailureReason.NONE&&lists.containsKey(kind)&&lists.get(kind).contains(new NetworkIndex.Address(ip));}
    public FailureReason readiness(long now){return asOf==0?FailureReason.NO_EVIDENCE:now>=validUntil()?FailureReason.STALE_DATA:FailureReason.NONE;}
    public long validUntil(){return asOf==0?0:asOf+settings.maxAgeHours*3600000L;}
    public VpnResult vpn(String ip,long now){
        FailureReason ready=readiness(now);Map<DetectionDetails.Type,Boolean> types=new EnumMap<>(DetectionDetails.Type.class);
        Set<DetectionDetails.Label> found=EnumSet.noneOf(DetectionDetails.Label.class);
        if(ready==FailureReason.NONE){
            NetworkIndex.Address literal=new NetworkIndex.Address(ip);
            for(Map.Entry<LocalSource.Kind,LocalSnapshot> list:lists.entrySet())if(list.getValue().contains(literal))types.put(DetectionDetails.Type.valueOf(list.getKey().name()),true);
            for(Map.Entry<DetectionDetails.Label,LocalSnapshot> list:labels.entrySet())if(list.getValue().contains(literal))found.add(list.getKey());
        }
        boolean booster=found.contains(DetectionDetails.Label.BOOSTER);
        boolean listed=types.containsKey(DetectionDetails.Type.VPN)||types.containsKey(DetectionDetails.Type.TOR)||types.containsKey(DetectionDetails.Type.PROXY);
        boolean positive=listed||settings.relay==IntelSettings.Relay.VPN&&types.containsKey(DetectionDetails.Type.RELAY)
                ||settings.booster==IntelSettings.Booster.VPN&&booster;
        boolean relayAllowed=!positive&&types.containsKey(DetectionDetails.Type.RELAY)&&settings.relay==IntelSettings.Relay.ALLOW;
        boolean boosterAllowed=!positive&&booster&&settings.booster==IntelSettings.Booster.ALLOW;
        VpnResult result=new VpnResult(ip,positive,positive?Optional.of((listed||!booster?"Listed as "+types.keySet():"Gaming booster node")+" (Connection Guard Intel, "+Instant.ofEpochMilli(asOf)+")"):Optional.empty());
        result.setDetails(new DetectionDetails(types,null,null,null,null,null,null).withDataAsOf(asOf==0?null:asOf).withLabels(found));
        if(!positive&&!relayAllowed&&!boosterAllowed)result.setUnknown(ready==FailureReason.NONE?FailureReason.NO_EVIDENCE:ready);
        result.setSourceVersion(generation.equals("missing")?null:generation);result.setValidUntil(ready==FailureReason.NONE?validUntil():0);return result;
    }
    public String describe(long now){return ID+" state="+readiness(now)+" as_of="+(asOf==0?"UNKNOWN":Instant.ofEpochMilli(asOf))+" generation="+generation+" relay="+settings.relay+" booster="+settings.booster+" fetchAgeMs="+(fetchedAt==0?"UNKNOWN":Math.max(0,now-fetchedAt))+" entries={VPN="+recordCount(LocalSource.Kind.VPN)+", TOR="+recordCount(LocalSource.Kind.TOR)+", RELAY="+recordCount(LocalSource.Kind.RELAY)+", HOSTING="+recordCount(LocalSource.Kind.HOSTING)+", PROXY="+recordCount(LocalSource.Kind.PROXY)+"}"+(labels.isEmpty()?"":" labels={BOOSTER="+recordCount(DetectionDetails.Label.BOOSTER)+", MOBILE="+recordCount(DetectionDetails.Label.MOBILE)+", SHARED="+recordCount(DetectionDetails.Label.SHARED)+"}")+" PROXY="+proxyState+(proxyState==ProxyState.SKIPPED?" (optional list rejected/unavailable; base lists retained)":"");}
}
