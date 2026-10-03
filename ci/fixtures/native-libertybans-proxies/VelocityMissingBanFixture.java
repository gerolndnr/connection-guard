// SPDX-License-Identifier: AGPL-3.0-or-later
package fixture;
import com.github.gerolndnr.connectionguard.api.v1.DecisionObservation;
import com.google.inject.Inject;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.*;
import com.velocitypowered.api.proxy.*;
public final class VelocityMissingBanFixture {
    private final ProxyServer proxy;
    private BanFixtureCore core;
    private volatile boolean live;
    @Inject public VelocityMissingBanFixture(ProxyServer proxy) {this.proxy=proxy;}
    @Subscribe public void initialize(ProxyInitializeEvent event) {
        live=true;core=new BanFixtureCore(System.out::println, true, DecisionObservation.Platform.VELOCITY);
        proxy.getCommandManager().register("fixture-ban", (SimpleCommand) invocation->{
            if(!(invocation.source() instanceof ConsoleCommandSource))return;
            if(!core.command(invocation.arguments()))System.out.println("BAN_COMMAND unsupported=true");
        });
        System.out.println("VELOCITY_BAN_FIXTURE_READY native=false");
    }
    @Subscribe public void shutdown(ProxyShutdownEvent event) {live=false;if(core!=null)core.close();}
}
