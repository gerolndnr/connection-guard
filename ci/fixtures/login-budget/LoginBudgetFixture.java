package fixture;

import com.github.gerolndnr.connectionguard.api.v1.*;
import com.google.inject.Inject;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.*;
import com.velocitypowered.api.proxy.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import net.kyori.adventure.text.Component;
import net.luckperms.api.*;
import net.luckperms.api.cacheddata.*;
import net.luckperms.api.context.ContextManager;
import net.luckperms.api.model.user.*;
import net.luckperms.api.query.QueryOptions;
import net.luckperms.api.util.Tristate;

/** A scripted API stand-in, NOT a LuckPerms installation or authenticated identity authority. */
public final class LoginBudgetFixture {
    private final ProxyServer proxy;
    private final ScheduledThreadPoolExecutor delays = new ScheduledThreadPoolExecutor(2, task -> {
        Thread thread = new Thread(task, "CG-fixture-delays"); thread.setDaemon(true); return thread;
    });
    private final AtomicInteger loads = new AtomicInteger(), cleanups = new AtomicInteger(), calls = new AtomicInteger(), callbacks = new AtomicInteger(), markers = new AtomicInteger();
    private volatile long permissionDelay = 20, sourceDelay = 20;
    private volatile boolean grant, positive = true;
    private volatile String permissionThread = "none";
    private ProviderRegistration provider;
    private ObserverRegistration observer;
    @Inject public LoginBudgetFixture(ProxyServer proxy) { this.proxy = proxy; }
    @Subscribe public void initialize(ProxyInitializeEvent event) throws Exception {
        QueryOptions options = mock(QueryOptions.class, (method, args) -> null);
        ContextManager contexts = mock(ContextManager.class, (method, args) -> method.equals("getStaticQueryOptions") ? options
                : method.equals("getQueryOptions") ? args[0] instanceof User ? Optional.of(options) : options : null);
        UserManager users = mock(UserManager.class, (method, args) -> {
            if (method.equals("getUser")) return null;
            if (method.equals("cleanupUser")) { cleanups.incrementAndGet(); return null; }
            if (!method.equals("loadUser")) return null;
            permissionThread = Thread.currentThread().getName(); loads.incrementAndGet();
            boolean capturedGrant = grant;
            CachedPermissionData permissions = mock(CachedPermissionData.class, (name, a) -> name.equals("checkPermission") ? capturedGrant ? Tristate.TRUE : Tristate.FALSE : null);
            CachedDataManager cached = mock(CachedDataManager.class, (name, a) -> name.equals("getPermissionData") ? permissions : null);
            User user = mock(User.class, (name, a) -> name.equals("getCachedData") ? cached : null);
            CompletableFuture<User> result = new CompletableFuture<>(); delays.schedule(() -> result.complete(user), permissionDelay, TimeUnit.MILLISECONDS); return result;
        });
        LuckPerms api = mock(LuckPerms.class, (method, args) -> method.equals("getUserManager") ? users : method.equals("getContextManager") ? contexts : null);
        Method register = LuckPermsProvider.class.getDeclaredMethod("register", LuckPerms.class); register.setAccessible(true); register.invoke(null, api);
        provider = ConnectionGuardApi.registerProvider(new ProviderDescriptor("budget-fixture", "1", String.join("", Collections.nCopies(64, "c")), true), ip -> {
            if (!ip.equals("127.0.0.1")) throw new IllegalArgumentException("Unexpected synthetic address");
            calls.incrementAndGet(); boolean capturedPositive = positive;
            CompletableFuture<DetectionObservation> result = new CompletableFuture<>();
            delays.schedule(() -> result.complete(capturedPositive ? DetectionObservation.positive(DetectionMetadata.empty())
                    : DetectionObservation.negative(DetectionMetadata.empty())), sourceDelay, TimeUnit.MILLISECONDS); return result;
        });
        observer = ConnectionGuardApi.registerDecisionObserver("budget-observer", observation -> {
            callbacks.incrementAndGet(); StringJoiner sources = new StringJoiner(";");
            for (DecisionObservation.Source source : observation.getSources()) sources.add(source.getId() + ":" + source.getObservation().getStatus() + ":" + source.getObservation().getReason());
            System.out.println("BUDGET_RECORD outcome=" + observation.getOutcome() + " reason=" + observation.getReason()
                    + " mode=" + observation.getMode() + " vpn=" + observation.getVpnCheck() + " geo=" + observation.getGeoCheck()
                    + " durationMs=" + observation.getDurationMillis() + " sources=" + sources);
        });
        proxy.getCommandManager().register("fixture-budget", (SimpleCommand) invocation -> {
            if (!(invocation.source() instanceof ConsoleCommandSource)) return;
            String[] args = invocation.arguments();
            if (args.length == 5 && args[0].equals("set")) {
                long p = Long.parseLong(args[1]), s = Long.parseLong(args[2]);
                if (p < 0 || p > 1000 || s < 0 || s > 1000) return;
                permissionDelay = p; sourceDelay = s; grant = Boolean.parseBoolean(args[3]); positive = Boolean.parseBoolean(args[4]);
            } else if (Arrays.equals(args, new String[]{"marker"})) markers.incrementAndGet();
            else if (!Arrays.equals(args, new String[]{"status"})) return;
            invocation.source().sendMessage(Component.text("BUDGET_STATUS loads=" + loads.get() + " cleanups=" + cleanups.get()
                    + " calls=" + calls.get() + " callbacks=" + callbacks.get() + " markers=" + markers.get() + " thread=" + permissionThread));
        });
    }
    @SuppressWarnings("unchecked") private static <T> T mock(Class<T> type, java.util.function.BiFunction<String, Object[], Object> action) {
        return (T) java.lang.reflect.Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (object, method, args) -> {
            if (method.getDeclaringClass() == Object.class) {
                if (method.getName().equals("hashCode")) return System.identityHashCode(object);
                if (method.getName().equals("equals")) return object == args[0];
                return "Synthetic permission API stand-in";
            }
            return action.apply(method.getName(), args);
        });
    }
    @Subscribe public void shutdown(ProxyShutdownEvent event) throws Exception {
        provider.close(); observer.close(); delays.shutdownNow();
        Method unregister = LuckPermsProvider.class.getDeclaredMethod("unregister"); unregister.setAccessible(true); unregister.invoke(null);
    }
}
