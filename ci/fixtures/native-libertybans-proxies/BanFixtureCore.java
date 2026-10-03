// SPDX-License-Identifier: AGPL-3.0-or-later
package fixture;
import com.github.gerolndnr.connectionguard.api.v1.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
/** Owned proxy fixture only; contains no native SDK imports or substitute authority. */
public final class BanFixtureCore implements AutoCloseable {
    private final AtomicInteger calls = new AtomicInteger();
    private volatile boolean positive;
    private final Consumer<String> log;
    private final DecisionObservation.Platform platform;
    private final ProviderRegistration provider;
    private final ObserverRegistration observer;
    public BanFixtureCore(Consumer<String> log, boolean positive, DecisionObservation.Platform platform) {
        this.log = log; this.positive = positive; this.platform=platform;
        provider = ConnectionGuardApi.registerProvider(new ProviderDescriptor("native-ban-fixture", "1", String.join("", Collections.nCopies(64, "b")), true), ip -> {
            calls.incrementAndGet();
            return CompletableFuture.completedFuture(this.positive ? DetectionObservation.positive(DetectionMetadata.empty()) : DetectionObservation.negative(DetectionMetadata.empty()));
        });
        observer = ConnectionGuardApi.registerDecisionObserver("native-ban-observer", o -> {
            String checks = o.getAdmissionChecks().stream().map(v -> v.getId()+":"+v.getResponse().getStatus()+":"+v.getResponse().getReason()).reduce((a,b) -> a+","+b).orElse("none");
            log.accept("BAN_RECORD outcome="+o.getOutcome()+" reason="+o.getReason()+" identity="+o.getIdentityTrust()+" vpn="+o.getVpnCheck()+" admission="+checks+" calls="+calls.get());
        });
    }
    public boolean command(String[] args) {
        if (Arrays.equals(args, new String[]{"cancel-runtime"})) {
            com.github.gerolndnr.connectionguard.core.ConnectionGuard.getLookupRuntime().close();
            log.accept("BAN_RUNTIME cancelled=true");
        } else if (args.length == 2 && args[0].equals("pipeline") && Arrays.asList("enforce", "exempt", "observe").contains(args[1])) {
            // Synthetic contract input on the actual registered hook; not an actual client identity.
            String mode=args[1];long started=System.nanoTime();int before=calls.get();
            com.github.gerolndnr.connectionguard.core.lookup.LookupSettings limits=com.github.gerolndnr.connectionguard.core.ConnectionGuard.getLookupRuntime().getSettings();
            com.github.gerolndnr.connectionguard.core.lookup.LoginChecks.check("203.0.113.10", limits, started, mode.equals("observe"),
                com.github.gerolndnr.connectionguard.core.lookup.LoginChecks.Permission.known(mode.equals("exempt")),
                com.github.gerolndnr.connectionguard.core.lookup.LoginChecks.Permission.known(true),
                new AdmissionRequest("203.0.113.10",null,DecisionObservation.IdentityTrust.UNTRUSTED,platform,started+TimeUnit.MILLISECONDS.toNanos(limits.deadlineMillis)))
                .thenAccept(value->{com.github.gerolndnr.connectionguard.core.lookup.LoginChecks.External external=value.external();
                    log.accept("BAN_PIPELINE mode="+mode+" denied="+external.isDenied()+" refuse="+external.shouldRefuse(mode.equals("observe"))+" admission="+external.observations.get(0).getResponse().getStatus()+" providerDelta="+(calls.get()-before)+" calls="+calls.get());})
                .exceptionally(error->{log.accept("BAN_PIPELINE failed=true");return null;});
        } else if (args.length == 2 && args[0].equals("provider") && (args[1].equals("positive") || args[1].equals("negative"))) {
            positive = args[1].equals("positive"); log.accept("BAN_PROVIDER positive="+positive+" calls="+calls.get());
        } else if (Arrays.equals(args, new String[]{"status"})) log.accept("BAN_STATUS calls="+calls.get());
        else return false;
        return true;
    }
    @Override public void close() { provider.close(); observer.close(); }
}
