package com.github.gerolndnr.connectionguard.core.local;

import com.github.gerolndnr.connectionguard.core.ConnectionGuard;
import com.github.gerolndnr.connectionguard.core.cache.NoCacheProvider;
import com.github.gerolndnr.connectionguard.core.lookup.LookupSettings;
import com.github.gerolndnr.connectionguard.core.vpn.*;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class LocalInlineCompletionTest {
    @Test void immutableLocalListDoesNotSubmitAWorkerOrHopThreads(){
        long now=System.currentTimeMillis();String ip="2001:db8:ffff::37";
        LocalSource source=new LocalSource("inline-fixture",LocalSource.Kind.VPN,"Authored fixture","CC0","Synthetic",24,null);
        LocalSnapshot snapshot=LocalSnapshot.parse(source,(ip+"\n").getBytes(StandardCharsets.UTF_8),now,now,"IMPORT_AS_OF",now);
        ConnectionGuard.configureLookup(LookupSettings.defaults());ConnectionGuard.setCacheProvider(new NoCacheProvider());ConnectionGuard.setRequiredPositiveFlags(1);
        ConnectionGuard.setVpnProviders(new ArrayList<>(Collections.singletonList(new LocalVpnProvider(snapshot))));
        try{
            Thread caller=Thread.currentThread();AtomicReference<Thread> completion=new AtomicReference<>();
            CompletableFuture<VpnResult> result=ConnectionGuard.getVpnResult(ip);result.thenAccept(value->completion.set(Thread.currentThread()));
            assertTrue(result.isDone());assertTrue(result.join().isVpn());assertSame(caller,completion.get());assertEquals(0,ConnectionGuard.getLookupRuntime().getActiveWorkers());
        }finally{ConnectionGuard.setVpnProviders(new ArrayList<>());}
    }
}
