package com.github.gerolndnr.connectionguard.core.local;

import com.google.gson.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

/** Synthetic indexes only; signed bundle/download behavior is tested separately. */
public final class IntelFixtures {
    private IntelFixtures() { }
    public static IntelSnapshot hosting(long asOf,long now) {
        JsonObject manifest=new JsonObject(),lists=new JsonObject();
        manifest.addProperty("schema",1);manifest.addProperty("evaluation_only",false);manifest.addProperty("as_of",Instant.ofEpochMilli(asOf).toString());
        Map<LocalSource.Kind,byte[]> files=new EnumMap<>(LocalSource.Kind.class);
        for(LocalSource.Kind kind:IntelSnapshot.kinds()) {
            String network=kind==LocalSource.Kind.HOSTING?"203.0.113.0/28\n":"192.0.2.0/28\n";
            byte[] bytes=network.getBytes(StandardCharsets.UTF_8);files.put(kind,bytes);JsonObject file=new JsonObject();
            file.addProperty("file",kind.name().toLowerCase(Locale.ROOT)+".txt");file.addProperty("bytes",bytes.length);file.addProperty("networks",1);file.addProperty("sha256",LocalSource.hash(bytes));lists.add(kind.name(),file);
        }
        manifest.add("lists",lists);
        return IntelSnapshot.parse(new IntelSettings(k->k.endsWith("enabled")?true:null),manifest.toString().getBytes(StandardCharsets.UTF_8),files,now,now);
    }
}
