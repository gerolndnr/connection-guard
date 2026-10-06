package fixture;
import org.bukkit.Bukkit;
import org.bukkit.command.*;
import org.bukkit.event.*;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;
public final class PolicyBackendFixture extends JavaPlugin implements Listener {
  private PolicyFixtureState state;
  @Override public void onEnable(){state=new PolicyFixtureState();Bukkit.getPluginManager().registerEvents(this,this);System.out.println("POLICY_FIXTURE_READY");}
  @Override public boolean onCommand(CommandSender sender,Command command,String label,String[] args){if(sender instanceof ConsoleCommandSender)state.command(args);return true;}
  @EventHandler public void join(PlayerJoinEvent event){System.out.println("POLICY_JOIN "+event.getPlayer().getName());}
  @Override public void onDisable(){if(state!=null)state.close();}
}
