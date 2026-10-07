package fixture;

import com.google.inject.Inject;
import com.velocitypowered.api.proxy.*;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.event.*;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.github.gerolndnr.connectionguard.core.ConnectionGuard;
import com.github.gerolndnr.connectionguard.core.cache.*;
import java.nio.file.Path;
import java.util.concurrent.*;

/** Isolated native fixture only: a deliberately blocked loader must not hold real login packets. */
public final class BootingCacheFixture {
    private final ProxyServer proxy;
    private final CountDownLatch release=new CountDownLatch(1);
    @Inject public BootingCacheFixture(ProxyServer proxy){this.proxy=proxy;}
    @Subscribe(order=PostOrder.LAST) public void start(ProxyInitializeEvent event)throws Exception{
        CacheProvider previous=ConnectionGuard.getCacheProvider();previous.disband().get(5,TimeUnit.SECONDS);
        StartingCacheProvider delayed=new StartingCacheProvider(()->{
            try{release.await();}catch(InterruptedException failure){Thread.currentThread().interrupt();throw new IllegalStateException(failure);}
            return new TieredCacheProvider(new SQLiteCacheProvider(Path.of("fixture-cache.db").toAbsolutePath().toString()));
        });
        delayed.setNamespace(ConnectionGuard.getActiveDraft().cacheNamespace);ConnectionGuard.setCacheProvider(delayed);delayed.setup();
        proxy.getCommandManager().register("fixture-release-cache",(SimpleCommand) invocation->{
            if(!(invocation.source() instanceof ConsoleCommandSource))return;
            release.countDown();System.out.println("CACHE_FIXTURE_RELEASED");
        });
        System.out.println("CACHE_FIXTURE_BLOCKED");
    }
    @Subscribe public void stop(com.velocitypowered.api.event.proxy.ProxyShutdownEvent event){release.countDown();}
}
