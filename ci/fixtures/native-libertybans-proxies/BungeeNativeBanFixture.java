// SPDX-License-Identifier: AGPL-3.0-or-later
package fixture;
import net.md_5.bungee.api.CommandSender;
import net.md_5.bungee.api.plugin.*;
import com.github.gerolndnr.connectionguard.api.v1.DecisionObservation;
public final class BungeeNativeBanFixture extends Plugin {
    private BanFixtureCore core;
    private volatile boolean live;
    private NativeBanSupport nativeSupport;
    @Override public void onEnable() {
        live=true;core=new BanFixtureCore(System.out::println, false, DecisionObservation.Platform.BUNGEE);
        nativeSupport=new NativeBanSupport(System.out::println, DecisionObservation.Platform.BUNGEE);
        getProxy().getPluginManager().registerCommand(this,new Command("fixture-ban") {
            @Override public void execute(CommandSender sender,String[] args) {
                if(sender!=getProxy().getConsole())return;
                if(!core.command(args))nativeSupport.command(args);
            }
        });
        System.out.println("BUNGEE_FIXTURE_READY native=true");
    }
    @Override public void onDisable() {live=false;if(core!=null)core.close();}
}
