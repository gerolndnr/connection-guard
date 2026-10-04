package fixture;

import com.github.gerolndnr.connectionguard.api.v1.*;
import com.github.gerolndnr.connectionguard.core.ConnectionGuard;
import com.github.gerolndnr.connectionguard.core.webhook.CGWebHookHelper;
import com.github.gerolndnr.connectionguard.libs.okhttp3.OkHttpClient;
import com.google.inject.Inject;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.*;
import com.velocitypowered.api.proxy.*;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.nio.file.*;
import java.security.*;
import java.security.cert.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.net.ssl.*;
import net.kyori.adventure.text.Component;

/** Console-only synthetic probe. Never install on a production server. No trust-all TLS. */
public final class NativeWebhookFixture {
    private final ProxyServer proxy;
    private final AtomicInteger calls=new AtomicInteger(), events=new AtomicInteger();
    private final AtomicReference<DecisionObservation> latest=new AtomicReference<>();
    private volatile String mode="positive";
    private ProviderRegistration provider;private ObserverRegistration observer;
    @Inject public NativeWebhookFixture(ProxyServer proxy){this.proxy=proxy;}
    @Subscribe public void initialize(ProxyInitializeEvent event) {
        provider=ConnectionGuardApi.registerProvider(new ProviderDescriptor("webhook-fixture","1","1".repeat(64),true),ip->{
            if(!ip.equals("127.0.0.1"))throw new IllegalArgumentException("Only synthetic loopback players.");
            calls.incrementAndGet();String selected=mode;
            if(selected.equals("unknown"))return CompletableFuture.completedFuture(DetectionObservation.unknown(DetectionObservation.Reason.NO_EVIDENCE));
            boolean positive=selected.equals("positive");
            DetectionMetadata facts=DetectionMetadata.withExactRisk(Collections.singletonMap(DetectionMetadata.Type.VPN,positive),64500L,
                    "synthetic-provider-isp","synthetic-provider-operator","PT",new BigDecimal("79.999"),null);
            return CompletableFuture.completedFuture(new DetectionObservation(positive?DetectionObservation.Status.POSITIVE:DetectionObservation.Status.NEGATIVE,
                    DetectionObservation.Reason.NONE,facts,System.currentTimeMillis()+300000,"2".repeat(64)));
        });
        observer=ConnectionGuardApi.registerDecisionObserver("webhook-observer",fact->{latest.set(fact);events.incrementAndGet();});
        proxy.getCommandManager().register("fixture-webhook",(SimpleCommand)invocation->{
            if(!(invocation.source() instanceof ConsoleCommandSource))return;
            try {
                String[] args=invocation.arguments();
                if(args.length==2 && args[0].equals("mode")) {
                    if(!Arrays.asList("positive","negative","unknown").contains(args[1]))throw new IllegalArgumentException();mode=args[1];
                } else if(args.length==3 && args[0].equals("bind")) bind(Integer.parseInt(args[1]),args[2]);
                else if(!(args.length==1 && args[0].equals("status")))throw new IllegalArgumentException();
                DecisionObservation fact=latest.get();boolean cached=fact!=null && !fact.getSources().isEmpty() && fact.getSources().get(0).isFromCache();
                invocation.source().sendMessage(Component.text("WH_FIXTURE calls="+calls.get()+" events="+events.get()+" mode="+mode+
                        " outcome="+(fact==null?"NONE":fact.getOutcome())+" reason="+(fact==null?"NONE":fact.getReason())+" cached="+cached+
                        " lookupIdle="+ConnectionGuard.getLookupRuntime().isIdle()+" "+CGWebHookHelper.describe()));
            } catch(Exception rejected){invocation.source().sendMessage(Component.text("WH_FIXTURE rejected=true"));}
        });
    }
    private static void bind(int port,String expected)throws Exception {
        if(port<1 || port>65535 || !expected.matches("[0-9a-f]{64}"))throw new IllegalArgumentException();
        Path path=Paths.get("fixture-cert.pem");if(Files.isSymbolicLink(path) || Files.size(path)>8192)throw new IllegalArgumentException();
        X509Certificate certificate;
        try(java.io.InputStream input=Files.newInputStream(path)){certificate=(X509Certificate)CertificateFactory.getInstance("X.509").generateCertificate(input);}
        certificate.checkValidity();certificate.verify(certificate.getPublicKey());
        byte[] digest=MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded());StringBuilder hex=new StringBuilder();
        for(byte value:digest)hex.append(String.format("%02x",value & 255));if(!hex.toString().equals(expected))throw new IllegalArgumentException();
        KeyStore store=KeyStore.getInstance(KeyStore.getDefaultType());store.load(null,null);store.setCertificateEntry("owned-loopback",certificate);
        TrustManagerFactory factory=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());factory.init(store);
        X509TrustManager trust=Arrays.stream(factory.getTrustManagers()).filter(value->value instanceof X509TrustManager).map(value->(X509TrustManager)value).findFirst().orElseThrow();
        SSLContext context=SSLContext.getInstance("TLS");context.init(null,new TrustManager[]{trust},new SecureRandom());
        Field owner=CGWebHookHelper.class.getDeclaredField("dispatcher");owner.setAccessible(true);Object dispatcher=owner.get(null);
        Field client=dispatcher.getClass().getDeclaredField("client");client.setAccessible(true);OkHttpClient original=(OkHttpClient)client.get(dispatcher);
        if(original.callTimeoutMillis()!=2500 || original.followRedirects() || original.followSslRedirects() || original.retryOnConnectionFailure())throw new IllegalStateException();
        OkHttpClient selected=original.newBuilder().sslSocketFactory(context.getSocketFactory(),trust).addInterceptor(chain->{
            if(!chain.request().url().isHttps() || !chain.request().url().host().equals("127.0.0.1") || chain.request().url().port()!=port
                    || !chain.request().url().encodedPath().startsWith("/fixture/"))throw new java.io.IOException("Owned endpoint required.");
            return chain.proceed(chain.request());
        }).build();
        // Controlled internal-field fixture seam, original JAR untouched; hostname validation stays enabled.
        client.set(dispatcher,selected);
    }
    @Subscribe public void stop(ProxyShutdownEvent event){if(provider!=null)provider.close();if(observer!=null)observer.close();}
}
