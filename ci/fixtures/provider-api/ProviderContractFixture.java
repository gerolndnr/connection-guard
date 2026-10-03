package fixture;

import com.github.gerolndnr.connectionguard.api.v1.*;
import com.google.inject.Inject;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.proxy.ConsoleCommandSource;
import com.velocitypowered.api.proxy.ProxyServer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.kyori.adventure.text.Component;

/** Synthetic, console-only contract probe. Never install on a production server. */
public final class ProviderContractFixture {
    private final ProxyServer proxy;
    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicReference<CompletableFuture<DetectionObservation>> pending = new AtomicReference<>();
    private volatile String mode = "positive", thread = "none", id = "fixture";
    private volatile ProviderRegistration registration, old;
    private volatile int generation;
    @Inject public ProviderContractFixture(ProxyServer proxy) { this.proxy = proxy; }
    @Subscribe public void initialize(ProxyInitializeEvent event) {
        if (ConnectionGuardApi.CONTRACT_VERSION != 1) throw new IllegalStateException("Unsupported API");
        register("fixture");
        proxy.getCommandManager().register("fixture-api", (SimpleCommand) invocation -> {
            if (!(invocation.source() instanceof ConsoleCommandSource)) return;
            String[] args = invocation.arguments();
            try {
                if (args.length == 2 && args[0].equals("mode")) {
                    if (!java.util.Arrays.asList("positive", "negative", "unknown", "hang", "blocking", "null", "failure").contains(args[1])) throw new IllegalArgumentException();
                    mode = args[1];
                } else if (args.length == 2 && args[0].equals("register")) register(args[1]);
                else if (args.length == 1 && args[0].equals("close")) registration.close();
                else if (args.length == 1 && args[0].equals("old-close")) { if (old != null) old.close(); }
                else if (args.length == 1 && args[0].equals("complete")) {
                    CompletableFuture<DetectionObservation> future = pending.getAndSet(null);
                    if (future != null) future.complete(observation(true));
                } else if (!(args.length == 1 && args[0].equals("status"))) throw new IllegalArgumentException();
                invocation.source().sendMessage(Component.text("FIXTURE id=" + id + " registered=" + registration.isRegistered()
                    + " generation=" + generation + " mode=" + mode + " calls=" + calls.get() + " thread=" + thread + " pending=" + (pending.get() != null)));
            } catch (RuntimeException error) { invocation.source().sendMessage(Component.text("FIXTURE invalid command")); }
        });
    }
    private void register(String selectedId) {
        // Preserve closed handles to check that closing an old handle cannot remove a replacement.
        if (registration != null) { registration.close(); old = registration; }
        id = selectedId; generation++;
        registration = ConnectionGuardApi.registerProvider(new ProviderDescriptor(id, "fixture-" + generation, hash("configuration-" + generation), true), ip -> {
            if (!ip.equals("127.0.0.1")) throw new IllegalArgumentException("Unexpected synthetic address");
            calls.incrementAndGet(); thread = Thread.currentThread().getName();
            String selectedMode = mode;
            if (selectedMode.equals("blocking")) {
                try { Thread.sleep(1500); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                return CompletableFuture.completedFuture(observation(true));
            }
            if (selectedMode.equals("hang")) {
                CompletableFuture<DetectionObservation> future = new CompletableFuture<>();
                CompletableFuture<DetectionObservation> previous = pending.getAndSet(future);
                if (previous != null) previous.cancel(false);
                return future;
            }
            if (selectedMode.equals("null")) return null;
            if (selectedMode.equals("unknown")) return CompletableFuture.completedFuture(DetectionObservation.unknown(DetectionObservation.Reason.NO_EVIDENCE));
            if (selectedMode.equals("failure")) return CompletableFuture.completedFuture(DetectionObservation.unknown(DetectionObservation.Reason.AUTHENTICATION));
            return CompletableFuture.completedFuture(observation(!selectedMode.equals("negative")));
        });
    }
    private DetectionObservation observation(boolean positive) {
        DetectionMetadata metadata = new DetectionMetadata(Collections.singletonMap(DetectionMetadata.Type.TOR, positive),
            15169L, "Fixture ISP", "Synthetic operator", "JP", 80, 90);
        return new DetectionObservation(positive ? DetectionObservation.Status.POSITIVE : DetectionObservation.Status.NEGATIVE,
            DetectionObservation.Reason.NONE, metadata, 0, hash("source-" + generation));
    }
    private static String hash(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(); for (byte value : digest) hex.append(String.format("%02x", value & 255)); return hex.toString();
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    @Subscribe public void shutdown(ProxyShutdownEvent event) {
        if (registration != null) registration.close();
        CompletableFuture<DetectionObservation> future = pending.getAndSet(null);
        if (future != null) future.cancel(false);
    }
}
