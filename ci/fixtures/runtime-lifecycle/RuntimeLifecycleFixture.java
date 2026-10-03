package fixture;

import com.github.gerolndnr.connectionguard.api.v1.*;
import com.github.gerolndnr.connectionguard.core.ConnectionGuard;
import com.google.inject.Inject;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.*;
import com.velocitypowered.api.proxy.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import net.kyori.adventure.text.Component;

/** Synthetic console-only probe; never install on a public server. */
public final class RuntimeLifecycleFixture {
    private final ProxyServer proxy;
    private final AtomicInteger calls = new AtomicInteger(), blocked = new AtomicInteger();
    private final CountDownLatch release = new CountDownLatch(1);
    private volatile boolean block = true;
    private ProviderRegistration registration;
    @Inject public RuntimeLifecycleFixture(ProxyServer proxy) { this.proxy = proxy; }
    @Subscribe public void initialize(ProxyInitializeEvent event) {
        registration = ConnectionGuardApi.registerProvider(new ProviderDescriptor("runtime-fixture", "1",
                String.join("", Collections.nCopies(64, "b")), true), ip -> {
            if (!ip.equals("127.0.0.1")) throw new IllegalArgumentException("Unexpected synthetic address");
            calls.incrementAndGet(); boolean held = block;
            if (held) {
                blocked.incrementAndGet();
                try { while (release.getCount() > 0) { try { release.await(); } catch (InterruptedException ignored) { } } }
                finally { blocked.decrementAndGet(); }
            }
            // The late positive must never replace the earlier UNKNOWN login result.
            return CompletableFuture.completedFuture(held ? DetectionObservation.positive(DetectionMetadata.empty())
                    : DetectionObservation.negative(DetectionMetadata.empty()));
        });
        proxy.getCommandManager().register("fixture-runtime", (SimpleCommand) invocation -> {
            if (!(invocation.source() instanceof ConsoleCommandSource)) return;
            if (Arrays.equals(invocation.arguments(), new String[]{"release"})) { block = false; release.countDown(); }
            else if (!Arrays.equals(invocation.arguments(), new String[]{"status"})) return;
            long workers = Thread.getAllStackTraces().keySet().stream().filter(t -> t.isAlive()
                    && t.getName().startsWith("ConnectionGuard-lookup-")).count();
            invocation.source().sendMessage(Component.text("RUNTIME_STATUS calls=" + calls.get() + " blocked=" + blocked.get()
                    + " workers=" + workers + " configured=" + ConnectionGuard.getLookupRuntime().getSettings().workers
                    + " idle=" + ConnectionGuard.getLookupRuntime().isIdle()));
        });
    }
    @Subscribe public void shutdown(ProxyShutdownEvent event) { release.countDown(); registration.close(); }
}
