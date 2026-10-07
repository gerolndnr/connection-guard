package com.github.gerolndnr.connectionguard.core.local;

import com.google.gson.*;
import okhttp3.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.security.spec.*;
import java.security.interfaces.ECPublicKey;
import java.util.*;

/** Authenticated bundle on disk: all files precede one atomic generation pointer. */
public final class IntelDataStore {
    public static final String HOST="https://intel.connectionguard.net/";
    private final Path root;
    private final IntelSettings settings;
    private final PublicKey key;
    public IntelDataStore(Path directory,IntelSettings settings){this(directory,settings,pinnedKey());}
    IntelDataStore(Path directory,IntelSettings settings,PublicKey key){
        if(directory==null)throw new IllegalArgumentException("Intel requires the plugin data directory.");
        this.root=directory.toAbsolutePath().normalize().resolve("local-data").resolve(IntelSnapshot.ID);this.settings=settings;this.key=key;
        LocalDataStore.checkParents(root);checkKey(key);
    }
    static PublicKey pinnedKey(){
        try(InputStream input=IntelDataStore.class.getResourceAsStream("/connectionguard-intel-signing-key.pub.pem")){
            String pem=new String(LocalDataStore.readBounded(input,4096),StandardCharsets.US_ASCII);
            String encoded=pem.replace("-----BEGIN PUBLIC KEY-----","").replace("-----END PUBLIC KEY-----","").replaceAll("\\s","");
            PublicKey key=KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(encoded)));checkKey(key);return key;
        }catch(Exception unavailable){throw new IllegalStateException("Bundled Intel trust key is invalid.");}
    }
    private static void checkKey(PublicKey key){
        try {
            if(!(key instanceof ECPublicKey))throw new GeneralSecurityException();
            AlgorithmParameters p=AlgorithmParameters.getInstance("EC");p.init(new ECGenParameterSpec("secp256r1"));ECParameterSpec expected=p.getParameterSpec(ECParameterSpec.class),actual=((ECPublicKey)key).getParams();
            if(!actual.getCurve().equals(expected.getCurve())||!actual.getGenerator().equals(expected.getGenerator())||!actual.getOrder().equals(expected.getOrder())||actual.getCofactor()!=expected.getCofactor())throw new GeneralSecurityException();
        }catch(GeneralSecurityException invalid){throw new IllegalArgumentException("Intel requires an ECDSA P-256 trust key.");}
    }
    void verify(byte[] manifest,byte[] encodedSignature)throws IOException{
        if(manifest.length==0||manifest.length>65536||encodedSignature.length==0||encodedSignature.length>256)throw new IOException("Intel manifest/signature exceeds its bound.");
        try {
            String encoded=new String(encodedSignature,StandardCharsets.US_ASCII).trim();
            byte[] signature=Base64.getDecoder().decode(encoded);if(signature.length<64||signature.length>80)throw new GeneralSecurityException();
            Signature verifier=Signature.getInstance("SHA256withECDSA");verifier.initVerify(key);verifier.update(manifest);
            if(!verifier.verify(signature))throw new GeneralSecurityException();
        }catch(GeneralSecurityException|IllegalArgumentException invalid){throw new IOException("Intel signature rejected; previous generation preserved.");}
    }
    public synchronized IntelSnapshot load(long now)throws IOException{
        Path pointer=root.resolve("current.json");LocalDataStore.checkParents(pointer);
        if(!Files.exists(pointer,LinkOption.NOFOLLOW_LINKS))return IntelSnapshot.missing(settings);
        try {
            JsonObject json=JsonParser.parseString(new String(LocalDataStore.read(pointer,1024),StandardCharsets.UTF_8)).getAsJsonObject();
            if(!json.keySet().equals(new HashSet<>(Arrays.asList("generation","fetchedAt"))))throw new IllegalArgumentException();
            JsonElement gen=json.get("generation"),fetched=json.get("fetchedAt");
            if(!gen.isJsonPrimitive()||!gen.getAsJsonPrimitive().isString()||!gen.getAsString().matches("[a-f0-9]{64}")||!fetched.isJsonPrimitive()||!fetched.getAsJsonPrimitive().isNumber()||!fetched.getAsString().matches("[1-9][0-9]{0,18}"))throw new IllegalArgumentException();
            Path dir=root.resolve(gen.getAsString());byte[] manifest=LocalDataStore.read(dir.resolve("manifest.json"),65536);
            if(!LocalSource.hash(manifest).equals(gen.getAsString()))throw new IllegalArgumentException();
            verify(manifest,LocalDataStore.read(dir.resolve("manifest.json.sig"),256));
            Map<LocalSource.Kind,byte[]> files=new EnumMap<>(LocalSource.Kind.class);
            for(LocalSource.Kind kind:IntelSnapshot.kinds())files.put(kind,LocalDataStore.read(dir.resolve(kind.name().toLowerCase(Locale.ROOT)+".txt"),4*1024*1024));
            if(IntelSnapshot.proxyFile(IntelSnapshot.manifest(manifest,now))!=null)try {
                files.put(LocalSource.Kind.PROXY,LocalDataStore.read(dir.resolve("proxy.txt"),4*1024*1024));
            }catch(IOException|RuntimeException unavailable){/* A missing/broken optional list cannot remove base protection. */}
            return IntelSnapshot.parse(settings,manifest,files,Long.parseLong(fetched.getAsString()),now);
        }catch(RuntimeException invalid){throw new IOException("Saved Intel generation invalid; active data preserved (contents redacted).");}
    }
    public synchronized IntelSnapshot update()throws IOException{return fetch(HttpUrl.get(HOST),LocalListDownloader.client());}
    // Package-private fixture transport; no YAML/Cloud endpoint or trust-key override exists.
    synchronized IntelSnapshot updateFixture(HttpUrl base,OkHttpClient client)throws IOException{
        if(!base.host().equals("127.0.0.1")||!base.scheme().equals("http")||!base.username().isEmpty()||!base.password().isEmpty()||base.query()!=null||base.fragment()!=null)throw new IllegalArgumentException("Intel fixture must use owned loopback.");
        return fetch(base,client);
    }
    private IntelSnapshot fetch(HttpUrl base,OkHttpClient client)throws IOException{
        try {
            byte[] manifest=LocalListDownloader.bytes(base.newBuilder().addPathSegment("manifest.json").build(),65536,client);
            byte[] signature=LocalListDownloader.bytes(base.newBuilder().addPathSegment("manifest.json.sig").build(),256,client);
            verify(manifest,signature);long now=System.currentTimeMillis();JsonObject json=IntelSnapshot.manifest(manifest,now);
            IntelSnapshot previous=load(now);
            long asOf=java.time.Instant.parse(json.get("as_of").getAsString()).toEpochMilli();
            if(asOf<previous.asOf||asOf==previous.asOf&&!previous.generation.equals(LocalSource.hash(manifest)))throw new IOException("Intel rollback rejected.");
            Map<LocalSource.Kind,byte[]> files=new EnumMap<>(LocalSource.Kind.class);
            for(LocalSource.Kind kind:IntelSnapshot.kinds()){
                JsonObject file=json.getAsJsonObject("lists").getAsJsonObject(kind.name());
                files.put(kind,LocalListDownloader.bytes(base.newBuilder().addPathSegment(file.get("file").getAsString()).build(),IntelSnapshot.number(file,"bytes",4*1024*1024),client));
            }
            JsonObject proxy=IntelSnapshot.proxyFile(json);
            if(proxy!=null)try {
                files.put(LocalSource.Kind.PROXY,LocalListDownloader.bytes(base.newBuilder().addPathSegment("proxy.txt").build(),IntelSnapshot.number(proxy,"bytes",4*1024*1024),client));
            }catch(IOException unavailable){/* Optional transport failures are reported by the snapshot. */}
            now=System.currentTimeMillis();IntelSnapshot next=IntelSnapshot.parse(settings,manifest,files,now,now);
            if(next.readiness(now)!=com.github.gerolndnr.connectionguard.core.lookup.FailureReason.NONE)throw new IOException("Intel publication is already stale.");
            commit(next,manifest,signature,files);return next;
        }catch(RuntimeException invalid){throw new IOException("Intel update invalid; previous signed generation preserved (contents redacted).");}
    }
    private void commit(IntelSnapshot next,byte[] manifest,byte[] signature,Map<LocalSource.Kind,byte[]> files)throws IOException{
        LocalDataStore.checkParents(root);Files.createDirectories(root);LocalDataStore.privateMode(root,"rwx------");
        Path dir=root.resolve(next.generation);LocalDataStore.checkParents(dir);Files.createDirectories(dir);LocalDataStore.privateMode(dir,"rwx------");
        for(LocalSource.Kind kind:IntelSnapshot.kinds())LocalDataStore.writeAtomic(dir.resolve(kind.name().toLowerCase(Locale.ROOT)+".txt"),files.get(kind));
        if(next.proxyState==IntelSnapshot.ProxyState.LOADED)LocalDataStore.writeAtomic(dir.resolve("proxy.txt"),files.get(LocalSource.Kind.PROXY));
        LocalDataStore.writeAtomic(dir.resolve("manifest.json"),manifest);LocalDataStore.writeAtomic(dir.resolve("manifest.json.sig"),signature);
        JsonObject pointer=new JsonObject();pointer.addProperty("generation",next.generation);pointer.addProperty("fetchedAt",next.fetchedAt);
        LocalDataStore.writeAtomic(root.resolve("current.json"),pointer.toString().getBytes(StandardCharsets.UTF_8));
        // Complete readers live on the heap; retain only the committed disk generation.
        try(DirectoryStream<Path> old=Files.newDirectoryStream(root)){
            for(Path entry:old)if(!entry.equals(dir)&&entry.getFileName().toString().matches("[a-f0-9]{64}")&&Files.isDirectory(entry,LinkOption.NOFOLLOW_LINKS)&&!Files.isSymbolicLink(entry)){
                try(DirectoryStream<Path> contents=Files.newDirectoryStream(entry)){
                    for(Path file:contents)if(file.getFileName().toString().matches("(?:vpn|tor|relay|hosting|proxy)\\.txt|manifest\\.json(?:\\.sig)?")&&!Files.isSymbolicLink(file))try{Files.deleteIfExists(file);}catch(IOException ignored){}
                }catch(IOException ignored){}
                try{Files.deleteIfExists(entry);}catch(IOException ignored){}
            }
        }catch(IOException ignored){/* Successful pointer commit remains successful. */}
    }
}
