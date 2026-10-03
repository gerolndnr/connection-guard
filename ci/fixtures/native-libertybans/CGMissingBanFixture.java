// SPDX-License-Identifier: AGPL-3.0-or-later
package fixture;
import com.github.gerolndnr.connectionguard.api.v1.*;
import java.util.*;import java.util.concurrent.*;import java.util.concurrent.atomic.*;
import org.bukkit.command.*;import org.bukkit.event.*;import org.bukkit.event.player.*;import org.bukkit.plugin.java.JavaPlugin;
/** No native SDK imports or synthetic native provider; tests installed addon with SDK absent. */
public final class CGMissingBanFixture extends JavaPlugin implements Listener {
    private final AtomicInteger calls=new AtomicInteger();private volatile boolean positive=true;
    private ProviderRegistration provider;private ObserverRegistration observer;
    @Override public void onEnable(){
        provider=ConnectionGuardApi.registerProvider(new ProviderDescriptor("native-ban-fixture","1",String.join("",Collections.nCopies(64,"b")),true),ip->{calls.incrementAndGet();return CompletableFuture.completedFuture(positive?DetectionObservation.positive(DetectionMetadata.empty()):DetectionObservation.negative(DetectionMetadata.empty()));});
        observer=ConnectionGuardApi.registerDecisionObserver("native-ban-observer",o->{String checks=o.getAdmissionChecks().stream().map(v->v.getId()+":"+v.getResponse().getStatus()+":"+v.getResponse().getReason()).reduce((a,b)->a+","+b).orElse("none");getLogger().info("BAN_RECORD outcome="+o.getOutcome()+" reason="+o.getReason()+" identity="+o.getIdentityTrust()+" vpn="+o.getVpnCheck()+" admission="+checks+" calls="+calls.get());});
        getServer().getPluginManager().registerEvents(this,this);
    }
    @EventHandler public void join(PlayerJoinEvent e){getLogger().info("BAN_JOIN "+e.getPlayer().getName());}
    @Override public boolean onCommand(CommandSender sender,Command command,String label,String[] args){if(!(sender instanceof ConsoleCommandSender))return true;if(Arrays.equals(args,new String[]{"cancel-runtime"})){com.github.gerolndnr.connectionguard.core.ConnectionGuard.getLookupRuntime().close();getLogger().info("BAN_RUNTIME cancelled=true");}else if(args.length==2 && args[0].equals("provider")){positive=args[1].equals("positive");getLogger().info("BAN_PROVIDER positive="+positive+" calls="+calls.get());}return true;}
    @Override public void onDisable(){if(provider!=null)provider.close();if(observer!=null)observer.close();}
}
