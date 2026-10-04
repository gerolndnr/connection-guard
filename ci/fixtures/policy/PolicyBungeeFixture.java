package fixture;
import net.md_5.bungee.api.CommandSender;
import net.md_5.bungee.api.plugin.*;
public final class PolicyBungeeFixture extends Plugin {
  private PolicyFixtureState state;
  @Override public void onEnable(){state=new PolicyFixtureState();getProxy().getPluginManager().registerCommand(this,new Command("fixture-policy"){
    @Override public void execute(CommandSender sender,String[] args){if(sender==getProxy().getConsole())state.command(args);}
  });System.out.println("BUNGEE_FIXTURE_READY");}
  @Override public void onDisable(){if(state!=null)state.close();}
}
