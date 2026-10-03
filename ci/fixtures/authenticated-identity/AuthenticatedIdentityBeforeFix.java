import com.github.gerolndnr.connectionguard.core.identity.ConnectionIdentity;
import java.net.*;import java.util.*;import java.util.concurrent.atomic.*;
public class AuthenticatedIdentityBeforeFix {
 public static void main(String[] args) {
  AtomicBoolean online=new AtomicBoolean(true),live=new AtomicBoolean(true);
  AtomicReference<UUID> currentId=new AtomicReference<>(UUID.fromString("dddddddd-1111-4222-8333-444444444444"));
  UUID captured=currentId.get();
  ConnectionIdentity proof=ConnectionIdentity.resolve(captured,"CGIdentity",new InetSocketAddress("127.0.0.1",21001),live::get,online.get(),false,false,false);
  if(!proof.isVerified())throw new AssertionError("Initial authenticated connection unexpectedly unavailable");
  online.set(false);currentId.set(UUID.fromString("aaaaaaaa-1111-4222-8333-444444444444"));
  System.out.println("modeChanged=true canonicalUuidChanged=true connected="+live.get()+" verifiedAfterChanges="+proof.isVerified());
  if(proof.isVerified())System.exit(3);
 }
}
