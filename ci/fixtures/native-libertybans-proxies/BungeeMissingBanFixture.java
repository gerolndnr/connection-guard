// SPDX-License-Identifier: AGPL-3.0-or-later
package fixture;
import com.github.gerolndnr.connectionguard.api.v1.DecisionObservation;
import net.md_5.bungee.api.CommandSender;
import net.md_5.bungee.api.plugin.*;
public final class BungeeMissingBanFixture extends Plugin {
    private BanFixtureCore core;
    private volatile boolean live;
    @Override public void onEnable() {
        live=true;core=new BanFixtureCore(System.out::println, true, DecisionObservation.Platform.BUNGEE);
        getProxy().getPluginManager().registerCommand(this,new Command("fixture-ban") {
            @Override public void execute(CommandSender sender,String[] args) {
                if(sender!=getProxy().getConsole())return;
                if(!core.command(args))System.out.println("BAN_COMMAND unsupported=true");
            }
        });
        System.out.println("BUNGEE_FIXTURE_READY native=false");
    }
    @Override public void onDisable() {live=false;if(core!=null)core.close();}
}
