// SPDX-License-Identifier: AGPL-3.0-or-later
package fixture;
import com.github.gerolndnr.connectionguard.api.v1.*;
import com.github.gerolndnr.connectionguard.addons.libertybans.LibertyBansReader;
import java.net.*;import java.time.*;import java.util.*;import java.util.concurrent.*;import java.util.concurrent.atomic.*;
import org.bukkit.command.*;import org.bukkit.event.*;import org.bukkit.event.player.*;import org.bukkit.plugin.*;import org.bukkit.plugin.java.JavaPlugin;
import space.arim.libertybans.api.*;import space.arim.libertybans.api.punish.*;import space.arim.libertybans.api.scope.*;import space.arim.omnibus.OmnibusProvider;
/** Owned local fixture only. Writes finite synthetic bans using the actual public API.
 * Never install on production. Production reader contains no punishment writes. */
public final class CGNativeBanFixture extends JavaPlugin implements Listener {
    private static final UUID SUBJECT=UUID.fromString("11111111-2222-3333-4444-555555555555");
    private final AtomicInteger calls=new AtomicInteger();private volatile boolean positive;
    private ProviderRegistration provider;private ObserverRegistration observer;private volatile Punishment loopback;
    @Override public void onEnable() {
        provider=ConnectionGuardApi.registerProvider(new ProviderDescriptor("native-ban-fixture","1",String.join("",Collections.nCopies(64,"b")),true),ip->{calls.incrementAndGet();return CompletableFuture.completedFuture(positive?DetectionObservation.positive(DetectionMetadata.empty()):DetectionObservation.negative(DetectionMetadata.empty()));});
        observer=ConnectionGuardApi.registerDecisionObserver("native-ban-observer", o->{
            String checks=o.getAdmissionChecks().stream().map(v->v.getId()+":"+v.getResponse().getStatus()+":"+v.getResponse().getReason()).reduce((a,b)->a+","+b).orElse("none");
            getLogger().info("BAN_RECORD outcome="+o.getOutcome()+" reason="+o.getReason()+" identity="+o.getIdentityTrust()+" vpn="+o.getVpnCheck()+" admission="+checks+" calls="+calls.get());
        });
        getServer().getPluginManager().registerEvents(this,this);
    }
    @EventHandler public void join(PlayerJoinEvent event){getLogger().info("BAN_JOIN "+event.getPlayer().getName());}
    private LibertyBans api(){return OmnibusProvider.getOmnibus().getRegistry().getProvider(LibertyBans.class).orElseThrow();}
    private EnforcementOptions silent(EnforcementOptionsFactory factory){return factory.enforcementOptionsBuilder().enforcement(EnforcementOptions.Enforcement.NONE).broadcasting(EnforcementOptions.Broadcasting.NONE).build();}
    private CompletableFuture<Punishment> ban(String ip,PunishmentType type,Duration duration,ServerScope scope) {
        try { DraftPunishment draft=api().getDrafter().draftBuilder().type(type).victim(AddressVictim.of(InetAddress.getByName(ip))).operator(ConsoleOperator.INSTANCE).reason("Owned synthetic finite integration fixture").duration(duration).scope(scope).build();
            return draft.enactPunishment(silent(draft)).toCompletableFuture().thenApply(v->v.orElseThrow());
        }catch(Exception e){return CompletableFuture.failedFuture(e);}
    }
    @Override public boolean onCommand(CommandSender sender,Command command,String label,String[] args){
        if(!(sender instanceof ConsoleCommandSender))return true;
        try {
            if(Arrays.equals(args,new String[]{"cancel-runtime"})) {com.github.gerolndnr.connectionguard.core.ConnectionGuard.getLookupRuntime().close();getLogger().info("BAN_RUNTIME cancelled=true");}
            else if(Arrays.equals(args,new String[]{"ready"})) getLogger().info("BAN_READY api="+(api()!=null)+" applicableScopes="+api().getScopeManager().scopesApplicableToCurrentServer().size());
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
                .thenAccept(v->getLogger().info("BAN_PREPARED finite=true revoked=true subjectSynthetic=true"))
                .exceptionally(e->{getLogger().warning("BAN_PREPARED failed=true");return null;});
            } else if(args.length==2 && args[0].equals("probe")) {
                Map<String,String> addresses=Map.of("active","203.0.113.10","clear","203.0.113.20","uuid","203.0.113.20","foreign","203.0.113.30","expired","203.0.113.40","mute","203.0.113.50","revoked","203.0.113.60");
                String selected=args[1];if(!addresses.containsKey(selected))throw new IllegalArgumentException();
                Plugin nativePlugin=getServer().getPluginManager().getPlugin("LibertyBans");
                AdmissionRequest request=new AdmissionRequest(addresses.get(selected),selected.equals("uuid")?SUBJECT:null,selected.equals("uuid")?DecisionObservation.IdentityTrust.VERIFIED_FORWARDING:DecisionObservation.IdentityTrust.UNTRUSTED,DecisionObservation.Platform.BUKKIT,System.nanoTime()+TimeUnit.SECONDS.toNanos(5));
                new LibertyBansReader(nativePlugin.getClass().getClassLoader(),nativePlugin::isEnabled).check(request).thenAccept(value->getLogger().info("BAN_PROBE case="+selected+" status="+value.getStatus()+" reason="+value.getReason()+" finite="+(value.getValidUntil()>System.currentTimeMillis())))
                    .exceptionally(e->{getLogger().warning("BAN_PROBE failed=true");return null;});
            } else if(Arrays.equals(args,new String[]{"loopback-ban"})) {
                ban("127.0.0.1",PunishmentType.BAN,Duration.ofMinutes(20),api().getScopeManager().globalScope()).thenAccept(v->{loopback=v;getLogger().info("BAN_LOOPBACK active=true");}).exceptionally(e->{getLogger().warning("BAN_LOOPBACK failed=true");return null;});
            } else if(Arrays.equals(args,new String[]{"loopback-clear"})) {
                if(loopback==null)throw new IllegalStateException();Punishment previous=loopback;
                previous.undoPunishment(silent(previous)).thenAccept(v->getLogger().info("BAN_LOOPBACK removed="+v));
            } else if(args.length==2 && args[0].equals("provider")) {
                positive=args[1].equals("positive");getLogger().info("BAN_PROVIDER positive="+positive+" calls="+calls.get());
            } else if(Arrays.equals(args,new String[]{"status"})) getLogger().info("BAN_STATUS calls="+calls.get());
        } catch(RuntimeException e){getLogger().warning("BAN_COMMAND failed=true");}
        return true;
    }
    @Override public void onDisable(){if(provider!=null)provider.close();if(observer!=null)observer.close();}
}
