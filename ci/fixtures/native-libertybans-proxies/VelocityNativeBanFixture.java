// SPDX-License-Identifier: AGPL-3.0-or-later
package fixture;
import com.google.inject.Inject;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.*;
import com.velocitypowered.api.proxy.*;
import com.github.gerolndnr.connectionguard.api.v1.DecisionObservation;
public final class VelocityNativeBanFixture {
    private final ProxyServer proxy;
    private BanFixtureCore core;
    private volatile boolean live;
    private NativeBanSupport nativeSupport;
    @Inject public VelocityNativeBanFixture(ProxyServer proxy) {this.proxy=proxy;}
    @Subscribe public void initialize(ProxyInitializeEvent event) {
        live=true;core=new BanFixtureCore(System.out::println, false, DecisionObservation.Platform.VELOCITY);
        nativeSupport=new NativeBanSupport(System.out::println, DecisionObservation.Platform.VELOCITY);
        proxy.getCommandManager().register("fixture-ban", (SimpleCommand) invocation->{
            if(!(invocation.source() instanceof ConsoleCommandSource))return;
            if(!core.command(invocation.arguments()))nativeSupport.command(invocation.arguments());
        });
        System.out.println("VELOCITY_BAN_FIXTURE_READY native=true");
    }
    @Subscribe public void shutdown(ProxyShutdownEvent event) {live=false;if(core!=null)core.close();}
}
