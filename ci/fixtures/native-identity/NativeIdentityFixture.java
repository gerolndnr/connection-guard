package fixture;

import com.github.gerolndnr.connectionguard.api.v1.*;
import com.github.gerolndnr.connectionguard.core.identity.*;
import com.github.gerolndnr.connectionguard.core.luckperms.CGLuckPermsHelper;
import com.google.inject.Inject;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.event.*;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.event.proxy.*;
import com.velocitypowered.api.proxy.*;
import net.kyori.adventure.text.Component;
import net.luckperms.api.*;
import net.luckperms.api.node.Node;
import org.geysermc.floodgate.api.FloodgateApi;
import org.geysermc.floodgate.api.player.FloodgatePlayer;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Tests actual encrypted gateway delegation. No Xbox/Mojang authentication or native player insertion. */
public final class NativeIdentityFixture {
    private final ProxyServer proxy;
    private final AtomicInteger calls = new AtomicInteger();
    private volatile boolean hold;
    private volatile CompletableFuture<DetectionObservation> pending;
    private volatile FloodgateIdentity.Probe last;
    private volatile FloodgatePlayer lastRecord;
    private volatile Player lastPlayer;
    private ProviderRegistration provider;
    private ObserverRegistration observer;
    @Inject public NativeIdentityFixture(ProxyServer proxy) { this.proxy=proxy; }
    @Subscribe public void initialize(ProxyInitializeEvent event) {
        provider=ConnectionGuardApi.registerProvider(new ProviderDescriptor("native-identity-fixture","1",String.join("",Collections.nCopies(64,"e")),true),ip -> {
            if (!ip.equals("127.0.0.1")) throw new IllegalArgumentException("Unexpected owned fixture address");
            calls.incrementAndGet();
            if (hold) { pending=new CompletableFuture<>(); System.out.println("IDENTITY_WAITING"); return pending; }
            return CompletableFuture.completedFuture(DetectionObservation.positive(DetectionMetadata.empty()));
        });
        observer=ConnectionGuardApi.registerDecisionObserver("native-identity-observer",o ->
            System.out.println("IDENTITY_DECISION outcome="+o.getOutcome()+" reason="+o.getReason()+" identity="+o.getIdentityTrust()+" vpn="+o.getVpnCheck()));
        proxy.getCommandManager().register("fixture-identity",(SimpleCommand) invocation -> {
            if (!(invocation.source() instanceof ConsoleCommandSource)) return;
            String[] args=invocation.arguments();
            if (Arrays.equals(args,new String[]{"status"})) {
                invocation.source().sendMessage(Component.text("IDENTITY_STATUS native="+FloodgateIdentity.isAvailable()+" enabled="+com.github.gerolndnr.connectionguard.core.ConnectionGuard.getSettings().nativeFloodgateIdentity+" calls="+calls.get()));
            } else if (Arrays.equals(args,new String[]{"prepare"})) {
                LuckPerms api=LuckPermsProvider.get();
                api.getUserManager().loadUser(UUID.fromString("00000000-0000-0000-0000-000000000123")).thenCompose(u -> {
                    u.data().add(Node.builder("connectionguard.exemption.vpn").withContext("server","native-fixture").build());return api.getUserManager().saveUser(u);
                }).thenCompose(ignored -> api.getUserManager().loadUser(UUID.fromString("dddddddd-1111-4222-8333-444444444444"))).thenCompose(u -> {
                    u.data().add(Node.builder("connectionguard.exemption.vpn").withContext("server","native-fixture").build());return api.getUserManager().saveUser(u);
                }).thenAccept(ignored -> invocation.source().sendMessage(Component.text("IDENTITY_PREPARED")))
                    .exceptionally(error -> { invocation.source().sendMessage(Component.text("IDENTITY_PREPARE_FAILED"));return null; });
            } else if (Arrays.equals(args,new String[]{"hold"})) {
                hold=true;pending=null;invocation.source().sendMessage(Component.text("IDENTITY_HOLD"));
            } else if (Arrays.equals(args,new String[]{"release"})) {
                CompletableFuture<DetectionObservation> value=pending;hold=false;
                if (value == null) throw new IllegalStateException("No current owned provider wait");
                value.complete(DetectionObservation.negative(DetectionMetadata.empty()));
                invocation.source().sendMessage(Component.text("IDENTITY_RELEASED"));
            } else if (Arrays.equals(args,new String[]{"retire-native-record"})) {
                FloodgatePlayer record=lastRecord;
                if (record==null) throw new IllegalStateException("No current owned encrypted gateway record");
                try {
                    Object api=FloodgateApi.getInstance();
                    boolean removed=Boolean.TRUE.equals(api.getClass().getMethod("setPendingRemove",FloodgatePlayer.class).invoke(api,record));
                    invocation.source().sendMessage(Component.text("IDENTITY_NATIVE_RETIRE removed="+removed));
                } catch (ReflectiveOperationException error) { throw new IllegalStateException("Native retirement fixture failed",error); }
            } else if (Arrays.equals(args,new String[]{"retired"})) {
                FloodgatePlayer record=lastRecord;Player player=lastPlayer;
                boolean lookup=record!=null && FloodgateApi.getInstance().getPlayer(record.getCorrectUniqueId())==record;
                boolean active=record!=null && FloodgateApi.getInstance().getPlayers().stream().anyMatch(p -> p==record);
                boolean recapture=record!=null && FloodgateIdentity.capture(record.getCorrectUniqueId(),record.getCorrectUsername(),
                    player.getRemoteAddress(),()->true).isBound();
                invocation.source().sendMessage(Component.text("IDENTITY_RETIRED captured="+(last!=null&&last.isBound())+" current="+(last!=null&&last.isCurrent())
                    +" lookup="+lookup+" active="+active+" recapture="+recapture));
            }
        });
    }
    @Subscribe(order=PostOrder.FIRST) public void connectionProbe(LoginEvent event) {
        Player player=event.getPlayer();FloodgatePlayer record=FloodgateApi.getInstance().getPlayer(player.getUniqueId());
        FloodgateIdentity.Probe proof=FloodgateIdentity.capture(player.getUniqueId(),player.getUsername(),player.getRemoteAddress(),player::isActive);
        boolean wrongSocket=false,alias=false,canonical=false;
        if (record!=null) {
            InetSocketAddress remote=player.getRemoteAddress();
            int other=remote.getPort()==65535?65534:remote.getPort()+1;
            wrongSocket=FloodgateIdentity.capture(player.getUniqueId(),player.getUsername(),new InetSocketAddress(remote.getAddress(),other),player::isActive).isBound();
            alias=record.isLinked() && FloodgateIdentity.capture(record.getJavaUniqueId(),player.getUsername(),remote,player::isActive).isBound();
            canonical=record.getCorrectUniqueId().equals(player.getUniqueId()) && record.getCorrectUsername().equals(player.getUsername());
        }
        last=proof;lastRecord=record;lastPlayer=player;
        System.out.println("IDENTITY_CONNECTION native="+(record!=null)+" bound="+proof.isBound()+" current="+proof.isCurrent()
            +" playerOnline="+player.isOnlineMode()+" canonical="+canonical+" wrongSocket="+wrongSocket+" alias="+alias+" linked="+(record!=null&&record.isLinked()));
    }
    @Subscribe public void shutdown(ProxyShutdownEvent event) { if (pending!=null) pending.cancel(false);provider.close();observer.close(); }
}
