package fixture;
import com.google.inject.Inject;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.*;
import com.velocitypowered.api.proxy.*;
public final class PolicyVelocityFixture {
  private final ProxyServer proxy;private PolicyFixtureState state;
  @Inject public PolicyVelocityFixture(ProxyServer proxy){this.proxy=proxy;}
  @Subscribe public void initialize(ProxyInitializeEvent event){state=new PolicyFixtureState();proxy.getCommandManager().register("fixture-policy",(SimpleCommand)invocation->{if(invocation.source() instanceof ConsoleCommandSource)state.command(invocation.arguments());});System.out.println("POLICY_FIXTURE_READY");}
  @Subscribe public void stop(ProxyShutdownEvent event){if(state!=null)state.close();}
}
