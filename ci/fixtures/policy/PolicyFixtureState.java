package fixture;
import com.github.gerolndnr.connectionguard.api.v1.*;
import com.github.gerolndnr.connectionguard.core.ConnectionGuard;
import com.github.gerolndnr.connectionguard.core.geo.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
/** Owned synthetic loopback fixture; never install on a public server. */
public final class PolicyFixtureState implements AutoCloseable {
  private ProviderRegistration provider;
  private ObserverRegistration observer;
  private AdmissionRegistration admission;
  private final AtomicInteger calls=new AtomicInteger(), geoCalls=new AtomicInteger(), actions=new AtomicInteger();
  private final AtomicInteger admissionCalls=new AtomicInteger();
  private volatile boolean positive=true;
  private volatile CompletableFuture<Optional<GeoResult>> held;
  private volatile CompletableFuture<AdmissionResponse> heldAdmission;
  public PolicyFixtureState() {
    provider=ConnectionGuardApi.registerProvider(new ProviderDescriptor("policy-fixture","1",String.join("",Collections.nCopies(64,"b")),true),ip->{
      if(!ip.equals("127.0.0.1"))throw new IllegalArgumentException("Unexpected fixture IP");
      calls.incrementAndGet(); return CompletableFuture.completedFuture(positive ? DetectionObservation.positive(DetectionMetadata.empty()) : DetectionObservation.negative(DetectionMetadata.empty()));
    });
    observer=ConnectionGuardApi.registerDecisionObserver("policy-observer",o->System.out.println("POLICY_OBS outcome="+o.getOutcome()+" reason="+o.getReason()+" vpn="+o.getVpnCheck()+" geo="+o.getGeoCheck()+" flags="+o.getFlags()));
    admission=ConnectionGuardApi.registerAdmissionHook("policy-admission",request->{
      if(!request.getIp().equals("127.0.0.1"))throw new IllegalArgumentException("Unexpected fixture IP");
      admissionCalls.incrementAndGet();
      CompletableFuture<AdmissionResponse> pending=heldAdmission;
      if(pending!=null){System.out.println("POLICY_ADMISSION_WAITING");return pending;}
      return CompletableFuture.completedFuture(AdmissionResponse.clear());
    });
  }
  public void command(String[] args) {
    if(args.length!=1)return;
    switch(args[0]) {
      case "positive": positive=true;break;
      case "negative": positive=false;break;
      case "geo": ConnectionGuard.setGeoProvider(new FixtureGeoProvider());break;
      case "hold": held=new CompletableFuture<>();break;
      case "release": if(held!=null)held.complete(Optional.of(geo()));held=null;break;
      case "hold-admission":
        if(heldAdmission!=null)throw new IllegalStateException("Already holding admission");
        heldAdmission=new CompletableFuture<>();break;
      case "release-admission":
        CompletableFuture<AdmissionResponse> pending=heldAdmission;heldAdmission=null;
        if(pending!=null)pending.complete(AdmissionResponse.clear());break;
      case "action": actions.incrementAndGet();break;
      case "status": break;
      default:return;
    }
    System.out.println("POLICY_STATUS calls="+calls.get()+" geoCalls="+geoCalls.get()+" actions="+actions.get()+" admissionCalls="+admissionCalls.get()+" runtimeIdle="+ConnectionGuard.getLookupRuntime().isIdle());
  }
  private GeoResult geo(){return new GeoResult("127.0.0.1","DE","Fixture city","Fixture ISP");}
  private final class FixtureGeoProvider implements GeoProvider {
    public CompletableFuture<Optional<GeoResult>> getGeoResult(String ip){
      if(!ip.equals("127.0.0.1"))throw new IllegalArgumentException("Unexpected fixture IP");geoCalls.incrementAndGet();
      CompletableFuture<Optional<GeoResult>> pending=held;
      if(pending!=null){System.out.println("POLICY_GEO_WAITING");return pending;}
      return CompletableFuture.completedFuture(Optional.of(geo()));
    }
  }
  public void close(){
    if(held!=null)held.complete(Optional.empty());
    if(heldAdmission!=null)heldAdmission.complete(AdmissionResponse.unknown(AdmissionResponse.Reason.CANCELLED));
    admission.close();provider.close();observer.close();
  }
}
