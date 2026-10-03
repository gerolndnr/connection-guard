// SPDX-License-Identifier: AGPL-3.0-or-later
package fixture;
import com.github.gerolndnr.connectionguard.api.v1.*;
import com.github.gerolndnr.connectionguard.core.ConnectionGuard;
import com.github.gerolndnr.connectionguard.core.extensions.AdmissionHooks;
import java.net.*;import java.time.*;import java.util.*;import java.util.concurrent.*;import java.util.function.*;
import space.arim.libertybans.api.*;import space.arim.libertybans.api.punish.*;import space.arim.libertybans.api.scope.*;import space.arim.omnibus.OmnibusProvider;
/** Actual public native API. Finite writes only in private owned HSQL fixtures.
 * Never install on production; the production addon is read-only. */
public final class NativeBanSupport {
    private static final UUID SUBJECT=UUID.fromString("11111111-2222-3333-4444-555555555555");
    private final Consumer<String> log;
    private final DecisionObservation.Platform platform;
    private volatile Punishment loopback;
    public NativeBanSupport(Consumer<String> log, DecisionObservation.Platform platform) {
        this.log=log;this.platform=platform;
    }
    private LibertyBans api(){return OmnibusProvider.getOmnibus().getRegistry().getProvider(LibertyBans.class).orElseThrow();}
    private EnforcementOptions silent(EnforcementOptionsFactory factory){return factory.enforcementOptionsBuilder().enforcement(EnforcementOptions.Enforcement.NONE).broadcasting(EnforcementOptions.Broadcasting.NONE).build();}
    private CompletableFuture<Punishment> ban(String ip,PunishmentType type,Duration duration,ServerScope scope) {
        try { DraftPunishment draft=api().getDrafter().draftBuilder().type(type).victim(AddressVictim.of(InetAddress.getByName(ip))).operator(ConsoleOperator.INSTANCE).reason("Owned synthetic finite integration fixture").duration(duration).scope(scope).build();
            return draft.enactPunishment(silent(draft)).toCompletableFuture().thenApply(v->v.orElseThrow());
        }catch(Exception e){return CompletableFuture.failedFuture(e);}
    }
    public void command(String[] args){
        try {
            if(Arrays.equals(args,new String[]{"ready"})) {
                try {log.accept("BAN_READY api="+(api()!=null)+" applicableScopes="+api().getScopeManager().scopesApplicableToCurrentServer().size());}
                catch(RuntimeException unavailable) {log.accept("BAN_READY api=false");}
            }
            else if(Arrays.equals(args,new String[]{"prepare"})) {
                ServerScope global=api().getScopeManager().globalScope(), foreign=api().getScopeManager().specificScope("cg-different-owned-fixture");
                if(api().getScopeManager().scopesApplicableToCurrentServer().contains(foreign))throw new IllegalStateException();
                ban("203.0.113.10",PunishmentType.BAN,Duration.ofMinutes(20),global)
                .thenCompose(v->ban("203.0.113.30",PunishmentType.BAN,Duration.ofMinutes(20),foreign))
                .thenCompose(v->ban("203.0.113.40",PunishmentType.BAN,Duration.ofSeconds(1),global))
                .thenCompose(v->ban("203.0.113.50",PunishmentType.MUTE,Duration.ofMinutes(20),global))
                .thenCompose(v->ban("203.0.113.60",PunishmentType.BAN,Duration.ofMinutes(20),global))
                .thenCompose(v->v.undoPunishment(silent(v)).toCompletableFuture())
                .thenCompose(v->{DraftPunishment draft=api().getDrafter().draftBuilder().type(PunishmentType.BAN).victim(PlayerVictim.of(SUBJECT)).operator(ConsoleOperator.INSTANCE).reason("Synthetic UUID contract input, not an authenticated account").duration(Duration.ofMinutes(20)).scope(global).build();return draft.enactPunishment(silent(draft)).toCompletableFuture();})
                .thenAccept(v->log.accept("BAN_PREPARED finite=true revoked=true subjectSynthetic=true"))
                .exceptionally(e->{log.accept("BAN_PREPARED failed=true");return null;});
            } else if(args.length==2 && args[0].equals("probe")) {
                Map<String,String> addresses=Map.of("active","203.0.113.10","clear","203.0.113.20","uuid","203.0.113.20","foreign","203.0.113.30","expired","203.0.113.40","mute","203.0.113.50","revoked","203.0.113.60");
                String selected=args[1];if(!addresses.containsKey(selected))throw new IllegalArgumentException();
                AdmissionRequest request=new AdmissionRequest(addresses.get(selected),selected.equals("uuid")?SUBJECT:null,selected.equals("uuid")?DecisionObservation.IdentityTrust.VERIFIED_FORWARDING:DecisionObservation.IdentityTrust.UNTRUSTED,platform,System.nanoTime()+TimeUnit.SECONDS.toNanos(5));
                AdmissionHooks.snapshot().invoke(0,request,ConnectionGuard.getLookupRuntime()).thenAccept(observation->{AdmissionResponse value=observation.getResponse();log.accept("BAN_PROBE case="+selected+" status="+value.getStatus()+" reason="+value.getReason()+" finite="+(value.getValidUntil()>System.currentTimeMillis()));})
                    .exceptionally(e->{log.accept("BAN_PROBE failed=true");return null;});
            } else if(Arrays.equals(args,new String[]{"loopback-ban"})) {
                ban("127.0.0.1",PunishmentType.BAN,Duration.ofMinutes(20),api().getScopeManager().globalScope()).thenAccept(v->{loopback=v;log.accept("BAN_LOOPBACK active=true");}).exceptionally(e->{log.accept("BAN_LOOPBACK failed=true");return null;});
            } else if(Arrays.equals(args,new String[]{"loopback-clear"})) {
                if(loopback==null)throw new IllegalStateException();Punishment previous=loopback;
                previous.undoPunishment(silent(previous)).thenAccept(v->log.accept("BAN_LOOPBACK removed="+v));
            }
        } catch(RuntimeException e){log.accept("BAN_COMMAND failed=true");}
    }
}
