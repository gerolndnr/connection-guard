package fixture;

import com.github.gerolndnr.connectionguard.api.v1.*;
import com.google.inject.Inject;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.proxy.ConsoleCommandSource;
import com.velocitypowered.api.proxy.ProxyServer;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import net.kyori.adventure.text.Component;

/** Synthetic console-only probe. Never install on production. */
public final class DecisionObserverFixture {
    private final ProxyServer proxy;
    private final AtomicInteger providerCalls = new AtomicInteger(), callbacks = new AtomicInteger();
    private volatile String providerMode = "positive", observerMode = "normal";
    private volatile CountDownLatch release = new CountDownLatch(1);
    private ProviderRegistration provider;
    private ObserverRegistration observer, old;
    @Inject public DecisionObserverFixture(ProxyServer proxy) { this.proxy = proxy; }
    @Subscribe public void initialize(ProxyInitializeEvent event) {
        if (ConnectionGuardApi.CONTRACT_VERSION != 1) throw new IllegalStateException("Unsupported contract");
        String fingerprint = String.join("", Collections.nCopies(64, "a"));
        provider = ConnectionGuardApi.registerProvider(new ProviderDescriptor("decision-fixture", "1", fingerprint, true), ip -> {
            if (!ip.equals("127.0.0.1")) throw new IllegalArgumentException("Unexpected synthetic address");
            providerCalls.incrementAndGet();
            if (providerMode.equals("unknown")) return CompletableFuture.completedFuture(DetectionObservation.unknown(DetectionObservation.Reason.AUTHENTICATION));
            boolean positive = providerMode.equals("positive");
            return CompletableFuture.completedFuture(positive ? DetectionObservation.positive(metadata(true)) : DetectionObservation.negative(metadata(false)));
        });
        register();
        proxy.getCommandManager().register("fixture-observer", (SimpleCommand) invocation -> {
            if (!(invocation.source() instanceof ConsoleCommandSource)) return;
            String[] args = invocation.arguments();
            if (args.length == 2 && args[0].equals("provider") && Arrays.asList("positive", "negative", "unknown").contains(args[1])) providerMode = args[1];
            else if (args.length == 2 && args[0].equals("observer") && Arrays.asList("normal", "fail", "block", "release").contains(args[1])) {
                if (args[1].equals("release")) { release.countDown(); observerMode = "normal"; }
                else { observerMode = args[1]; if (observerMode.equals("block")) release = new CountDownLatch(1); }
            } else if (args.length == 1 && args[0].equals("replace")) { observer.close(); old = observer; register(); }
            else if (args.length == 1 && args[0].equals("old-close")) { if (old != null) old.close(); }
            else if (args.length == 1 && args[0].equals("close")) observer.close();
            else if (!(args.length == 1 && args[0].equals("status"))) return;
            invocation.source().sendMessage(Component.text("OBSERVER_STATUS callbacks=" + callbacks.get() + " providerCalls=" + providerCalls.get()
                    + " registered=" + observer.isRegistered() + " provider=" + providerMode + " observer=" + observerMode));
        });
    }
    private void register() {
        observer = ConnectionGuardApi.registerDecisionObserver("observer-fixture", event -> {
            int count = callbacks.incrementAndGet(); String mode = observerMode; CountDownLatch held = release;
            StringJoiner sources = new StringJoiner(";");
            for (DecisionObservation.Source source : event.getSources()) sources.add(source.getId() + ":" + source.getObservation().getStatus()
                    + ":" + source.getObservation().getReason() + ":" + source.isFromCache());
            StringJoiner rules = new StringJoiner(";");
            for (DecisionObservation.Rule rule : event.getRules()) rules.add(rule.getId() + ":" + rule.getEvaluatedScope() + ":" + rule.getEffect()
                    + ":" + rule.getMatch() + ":" + rule.isSelected());
            System.out.println("OBSERVER_RECORD callbacks=" + count + " outcome=" + event.getOutcome() + " reason=" + event.getReason()
                    + " mode=" + event.getMode() + " phase=" + event.getPhase() + " trust=" + event.getIdentityTrust()
                    + " uuid=" + event.getTrustedUuid().isPresent() + " vpn=" + event.getVpnCheck() + " geo=" + event.getGeoCheck()
                    + " flags=" + event.getFlags().toString().replace(" ", "") + " sources=" + (sources.length() == 0 ? "none" : sources)
                    + " rules=" + (rules.length() == 0 ? "none" : rules) + " durationMs=" + event.getDurationMillis()
                    + " thread=" + Thread.currentThread().getName());
            if (mode.equals("block")) held.await();
            if (mode.equals("fail")) throw new IllegalStateException("Synthetic observer exception details must not be logged");
        });
    }
    private DetectionMetadata metadata(boolean positive) {
        return new DetectionMetadata(Collections.singletonMap(DetectionMetadata.Type.TOR, positive), 15169L, "Synthetic ISP", null, "JP", 80, null);
    }
    @Subscribe public void shutdown(ProxyShutdownEvent event) {
        release.countDown(); observer.close(); provider.close();
    }
}
