package com.github.gerolndnr.connectionguard.core;

import com.github.gerolndnr.connectionguard.core.rules.EvidencePolicy;
import com.github.gerolndnr.connectionguard.core.rules.AccessRule;
import com.github.gerolndnr.connectionguard.core.rules.AccessRuleStore;
import com.github.gerolndnr.connectionguard.core.cache.CacheProvider;
import com.github.gerolndnr.connectionguard.core.geo.GeoProvider;
import com.github.gerolndnr.connectionguard.core.geo.GeoResult;
import com.github.gerolndnr.connectionguard.core.vpn.VpnProvider;
import com.github.gerolndnr.connectionguard.core.vpn.VpnResult;

import com.github.gerolndnr.connectionguard.core.config.GuardSettings;
import com.github.gerolndnr.connectionguard.core.config.ProviderConfiguration;
import com.github.gerolndnr.connectionguard.core.identity.Exemptions;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.function.Supplier;
import com.github.gerolndnr.connectionguard.core.lookup.*;
import com.github.gerolndnr.connectionguard.core.http.ProviderHttp;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Logger;

public class ConnectionGuard {
    private static volatile AccessRuleStore rules;
    public static void initializeRules(java.nio.file.Path directory) {
        try { rules = new AccessRuleStore(directory); }
        catch (java.io.IOException invalid) { throw new IllegalStateException("Access rule file invalid; correct it before enabling checks (values redacted)."); }
    }
    public static AccessRuleStore getRuleStore() { return rules; }
    public static Optional<AccessRule> accessRule(String ip, java.util.UUID uuid, boolean trusted, AccessRule.Scope scope) {
        return rules == null ? Optional.empty() : rules.match(ip, uuid, trusted, scope);
    }
    public static EvidencePolicy.Decision evidenceRule(String ip, java.util.UUID uuid, boolean trusted,
            AccessRule.Scope scope, VpnResult vpn, GeoLookup geo) {
        List<ProviderVote> sources = new ArrayList<>(vpn.getVotes());
        if (geoProvider != null) {
            DetectionDetails details = DetectionDetails.empty();
            if (geo.getResult().isPresent()) {
                GeoResult result = geo.getResult().get();
                String country = result.getCountryName(), isp = result.getIspName();
                details = new DetectionDetails(null, result.getAsn(), "Unknown".equalsIgnoreCase(isp) ? null : isp, null,
                        country != null && country.matches("[A-Z]{2}") ? country : null, null, null);
            }
            if (geo.getResult().isPresent() || geo.getReason() != FailureReason.NONE) sources.add(new ProviderVote("geo." + providerName(geoProvider),
                    geo.getResult().isPresent() ? ProviderVote.Status.NEGATIVE : ProviderVote.Status.UNKNOWN,
                    geo.getResult().isPresent() ? FailureReason.NONE : geo.getReason(), geo.getDurationMillis(), details,
                    geo.getResult().isPresent() ? geo.getResult().get().getValidUntil() : 0,
                    geo.getResult().isPresent() ? geo.getResult().get().getSourceVersion() : null, false));
        }
        return EvidencePolicy.evaluate(rules == null ? Collections.emptyList() : rules.snapshot(),
                ip, uuid, trusted, scope, sources, System.currentTimeMillis());
    }
    public static boolean hasIdentityRules() {
        return rules != null && rules.snapshot().stream().anyMatch(rule -> rule.getType() == AccessRule.Target.UUID
                && (rule.getExpiresAt() == 0 || rule.getExpiresAt() > System.currentTimeMillis()));
    }
    private static String activeCacheSignature;
    private static volatile ProviderConfiguration activeDraft;
    public static ProviderConfiguration getActiveDraft() { return activeDraft; }
    private static final com.github.gerolndnr.connectionguard.core.messages.MessageCatalog INITIAL_MESSAGES = com.github.gerolndnr.connectionguard.core.messages.MessageCatalog.defaults("en");
    public static com.github.gerolndnr.connectionguard.core.messages.MessageCatalog getMessages() {
        ProviderConfiguration selected = activeDraft;
        return selected == null ? INITIAL_MESSAGES : selected.messages;
    }
    public static synchronized void applyProviders(ProviderConfiguration draft) {
        com.github.gerolndnr.connectionguard.core.extensions.DecisionObservers.validateActivation(draft.observers);
        if (!lookupRuntime.isIdle()) throw new IllegalStateException("Wait for lookup workers and deadlines before reloading providers.");
        if (activeCacheSignature != null && !activeCacheSignature.equals(draft.cacheSignature)) {
            throw new IllegalArgumentException("Cache connection changes require a restart; active configuration preserved.");
        }
        applySettings(draft.settings);
        vpnProviders = draft.providers;
        geoProvider = draft.geo;
        requiredPositiveFlags = draft.threshold;
        vpnCacheExpirationTime = draft.vpnTtl;
        geoCacheExpirationTime = draft.geoTtl;
        activeCacheSignature = draft.cacheSignature;
        if (cacheProvider != null) cacheProvider.setNamespace(draft.cacheNamespace);
        java.util.Set<String> selectedHealth = new java.util.HashSet<>();
        for (int i = 0; i < vpnProviders.size(); i++) selectedHealth.add(quotaKey(providerId(vpnProviders.get(i), i)));
        if (geoProvider != null) selectedHealth.add(quotaKey(providerName(geoProvider)));
        // Successful draft activation is quiescent. Retain budgets/counters for still-selected sources,
        // but never accumulate histories of arbitrary old extension/list IDs across reloads.
        health.keySet().retainAll(selectedHealth);
        for (String source : selectedHealth) health.computeIfAbsent(source, key -> new ProviderHealth());
        Map<String, Integer> days = new java.util.HashMap<>(), minutes = new java.util.HashMap<>();
        for (String id : draft.dayBudgets.keySet()) {
            String shared = quotaKey(id);
            days.merge(shared, draft.dayBudgets.get(id), ConnectionGuard::stricterBudget);
            minutes.merge(shared, draft.minuteBudgets.get(id), ConnectionGuard::stricterBudget);
        }
        for (String id : days.keySet()) setProviderBudget(id, days.get(id), minutes.get(id));
        activeDraft = draft;
        com.github.gerolndnr.connectionguard.core.extensions.DecisionObservers.configure(draft.observers);
        com.github.gerolndnr.connectionguard.core.commands.LocalDataCommands.configure(draft);
    }
    private static volatile GuardSettings settings = GuardSettings.defaults();
    public static GuardSettings getSettings() { return settings; }
    public static synchronized void applySettings(GuardSettings next) {
        com.github.gerolndnr.connectionguard.core.extensions.AdmissionHooks.validateActivation();
        configureLookup(next.lookup);
        com.github.gerolndnr.connectionguard.core.extensions.AdmissionHooks.configure(next.admissionHooks);
        admission = new com.github.gerolndnr.connectionguard.core.admission.AdmissionController(next.admission);
        settings = next;
        com.github.gerolndnr.connectionguard.core.webhook.CGWebHookHelper.configure(next.webhooks);
    }
    private static volatile com.github.gerolndnr.connectionguard.core.admission.AdmissionController admission =
            new com.github.gerolndnr.connectionguard.core.admission.AdmissionController(settings.admission);
    public static synchronized com.github.gerolndnr.connectionguard.core.admission.LoginAdmission admitLogin(String ip, boolean vpnExempt, boolean geoExempt) {
        return admitLogin(ip, vpnExempt, geoExempt, settings.observe);
    }
    public static synchronized com.github.gerolndnr.connectionguard.core.admission.LoginAdmission admitLogin(String ip, boolean vpnExempt, boolean geoExempt, boolean observe) {
        com.github.gerolndnr.connectionguard.core.admission.AdmissionController current = admission;
        com.github.gerolndnr.connectionguard.core.admission.LoginAdmission result = current.admit(ip, !vpnExempt || !geoExempt, observe);
        if (result.shouldAlert() && logger != null) logger.warning("Lookup admission skipped: " + result.getReason()
                + " retryMs=" + result.getRetryMillis() + "; " + current.describe());
        return result;
    }
    public static String admissionStats() { return admission.describe(); }
    private static int requiredPositiveFlags = 1;
    private static volatile LookupRuntime lookupRuntime = new LookupRuntime(LookupSettings.defaults());
    private static volatile LookupCoordinator coordinator = new LookupCoordinator(lookupRuntime);
    private static final Map<String, ProviderHealth> health = new ConcurrentHashMap<>();

    public static synchronized void configureLookup(LookupSettings settings) {
        if (coordinator.inflight() != 0) throw new IllegalStateException("Wait for active lookups before changing limits.");
        LookupRuntime previous = lookupRuntime;
        if (previous.isOpen() && previous.getSettings().equals(settings)) return;
        com.github.gerolndnr.connectionguard.core.extensions.AdmissionHooks.validateActivation();
        if (!previous.retireIfIdle()) throw new IllegalStateException("Wait for lookup workers and deadlines before changing limits.");
        lookupRuntime = new LookupRuntime(settings);
        coordinator = new LookupCoordinator(lookupRuntime);
        ProviderHttp.configure(settings);
    }
    public static LookupRuntime getLookupRuntime() { return lookupRuntime; }
    public static String lookupStats() { return coordinator.describe(); }
    public static Map<String, ProviderHealth> providerHealth() { return Collections.unmodifiableMap(health); }
    public static void setProviderBudget(String provider, int day, int minute) {
        health.computeIfAbsent(quotaKey(provider), key -> new ProviderHealth()).budgets(day, minute);
    }
    public static synchronized void shutdown() { lookupRuntime.close(); com.github.gerolndnr.connectionguard.core.commands.LocalDataCommands.shutdown(); com.github.gerolndnr.connectionguard.core.extensions.AdmissionHooks.closeAll(); com.github.gerolndnr.connectionguard.core.extensions.ExtensionRegistry.closeAll(); com.github.gerolndnr.connectionguard.core.extensions.DecisionObservers.shutdown(); }

    private static ArrayList<VpnProvider> vpnProviders;
    private static GeoProvider geoProvider;
    private static CacheProvider cacheProvider;
    private static Logger logger;
    private static int vpnCacheExpirationTime = 1440;
    private static int geoCacheExpirationTime = 1440;

    public static void initializeCache() {
        initializeCache(30, TimeUnit.SECONDS);
    }

    static void initializeCache(long timeout, TimeUnit unit) {
        try {
            if (Boolean.TRUE.equals(cacheProvider.setup().get(timeout, unit))) {
                return;
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException | ExecutionException | TimeoutException failure) {
            // Do not forward exception details that may contain cache credentials.
        }
        throw new IllegalStateException("Connection Guard could not initialize the configured cache; "
                + "check cache settings and server logs before enabling connection checks.");
    }

    public static synchronized CompletableFuture<VpnResult> getVpnResult(String address) {
        final String ipAddress = Exemptions.normalize(address);
        final List<VpnProvider> providers = vpnProviders == null ? Collections.emptyList() : new ArrayList<>(vpnProviders);
        final int threshold = requiredPositiveFlags;
        final long started = System.nanoTime();
        final LookupRuntime runtime = lookupRuntime;
        final LookupSettings limits = runtime.getSettings();
        final CacheProvider cache = cacheProvider;
        final AtomicReferenceArray<CompletedSource> sources = new AtomicReferenceArray<>(providers.size());
        Supplier<VpnResult> timedOut = () -> aggregate(ipAddress, providers, threshold, sources, started, FailureReason.TIMEOUT, Long.MAX_VALUE);
        return coordinator.query("vpn:" + ipAddress, () -> safeCacheVpn(cache, ipAddress, providers).thenCompose(cached -> {
            if (!runtime.isOpen() || elapsed(started) >= limits.deadlineMillis) return CompletableFuture.completedFuture(timedOut.get());
            if (cached.isPresent()) { cached.get().setFromCache(true); return CompletableFuture.completedFuture(cached.get()); }
            List<CompletableFuture<?>> jobs = new ArrayList<>();
            for (int i = 0; i < providers.size(); i++) {
                final int index = i;
                final VpnProvider provider = providers.get(i);
                final String name = providerId(provider, i);
                long remaining = limits.deadlineMillis - elapsed(started);
                CompletableFuture<Optional<VpnResult>> job;
                if (provider.isAvailable()) job = providerCall(runtime, name, () -> provider.getVpnResult(ipAddress), remaining);
                else {
                    VpnResult unavailable = new VpnResult(ipAddress, false); unavailable.setUnknown(FailureReason.NO_PROVIDER);
                    job = CompletableFuture.completedFuture(Optional.of(unavailable));
                }
                jobs.add(job.handle((answer, error) -> {
                    FailureReason reason = error == null ? FailureReason.NONE : LookupException.reason(error);
                    if (error == null && (answer == null || !answer.isPresent())) reason = FailureReason.INVALID_RESPONSE;
                    if (reason == FailureReason.NONE) {
                        try { if (!ipAddress.equals(Exemptions.normalize(answer.get().getIpAddress()))) throw new IllegalArgumentException(); answer.get().getDetails().validate(); }
                        catch (RuntimeException invalid) { reason = FailureReason.INVALID_RESPONSE; }
                    }
                    boolean valid = reason == FailureReason.NONE;
                    ProviderVote.Status status = valid ? answer.get().getStatus() : ProviderVote.Status.UNKNOWN;
                    if (valid) {
                        reason = answer.get().getSourceReason();
                        if (status == null || answer.get().isVpn() != (status == ProviderVote.Status.POSITIVE) || (status == ProviderVote.Status.UNKNOWN) != (reason != FailureReason.NONE)
                                || answer.get().getValidUntil() < 0 || answer.get().getSourceVersion() != null && !answer.get().getSourceVersion().matches("[0-9a-f]{64}")) {
                            status = ProviderVote.Status.UNKNOWN; reason = FailureReason.INVALID_RESPONSE; valid = false;
                        } else if (answer.get().getValidUntil() != 0 && System.currentTimeMillis() >= answer.get().getValidUntil()) {
                            status = ProviderVote.Status.UNKNOWN; reason = FailureReason.STALE_DATA; valid = false;
                        }
                    }
                    ProviderHealth sourceHealth = health.get(quotaKey(name));
                    if (reason != FailureReason.NONE && reason != FailureReason.NO_EVIDENCE && reason != FailureReason.STALE_DATA
                            && logger != null && sourceHealth != null && sourceHealth.claimAlert()) logger.warning("VPN provider response unavailable (" + reason + ").");
                    sources.set(index, new CompletedSource(new ProviderVote(name, status, reason, elapsed(started), valid ? answer.get().getDetails() : DetectionDetails.empty(),
                            valid ? answer.get().getValidUntil() : 0, valid ? answer.get().getSourceVersion() : null, provider.isVoting()),
                            valid ? answer.get().getVpnProviderName() : Optional.empty()));
                    return null;
                }));
            }
            return CompletableFuture.allOf(jobs.toArray(new CompletableFuture[0])).thenApply(ignored -> {
                VpnResult result = aggregate(ipAddress, providers, threshold, sources, started, FailureReason.NO_PROVIDER, Long.MAX_VALUE);
                if (result.getStatus() != ProviderVote.Status.UNKNOWN) {
                    // Persisting is best effort; it must never extend the login deadline.
                    result.setCachedOn(System.currentTimeMillis());
                    try { cache.addVpnResult(result).exceptionally(error -> null); }
                    catch (RuntimeException failure) { /* A cache outage cannot turn a positive into a negative. */ }
                }
                return result;
            });
        }), timedOut, () -> unknownVpn(ipAddress, FailureReason.OVERLOADED),
                cutoff -> aggregate(ipAddress, providers, threshold, sources, started, FailureReason.TIMEOUT, cutoff));
    }

    private static final class CompletedSource {
        private final ProviderVote vote;
        private final Optional<String> operator;
        private final long atNanos = System.nanoTime();
        private CompletedSource(ProviderVote vote, Optional<String> operator) { this.vote = vote; this.operator = operator; }
    }
    private static VpnResult aggregate(String ip, List<VpnProvider> providers, int threshold,
            AtomicReferenceArray<CompletedSource> sources, long started, FailureReason missing, long notAfterNanos) {
        List<ProviderVote> trace = new ArrayList<>();
        int positive = 0, complete = 0, voting = 0;
        Optional<String> operator = Optional.empty();
        for (int i = 0; i < providers.size(); i++) {
            CompletedSource source = sources.get(i);
            if (source != null && notAfterNanos != Long.MAX_VALUE && source.atNanos - notAfterNanos > 0) source = null;
            ProviderVote vote = source == null ? null : source.vote;
            if (vote == null) vote = new ProviderVote(providerId(providers.get(i), i),
                    ProviderVote.Status.UNKNOWN, missing, elapsed(started), DetectionDetails.empty(), 0, null, providers.get(i).isVoting());
            if (!vote.isFresh(System.currentTimeMillis())) vote = new ProviderVote(vote.getProvider(), ProviderVote.Status.UNKNOWN,
                    FailureReason.STALE_DATA, vote.getDurationMillis(), DetectionDetails.empty(), 0, vote.getSourceVersion(), vote.isVoting());
            trace.add(vote);
            if (providers.get(i).isVoting()) {
                voting++;
                if (vote.getStatus() != ProviderVote.Status.UNKNOWN) complete++;
                if (vote.getStatus() == ProviderVote.Status.POSITIVE) positive++;
            }
            if (source != null && vote.getReason() != FailureReason.STALE_DATA && source.operator.isPresent()) operator = source.operator;
        }
        VpnResult result = new VpnResult(ip, false, operator);
        result.setStatus(positive >= threshold ? ProviderVote.Status.POSITIVE
                : voting > 0 && complete == voting ? ProviderVote.Status.NEGATIVE : ProviderVote.Status.UNKNOWN);
        if (trace.isEmpty()) trace.add(new ProviderVote("none", ProviderVote.Status.UNKNOWN, FailureReason.NO_PROVIDER, elapsed(started)));
        result.setVotes(trace);
        result.setPositiveThreshold(threshold);
        long expiry = 0;
        for (ProviderVote vote : trace) if (vote.getValidUntil() > 0) expiry = expiry == 0 ? vote.getValidUntil() : Math.min(expiry, vote.getValidUntil());
        result.setValidUntil(expiry);
        return result;
    }

    /** Current in-flight facts only: no cache read, network work, publication or caller cancellation. */
    public static synchronized Optional<VpnResult> snapshotVpn(String address, long notAfterNanos) {
        return coordinator.<VpnResult>snapshot("vpn:" + Exemptions.normalize(address), notAfterNanos);
    }

    public static VpnResult unknownVpn(String ip, FailureReason reason) {
        VpnResult result = new VpnResult(ip, false);
        result.setStatus(ProviderVote.Status.UNKNOWN);
        result.setVotes(Collections.singletonList(new ProviderVote("lookup", ProviderVote.Status.UNKNOWN, reason, 0)));
        return result;
    }

    private static CompletableFuture<Optional<VpnResult>> safeCacheVpn(CacheProvider cache, String ip, List<VpnProvider> providers) {
        if (providers.stream().anyMatch(provider -> !provider.isAvailable())) return CompletableFuture.completedFuture(Optional.empty());
        try { return cache.getVpnResult(ip).exceptionally(error -> Optional.empty()).thenApply(answer -> answer.filter(result ->
                providers.stream().allMatch(VpnProvider::isAvailable) && (result.getValidUntil() == 0 || System.currentTimeMillis() < result.getValidUntil())
                        && result.getVotes().stream().allMatch(vote -> vote.isFresh(System.currentTimeMillis())))); }
        catch (RuntimeException failure) { return CompletableFuture.completedFuture(Optional.empty()); }
    }

    public static CompletableFuture<Optional<GeoResult>> getGeoResult(String ipAddress) {
        return getGeoLookup(ipAddress).thenApply(GeoLookup::getResult);
    }

    public static synchronized CompletableFuture<GeoLookup> getGeoLookup(String address) {
        final String ipAddress = Exemptions.normalize(address);
        final long started = System.nanoTime();
        final GeoProvider provider = geoProvider;
        final LookupRuntime runtime = lookupRuntime;
        final LookupSettings limits = runtime.getSettings();
        final CacheProvider store = cacheProvider;
        return coordinator.query("geo:" + ipAddress, () -> {
            CompletableFuture<Optional<GeoResult>> cache;
            try { cache = store.getGeoResult(ipAddress).exceptionally(error -> Optional.empty()); }
            catch (RuntimeException failure) { cache = CompletableFuture.completedFuture(Optional.empty()); }
            return cache.thenCompose(cached -> {
                if (!runtime.isOpen() || elapsed(started) >= limits.deadlineMillis) return CompletableFuture.completedFuture(new GeoLookup(Optional.empty(), FailureReason.TIMEOUT, false, elapsed(started)));
                if (cached.isPresent() && (cached.get().getValidUntil() == 0 || System.currentTimeMillis() < cached.get().getValidUntil())) return CompletableFuture.completedFuture(new GeoLookup(cached, FailureReason.NONE, true, elapsed(started)));
                if (provider == null) return CompletableFuture.completedFuture(new GeoLookup(Optional.empty(), FailureReason.NO_PROVIDER, false, elapsed(started)));
                return providerCall(runtime, providerName(provider), () -> provider.getGeoResult(ipAddress),
                        limits.deadlineMillis - elapsed(started)).handle((answer, error) -> {
                    FailureReason reason = error == null ? FailureReason.NONE : LookupException.reason(error);
                    if (answer == null || !answer.isPresent()) { answer = Optional.empty(); if (error == null) reason = FailureReason.INVALID_RESPONSE; }
                    if (answer.isPresent()) {
                        try { if (!ipAddress.equals(Exemptions.normalize(answer.get().getIpAddress()))) throw new IllegalArgumentException(); answer.get().validate(); }
                        catch (RuntimeException invalid) { answer = Optional.empty(); reason = FailureReason.INVALID_RESPONSE; }
                        if (answer.isPresent() && answer.get().getValidUntil() != 0 && System.currentTimeMillis() >= answer.get().getValidUntil()) { answer = Optional.empty(); reason = FailureReason.STALE_DATA; }
                    }
                    if (answer.isPresent()) {
                        answer.get().setCachedOn(System.currentTimeMillis());
                        try { store.addGeoResult(answer.get()).exceptionally(failure -> null); }
                        catch (RuntimeException failure) { /* best effort */ }
                    }
                    return new GeoLookup(answer, reason, false, elapsed(started));
                });
            });
        }, () -> new GeoLookup(Optional.empty(), FailureReason.TIMEOUT, false, elapsed(started)),
           () -> new GeoLookup(Optional.empty(), FailureReason.OVERLOADED, false, elapsed(started)));
    }

    private static <T> CompletableFuture<T> providerCall(LookupRuntime runtime, String name, Supplier<CompletableFuture<T>> supplier, long remaining) {
        if (remaining <= 0 || !runtime.isOpen()) {
            CompletableFuture<T> expired = new CompletableFuture<>(); expired.completeExceptionally(new LookupException(runtime.isOpen() ? FailureReason.TIMEOUT : FailureReason.CANCELLED)); return expired;
        }
        ProviderHealth state = health.computeIfAbsent(quotaKey(name), key -> new ProviderHealth());
        FailureReason admission = state.reserve(System.currentTimeMillis());
        CompletableFuture<T> bounded = new CompletableFuture<>();
        if (admission != FailureReason.NONE) { bounded.completeExceptionally(new LookupException(admission)); return bounded; }
        CompletableFuture<T> outcome = new CompletableFuture<>();
        ScheduledFuture<?> timeout = runtime.schedule(() -> outcome.completeExceptionally(new LookupException(FailureReason.TIMEOUT)), Math.max(0, remaining));
        outcome.whenComplete((answer, error) -> {
            timeout.cancel(false);
            state.record(error == null ? FailureReason.NONE : LookupException.reason(error), error, runtime.getSettings());
            if (error == null) bounded.complete(answer); else bounded.completeExceptionally(error);
        });
        try {
            runtime.submit(() -> {
                if (outcome.isDone()) throw new LookupException(FailureReason.CANCELLED);
                return supplier.get();
            }).thenCompose(future -> future).whenComplete((answer, error) -> {
                if (error == null && answer instanceof Optional && !((Optional<?>) answer).isPresent()) outcome.completeExceptionally(new LookupException(FailureReason.INVALID_RESPONSE));
                else if (error == null) outcome.complete(answer);
                else outcome.completeExceptionally(error);
            });
        } catch (RuntimeException error) { outcome.completeExceptionally(error); }
        return bounded;
    }
    private static int stricterBudget(int first, int second) { return first == 0 ? second : second == 0 ? first : Math.min(first, second); }
    public static String providerId(VpnProvider provider, int index) { return providerName(provider) + (provider.stableSourceId() ? "" : "#" + index); }
    private static String providerName(Object provider) {
        if (provider instanceof VpnProvider && ((VpnProvider) provider).sourceName() != null) {
            String source = ((VpnProvider) provider).sourceName();
            if (!source.matches("[a-zA-Z0-9_.-]{1,80}")) throw new IllegalArgumentException("Invalid public provider ID.");
            return source;
        }
        String name = provider.getClass().getSimpleName().replaceAll("\\$\\$Lambda\\$.*", "Lambda").replaceAll("[^a-zA-Z0-9_.-]", "_");
        if (name.isEmpty()) name = "AnonymousProvider";
        return name.substring(0, Math.min(80, name.length()));
    }
    private static String quotaKey(String name) {
        if (name.startsWith("IpApi")) return "IP-API";
        if (name.startsWith("ProxyCheck")) return "ProxyCheck";
        return name;
    }
    private static long elapsed(long started) { return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started); }

    public static void setRequiredPositiveFlags(int requiredPositiveFlags) {
        ConnectionGuard.requiredPositiveFlags = requiredPositiveFlags;
    }

    public static void setVpnProviders(ArrayList<VpnProvider> vpnProviders) {
        ConnectionGuard.vpnProviders = vpnProviders;
        health.clear();
    }

    public static void setGeoProvider(GeoProvider geoProvider) {
        ConnectionGuard.geoProvider = geoProvider;
        if (geoProvider != null && health.containsKey(quotaKey(geoProvider.getClass().getSimpleName()))) health.get(quotaKey(geoProvider.getClass().getSimpleName())).resetFailures();
    }

    public static void setCacheProvider(CacheProvider cacheProvider) {
        ConnectionGuard.cacheProvider = cacheProvider;
    }

    public static void setLogger(Logger logger) {
        ConnectionGuard.logger = logger;
    }

    public static void setVpnCacheExpirationTime(int vpnCacheExpirationTime) {
        ConnectionGuard.vpnCacheExpirationTime = vpnCacheExpirationTime;
    }

    public static void setGeoCacheExpirationTime(int geoCacheExpirationTime) {
        ConnectionGuard.geoCacheExpirationTime = geoCacheExpirationTime;
    }

    public static int getRequiredPositiveFlags() {
        return requiredPositiveFlags;
    }

    public static ArrayList<VpnProvider> getVpnProviders() {
        return vpnProviders;
    }

    public static GeoProvider getGeoProvider() {
        return geoProvider;
    }

    public static CacheProvider getCacheProvider() {
        return cacheProvider;
    }

    public static Logger getLogger() {
        return logger;
    }

    public static int getVpnCacheExpirationTime() {
        return vpnCacheExpirationTime;
    }

    public static int getGeoCacheExpirationTime() {
        return geoCacheExpirationTime;
    }
}
