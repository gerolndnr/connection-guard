package com.github.gerolndnr.connectionguard.core.local;

import com.google.gson.*;
import org.junit.jupiter.api.Test;
import java.net.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.lang.reflect.*;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.*;

/** Executes the unmodified released validator, not a reimplementation of its rules. */
class Intel060CompatibilityTest {
    private byte[] fixture() throws Exception {
        try(java.io.InputStream input=getClass().getResourceAsStream("/intel/manifest-with-proxy.json")) {
            return LocalDataStore.readBounded(input,65536);
        }
    }
    @Test void additionalProxyManifestPassesExactFrozen060CodeAndRequiredListsStayStrict() throws Exception {
        Path source=Paths.get(System.getProperty("cg.legacyIntel060.source"));
        assertEquals("dee70c73b5fa2ec1676c58cf62f67622fe64a955758659da7789878965689185",LocalSource.hash(Files.readAllBytes(source)),"Frozen validator must remain byte-for-byte equal to tag 0.6.0");
        final String name=IntelSnapshot.class.getName();
        try(URLClassLoader loader=new URLClassLoader(new URL[]{Paths.get(System.getProperty("cg.legacyIntel060.classes")).toUri().toURL()},getClass().getClassLoader()) {
            @Override protected Class<?> loadClass(String n,boolean resolve) throws ClassNotFoundException {
                if(!n.equals(name))return super.loadClass(n,resolve);
                Class<?> c=findLoadedClass(n);if(c==null)c=findClass(n);if(resolve)resolveClass(c);return c;
            }
        }) {
            Class<?> legacy=loader.loadClass(name);assertNotSame(IntelSnapshot.class,legacy);
            Method validate=legacy.getDeclaredMethod("manifest",byte[].class,long.class);validate.setAccessible(true);
            byte[] bytes=fixture();long now=Instant.parse("2026-10-07T01:00:00Z").toEpochMilli();
            JsonObject result=(JsonObject)validate.invoke(null,bytes,now);
            assertEquals(4,result.getAsJsonObject("lists").size());assertNotNull(result.getAsJsonObject("additional_lists").get("PROXY"));
            assertNotNull(IntelSnapshot.manifest(bytes,now));
            JsonObject forbidden=JsonParser.parseString(new String(bytes,StandardCharsets.UTF_8)).getAsJsonObject();
            forbidden.getAsJsonObject("lists").add("PROXY",forbidden.getAsJsonObject("additional_lists").get("PROXY"));
            byte[] bad=forbidden.toString().getBytes(StandardCharsets.UTF_8);
            assertThrows(InvocationTargetException.class,()->validate.invoke(null,bad,now));
            assertThrows(IllegalArgumentException.class,()->IntelSnapshot.manifest(bad,now));
        }
    }
}
