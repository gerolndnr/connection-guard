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
    private static final com.github.gerolndnr.connectionguard.core.policy.DecisionLeases decisionLeases = new com.github.gerolndnr.connectionguard.core.policy.DecisionLeases();
    public static synchronized com.github.gerolndnr.connectionguard.core.policy.DecisionLeases.Lease acquireDecisionLease() { return decisionLeases.acquire(); }
    public static long activePolicyDecisions() { return decisionLeases.active(); }
    private static final com.github.gerolndnr.connectionguard.core.policy.PolicyShadow policyShadow = new com.github.gerolndnr.connectionguard.core.policy.PolicyShadow();
    private static final java.util.concurrent.atomic.AtomicLong policyEpoch = new java.util.concurrent.atomic.AtomicLong();
    private static void policyContextChanged() {
        policyEpoch.incrementAndGet();
        policyShadow.stop(com.github.gerolndnr.connectionguard.core.policy.PolicyShadow.State.BASE_CHANGED);
    }
    private static volatile AccessRuleStore rules;
    public static synchronized void initializeRules(java.nio.file.Path directory) {
        decisionLeases.requireIdle();
        try { rules = new AccessRuleStore(directory, ConnectionGuard.class, () -> settings, ConnectionGuard::validatePolicyPublication); }
        catch (java.io.IOException invalid) { throw new IllegalStateException("Access rule file invalid; correct it before enabling checks (values redacted)."); }
        policyContextChanged();
        com.github.gerolndnr.connectionguard.core.commands.PolicyCommands.configure(directory);
    }
    public static AccessRuleStore getRuleStore() { return rules; }
    public static Optional<AccessRule> accessRule(String ip, java.util.UUID uuid, boolean trusted, AccessRule.Scope scope) {
        return rules == null ? Optional.empty() : rules.match(ip, uuid, trusted, scope);
    }
    public static EvidencePolicy.Decision evidenceRule(String ip, java.util.UUID uuid, boolean trusted,
            AccessRule.Scope scope, VpnResult vpn, GeoLookup geo) {
        List<ProviderVote> sources = com.github.gerolndnr.connectionguard.core.policy.ConnectionPolicy.sources(vpn, geo, policyGeoSource());
        return EvidencePolicy.evaluate(rules == null ? Collections.emptyList() : rules.snapshot(),
                ip, uuid, trusted, scope, sources, System.currentTimeMillis());
    }
    public static synchronized String policyGeoSource() { return geoProvider == null ? null : "geo." + providerName(geoProvider); }
    public static synchronized com.github.gerolndnr.connectionguard.core.policy.PolicyReplay.Snapshot policySnapshot() {
        AccessRuleStore store = rules;
        return new com.github.gerolndnr.connectionguard.core.policy.PolicyReplay.Snapshot(getSettings(),
                store == null ? Collections.emptyList() : store.snapshot());
    }
    public static com.github.gerolndnr.connectionguard.core.policy.ConnectionPolicy.Evaluation evaluatePolicy(
            GuardSettings selected, String ip, java.util.UUID uuid, boolean trusted, boolean vpnExempt, boolean geoExempt,
            VpnResult vpn, GeoLookup geo, String selectedGeoSource, long asOf) {
        AccessRuleStore store = rules;
        List<AccessRule> selectedRules = store == null ? Collections.emptyList() : store.snapshot();
        com.github.gerolndnr.connectionguard.core.policy.ConnectionPolicy.Evaluation live = com.github.gerolndnr.connectionguard.core.policy.ConnectionPolicy.evaluate(selected,
                selectedRules, ip, uuid, trusted, vpnExempt, geoExempt,
                vpn, geo, selectedGeoSource, asOf);
        policyShadow.record(selected, selectedRules, policyEpoch.get(), ip, uuid, trusted, vpnExempt, geoExempt,
                vpn, geo, selectedGeoSource, asOf, live);
        return live;
    }
    public static synchronized com.github.gerolndnr.connectionguard.core.policy.PolicyShadow.View startPolicyShadow(
            com.github.gerolndnr.connectionguard.core.policy.PolicyReplay.Snapshot candidate, long durationMillis) {
        AccessRuleStore store = rules;
        return policyShadow.start(getSettings(), store == null ? Collections.emptyList() : store.snapshot(), policyEpoch.get(), candidate, durationMillis);
    }
    public static synchronized com.github.gerolndnr.connectionguard.core.policy.PolicyShadow.View policyShadowStatus() {
        AccessRuleStore store = rules;
        return policyShadow.view(getSettings(), store == null ? Collections.emptyList() : store.snapshot(), policyEpoch.get());
    }
    public static void stopPolicyShadow() { policyShadow.stop(com.github.gerolndnr.connectionguard.core.policy.PolicyShadow.State.STOPPED); }
    public static synchronized String policyActivationToken() {
        return com.github.gerolndnr.connectionguard.core.policy.PolicyReplay.digest(policySnapshot().fingerprint() + "|"
                + (rules == null ? "none" : rules.revision()) + "|" + policyEpoch.get() + "|" + requiredPositiveFlags
                + "|" + (activeDraft == null ? "none" : activeDraft.cacheNamespace));
    }
    private static void validatePolicyPublication(com.github.gerolndnr.connectionguard.core.policy.PolicyJournal journal) {
        decisionLeases.requireIdle();
        if (!lookupRuntime.isIdle()) throw new IllegalStateException("Wait for provider/cache workers before changing policy.");
        if (journal != null && journal.local && activeDraft != null && activeDraft.cloudManagesPolicy)
            throw new IllegalArgumentException("Local policy and dashboard decision settings conflict; reset the managed overlay before startup (values redacted).");
    }
    private static void validatePolicyWrite(String expected) {
        if (rules == null || !policyActivationToken().equals(expected)) throw new IllegalArgumentException("Policy base/conditions changed; review the current token again.");
        decisionLeases.requireIdle();
        if (!lookupRuntime.isIdle()) throw new IllegalStateException("Wait for provider/cache workers before changing policy.");
        if (activeDraft != null && activeDraft.cloudManagesPolicy)
            throw new IllegalArgumentException("Dashboard owns decision settings; reset its managed settings before choosing a local policy.");
    }
    public static synchronized String activatePolicy(com.github.gerolndnr.connectionguard.core.policy.PolicyReplay.Snapshot candidate,
            String candidateHash, String expected) throws java.io.IOException {
        validatePolicyWrite(expected);
        if (!candidate.fingerprint().equals(candidateHash)) throw new IllegalArgumentException("Candidate changed after review.");
        com.github.gerolndnr.connectionguard.core.policy.PolicyJournal next = rules.transition(candidate,
                com.github.gerolndnr.connectionguard.core.policy.PolicyJournal.Operation.ACTIVATE, true, System.currentTimeMillis());
        policyContextChanged(); return next.current().id;
    }
    public static synchronized String rollbackPolicy(String revision, String expected) throws java.io.IOException {
        validatePolicyWrite(expected);
        com.github.gerolndnr.connectionguard.core.policy.PolicyJournal current = rules.journal();
        if (current == null) throw new IllegalArgumentException("No policy history.");
        com.github.gerolndnr.connectionguard.core.policy.PolicyReplay.Snapshot selected = current.find(revision).policy;
        com.github.gerolndnr.connectionguard.core.policy.PolicyJournal next = rules.transition(selected,
                com.github.gerolndnr.connectionguard.core.policy.PolicyJournal.Operation.ROLLBACK, true, System.currentTimeMillis());
        policyContextChanged(); return next.current().id;
    }
    public static synchronized String releasePolicy(String expected) throws java.io.IOException {
        validatePolicyWrite(expected);
        if (!rules.locallyOwned()) throw new IllegalArgumentException("No local policy owns the settings.");
        com.github.gerolndnr.connectionguard.core.policy.PolicyJournal next = rules.transition(
                new com.github.gerolndnr.connectionguard.core.policy.PolicyReplay.Snapshot(settings, rules.snapshot()),
                com.github.gerolndnr.connectionguard.core.policy.PolicyJournal.Operation.RELEASE, false, System.currentTimeMillis());
        policyContextChanged(); return next.current().id;
    }
    public static boolean hasIdentityRules() {
        return rules != null && rules.snapshot().stream().anyMatch(rule -> rule.getType() == AccessRule.Target.UUID
                && (rule.getExpiresAt() == 0 || rule.getExpiresAt() > System.currentTimeMillis()));
    }
    private static String activeCacheSignature;
    private static volatile ProviderConfiguration activeDraft;
    private static final java.util.concurrent.atomic.AtomicLong nextHostingNotice = new java.util.concurrent.atomic.AtomicLong();
    private static boolean failover;
    private static volatile Map<String, String> selectedHealthIds = Collections.emptyMap();
    private static boolean geoDisabled;
    public static boolean isGeoDisabled() { return geoDisabled; }
    private static int externalAttempts = 3;
    private static volatile com.github.gerolndnr.connectionguard.core.local.TorExitList tor;
    public static String torStatus() { return tor == null ? "Tor local layer unavailable" : tor.describe(); }
    public static void startTorRefresh() { if (tor != null) tor.start(); }
    private static final UncheckedVpnAdmissions uncheckedVpnAdmissions = new UncheckedVpnAdmissions();
    public static UncheckedVpnAdmissions uncheckedVpnAdmissions() { return uncheckedVpnAdmissions; }
    public static void startCoverageReporting() {
        uncheckedVpnAdmissions.start(line -> { Logger output = logger; if (output != null) output.warning(line); });
    }
    public static ProviderConfiguration getActiveDraft() { return activeDraft; }
    private static final com.github.gerolndnr.connectionguard.core.messages.MessageCatalog INITIAL_MESSAGES = com.github.gerolndnr.connectionguard.core.messages.MessageCatalog.defaults("en");
    public static com.github.gerolndnr.connectionguard.core.messages.MessageCatalog getMessages() {
        ProviderConfiguration selected = activeDraft;
        return selected == null ? INITIAL_MESSAGES : selected.messages;
    }
    public static synchronized void applyProviders(ProviderConfiguration draft) {
        decisionLeases.requireIdle();
        if (rules != null) rules.validateConfigReload(draft.settings, draft.cloudManagesPolicy);
        com.github.gerolndnr.connectionguard.core.extensions.DecisionObservers.validateActivation(draft.observers);
        if (!lookupRuntime.isIdle()) throw new IllegalStateException("Wait for lookup workers and deadlines before reloading providers.");
        if (activeCacheSignature != null && !activeCacheSignature.equals(draft.cacheSignature)) {
            throw new IllegalArgumentException("Cache connection changes require a restart; active configuration preserved.");
        }
        com.github.gerolndnr.connectionguard.core.local.TorExitList nextTor = tor;
        if (nextTor == null && draft.dataPath != null) nextTor = new com.github.gerolndnr.connectionguard.core.local.TorExitList(draft.dataPath, logger);
        applySettings(draft.settings);
        tor = nextTor; failover = draft.failover; externalAttempts = draft.externalAttempts;
        clearProxyCheckHandoffs();
        vpnProviders = draft.providers;
        selectedHealthIds = Collections.unmodifiableMap(new java.util.HashMap<>(draft.healthIds));
        geoProvider = draft.geo; geoDisabled = draft.geo == null;
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
    public static synchronized GuardSettings getSettings() { return rules == null ? settings : rules.effective(settings); }
    public static synchronized void applySettings(GuardSettings next) {
        if (rules != null) rules.validateConfigReload(next, false);
        com.github.gerolndnr.connectionguard.core.extensions.AdmissionHooks.validateActivation();
        configureLookup(next.lookup);
        com.github.gerolndnr.connectionguard.core.extensions.AdmissionHooks.configure(next.admissionHooks);
        admission = new com.github.gerolndnr.connectionguard.core.admission.AdmissionController(next.admission);
        settings = next;
        if (rules != null) rules.configured(next);
        policyContextChanged();
        com.github.gerolndnr.connectionguard.core.webhook.CGWebHookHelper.configure(next.webhooks);
    }
    private static volatile com.github.gerolndnr.connectionguard.core.admission.AdmissionController admission =
            new com.github.gerolndnr.connectionguard.core.admission.AdmissionController(settings.admission);
    public static synchronized com.github.gerolndnr.connectionguard.core.admission.LoginAdmission admitLogin(String ip, boolean vpnExempt, boolean geoExempt) {
        return admitLogin(ip, vpnExempt, geoExempt, getSettings().observe);
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
    public static synchronized void shutdown() { com.github.gerolndnr.connectionguard.core.commands.MigrationCommands.shutdown(); com.github.gerolndnr.connectionguard.core.migration.MigrationDatabases.setLoader(null); clearProxyCheckHandoffs(); uncheckedVpnAdmissions.close(); stopPolicyShadow(); decisionLeases.reset(); rules = null; com.github.gerolndnr.connectionguard.core.cloud.CloudSync.shutdown(); if (tor != null) { tor.close(); tor = null; } lookupRuntime.close(); com.github.gerolndnr.connectionguard.core.commands.LocalDataCommands.shutdown(); com.github.gerolndnr.connectionguard.core.extensions.AdmissionHooks.closeAll(); com.github.gerolndnr.connectionguard.core.extensions.ExtensionRegistry.closeAll(); com.github.gerolndnr.connectionguard.core.extensions.DecisionObservers.shutdown(); }

    private static ArrayList<VpnProvider> vpnProviders;
    private static GeoProvider geoProvider;
    private static CacheProvider cacheProvider;
    private static Logger logger;
    private static int vpnCacheExpirationTime = 1440;
    private static int geoCacheExpirationTime = 1440;

    public static void initializeCache() {
        initializeCache(30, TimeUnit.SECONDS);
    }

    /** Clean stops wait for bounded persistence, never on the login path. */
    public static void closeCache() {
        if(cacheProvider==null)return;
        try { if(!Boolean.TRUE.equals(cacheProvider.disband().get(5,TimeUnit.SECONDS)))throw new IllegalStateException(); }
        catch(InterruptedException interrupted){Thread.currentThread().interrupt();}
        catch(Exception failure){if(logger!=null)logger.warning("Cache shutdown did not finish; recent cache facts may need another lookup after restart (details redacted).");}
    }

    static void initializeCache(long timeout, TimeUnit unit) {
        try {
            if (Boolean.TRUE.equals(cacheProvider.setup().get(timeout, unit))) {
                return;
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException | ExecutionException | TimeoutException failure) {
            com.github.gerolndnr.connectionguard.core.cloud.PluginErrorReports.record(failure, com.github.gerolndnr.connectionguard.core.cloud.PluginErrorReports.Context.CACHE);
            // Do not forward exception details that may contain cache credentials.
        }
        throw new IllegalStateException("Connection Guard could not initialize the configured cache; "
                + "check cache settings and server logs before enabling connection checks.");
    }

    public static synchronized CompletableFuture<VpnResult> getVpnResult(String address) {
        final String ipAddress = Exemptions.normalize(address);
        if (tor != null) { Optional<VpnResult> local = tor.lookup(ipAddress); if (local.isPresent()) return CompletableFuture.completedFuture(local.get()); }
        final boolean ordered = failover; final int attempts = externalAttempts;
        final List<VpnProvider> providers = vpnProviders == null ? Collections.emptyList() : new ArrayList<>(vpnProviders);
        final int threshold = requiredPositiveFlags;
        final long started = System.nanoTime();
        final LookupRuntime runtime = lookupRuntime;
        final LookupSettings limits = runtime.getSettings();
        final CacheProvider cache = cacheProvider;
        final AtomicReferenceArray<CompletedSource> sources = new AtomicReferenceArray<>(providers.size());
        Supplier<VpnResult> timedOut = () -> aggregate(ipAddress, providers, threshold, sources, started, FailureReason.TIMEOUT, Long.MAX_VALUE, ordered);
        return coordinator.query("vpn:" + ipAddress, () -> safeCacheVpn(cache, ipAddress, providers).thenCompose(cached -> {
            if (!runtime.isOpen() || elapsed(started) >= limits.deadlineMillis) return CompletableFuture.completedFuture(timedOut.get());
            if (cached.isPresent()) { cached.get().setFromCache(true); return CompletableFuture.completedFuture(cached.get()); }
            CompletableFuture<?> completed;
            if (ordered) completed = failoverChain(ipAddress, providers, sources, runtime, started, 0, 0, attempts);
            else {
                List<CompletableFuture<?>> jobs = new ArrayList<>();
                for (int i = 0; i < providers.size(); i++) jobs.add(vpnSource(ipAddress, providers.get(i), i, sources, runtime, started));
                completed = CompletableFuture.allOf(jobs.toArray(new CompletableFuture[0]));
            }
            return completed.thenApply(ignored -> {
                VpnResult result = aggregate(ipAddress, providers, threshold, sources, started, FailureReason.NO_PROVIDER, Long.MAX_VALUE, ordered);
                if (result.getStatus() != ProviderVote.Status.UNKNOWN) {
                    // Persisting is best effort; it must never extend the login deadline.
                    result.setCachedOn(System.currentTimeMillis());
                    try { cache.addVpnResult(result).exceptionally(error -> null); }
                    catch (RuntimeException failure) { /* A cache outage cannot turn a positive into a negative. */ }
                }
                return result;
            });
        }), timedOut, () -> unknownVpn(ipAddress, FailureReason.OVERLOADED),
                cutoff -> aggregate(ipAddress, providers, threshold, sources, started, FailureReason.TIMEOUT, cutoff, ordered));
    }

    private static CompletableFuture<Void> failoverChain(String ip, List<VpnProvider> providers, AtomicReferenceArray<CompletedSource> sources,
            LookupRuntime runtime, long started, int index, int sent, int maximum) {
        if (index >= providers.size() || sent >= maximum || elapsed(started) >= runtime.getSettings().deadlineMillis) return CompletableFuture.completedFuture(null);
        VpnProvider provider = providers.get(index);
        return vpnSource(ip, provider, index, sources, runtime, started).thenCompose(completed -> {
            if (completed.vote.isVoting() && completed.vote.getStatus() != ProviderVote.Status.UNKNOWN) return CompletableFuture.completedFuture(null);
            boolean transmitted = completed.attempted && !provider.isLocal();
            return failoverChain(ip, providers, sources, runtime, started, index + 1, sent + (transmitted ? 1 : 0), maximum);
        });
    }
    private static CompletableFuture<CompletedSource> vpnSource(String ipAddress, VpnProvider provider, int index,
            AtomicReferenceArray<CompletedSource> sources, LookupRuntime runtime, long started) {
        String name = providerId(provider, index);
        long remaining = runtime.getSettings().deadlineMillis - elapsed(started);
        sources.set(index, new CompletedSource(new ProviderVote(name, ProviderVote.Status.UNKNOWN, FailureReason.TIMEOUT, elapsed(started)), Optional.empty()));
        java.util.concurrent.atomic.AtomicBoolean attempted = new java.util.concurrent.atomic.AtomicBoolean();
        CompletableFuture<Optional<VpnResult>> job;
        if (provider instanceof com.github.gerolndnr.connectionguard.core.local.IntelVpnProvider
                || provider instanceof com.github.gerolndnr.connectionguard.core.local.LocalVpnProvider)
            job = localProviderCall(runtime, name, provider, ipAddress, remaining);
        else if (provider instanceof com.github.gerolndnr.connectionguard.core.vpn.ProxyCheckVpnProvider)
            job = ((com.github.gerolndnr.connectionguard.core.vpn.ProxyCheckVpnProvider) provider).getVpnResult(ipAddress, remaining, () -> attempted.set(true));
        else if (provider.isAvailable()) job = providerCall(runtime, name, () -> provider.getVpnResult(ipAddress), remaining, () -> attempted.set(true));
        else {
            VpnResult unavailable = new VpnResult(ipAddress, false); unavailable.setUnknown(FailureReason.NO_PROVIDER);
            job = CompletableFuture.completedFuture(Optional.of(unavailable));
        }
        return job.handle((answer, error) -> {
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
                    if (valid && status == ProviderVote.Status.NEGATIVE && Boolean.TRUE.equals(answer.get().getDetails().get(DetectionDetails.Type.HOSTING))) {
                        long now = System.currentTimeMillis(), next = nextHostingNotice.get();
                        if (logger != null && now >= next && nextHostingNotice.compareAndSet(next, now + 30000))
                            logger.info("Hosting-only evidence observed; no default denial. Review provider votes in /cg explain and linked dashboard events.");
                    }
                    CompletedSource completed = new CompletedSource(new ProviderVote(name, status, reason, elapsed(started), valid ? answer.get().getDetails() : DetectionDetails.empty(),
                            valid ? answer.get().getValidUntil() : 0, valid ? answer.get().getSourceVersion() : null, provider.isVoting()),
                            valid ? answer.get().getVpnProviderName() : Optional.empty(), attempted.get());
                    sources.set(index, completed);
                    return completed;
        });
    }

    /** Only final built-in immutable indexes bypass transport workers; arbitrary addons never do. */
    private static CompletableFuture<Optional<VpnResult>> localProviderCall(LookupRuntime runtime, String name,
            VpnProvider provider, String ip, long remaining) {
        ProviderHealth state = health.computeIfAbsent(quotaKey(name), key -> new ProviderHealth());
        FailureReason admission = !runtime.isOpen() ? FailureReason.CANCELLED
                : remaining <= 0 ? FailureReason.TIMEOUT : state.reserve(System.currentTimeMillis());
        if (admission != FailureReason.NONE) {
            CompletableFuture<Optional<VpnResult>> rejected = new CompletableFuture<>();
            rejected.completeExceptionally(new LookupException(admission)); return rejected;
        }
        try {
            return provider.getVpnResult(ip).whenComplete((answer, error) ->
                    state.record(error == null ? FailureReason.NONE : LookupException.reason(error), error, runtime.getSettings()));
        } catch (RuntimeException invalid) {
            state.record(LookupException.reason(invalid), invalid, runtime.getSettings());
            CompletableFuture<Optional<VpnResult>> rejected = new CompletableFuture<>();
            rejected.completeExceptionally(invalid); return rejected;
        }
    }

    private static final class CompletedSource {
        private final ProviderVote vote;
        private final Optional<String> operator;
        private final long atNanos = System.nanoTime();
        private final boolean attempted;
        private CompletedSource(ProviderVote vote, Optional<String> operator) { this(vote, operator, false); }
        private CompletedSource(ProviderVote vote, Optional<String> operator, boolean attempted) { this.vote = vote; this.operator = operator; this.attempted = attempted; }
    }
    private static VpnResult aggregate(String ip, List<VpnProvider> providers, int threshold,
            AtomicReferenceArray<CompletedSource> sources, long started, FailureReason missing, long notAfterNanos, boolean ordered) {
        List<ProviderVote> trace = new ArrayList<>();
        int positive = 0, complete = 0, voting = 0;
        Optional<String> operator = Optional.empty();
        for (int i = 0; i < providers.size(); i++) {
            CompletedSource source = sources.get(i);
            if (source != null && notAfterNanos != Long.MAX_VALUE && source.atNanos - notAfterNanos > 0) source = null;
            if (ordered && source == null) continue;
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
                : voting > 0 && (ordered ? complete > 0 : complete == voting) ? ProviderVote.Status.NEGATIVE : ProviderVote.Status.UNKNOWN);
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
        if (geoDisabled) return CompletableFuture.completedFuture(new GeoLookup(Optional.empty(), FailureReason.NONE, false, 0));
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
                long remaining = limits.deadlineMillis - elapsed(started);
                CompletableFuture<Optional<GeoResult>> lookup = provider instanceof com.github.gerolndnr.connectionguard.core.geo.ProxyCheckGeoProvider
                        && ((com.github.gerolndnr.connectionguard.core.geo.ProxyCheckGeoProvider) provider).sharesVpnQuery()
                        ? ((com.github.gerolndnr.connectionguard.core.geo.ProxyCheckGeoProvider) provider).getGeoResult(ipAddress, remaining)
                        : providerCall(runtime, providerName(provider), () -> provider.getGeoResult(ipAddress), remaining);
                return lookup.handle((answer, error) -> {
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

    public static CompletableFuture<Optional<com.google.gson.JsonObject>> proxyCheckResponse(
            com.github.gerolndnr.connectionguard.core.http.ProxyCheckClient client, String address, boolean geo, long remaining, Runnable attempted) {
        String ip = Exemptions.normalize(address);
        LookupRuntime runtime = getLookupRuntime(); LookupSettings limits = runtime.getSettings();
        if (remaining <= 0 || !runtime.isOpen()) {
            CompletableFuture<Optional<com.google.gson.JsonObject>> expired = new CompletableFuture<>();
            expired.completeExceptionally(new LookupException(runtime.isOpen() ? FailureReason.TIMEOUT : FailureReason.CANCELLED)); return expired;
        }
        return client.query(ip, geo, limits.maxInflight, limits.deadlineMillis,
                transmitted -> providerCall(runtime, "ProxyCheckVpnProvider", () -> client.fetch(ip), remaining, transmitted), attempted);
    }
    private static <T> CompletableFuture<T> providerCall(LookupRuntime runtime, String name, Supplier<CompletableFuture<T>> supplier, long remaining) {
        return providerCall(runtime, name, supplier, remaining, () -> {});
    }
    private static <T> CompletableFuture<T> providerCall(LookupRuntime runtime, String name, Supplier<CompletableFuture<T>> supplier, long remaining, Runnable attempted) {
        if (remaining <= 0 || !runtime.isOpen()) {
            CompletableFuture<T> expired = new CompletableFuture<>(); expired.completeExceptionally(new LookupException(runtime.isOpen() ? FailureReason.TIMEOUT : FailureReason.CANCELLED)); return expired;
        }
        ProviderHealth state = health.computeIfAbsent(quotaKey(name), key -> new ProviderHealth());
        FailureReason admission = state.reserve(System.currentTimeMillis());
        CompletableFuture<T> bounded = new CompletableFuture<>();
        if (admission != FailureReason.NONE) { alertProvider(name, state, admission); bounded.completeExceptionally(new LookupException(admission)); return bounded; }
        CompletableFuture<T> outcome = new CompletableFuture<>();
        ScheduledFuture<?> timeout = runtime.schedule(() -> outcome.completeExceptionally(new LookupException(FailureReason.TIMEOUT)), Math.max(0, remaining));
        outcome.whenComplete((answer, error) -> {
            timeout.cancel(false);
            state.record(error == null ? FailureReason.NONE : LookupException.reason(error), error, runtime.getSettings());
            if (error != null) alertProvider(name, state, LookupException.reason(error));
            if (error == null) bounded.complete(answer); else bounded.completeExceptionally(error);
        });
        try {
            runtime.submit(() -> {
                if (outcome.isDone()) throw new LookupException(FailureReason.CANCELLED);
                attempted.run(); return supplier.get();
            }).thenCompose(future -> future).whenComplete((answer, error) -> {
                if (error == null && answer instanceof Optional && !((Optional<?>) answer).isPresent()) outcome.completeExceptionally(new LookupException(FailureReason.INVALID_RESPONSE));
                else if (error == null) outcome.complete(answer);
                else {
                    Throwable cause = error instanceof java.util.concurrent.CompletionException && error.getCause() != null ? error.getCause() : error;
                    if (!(cause instanceof LookupException)) com.github.gerolndnr.connectionguard.core.cloud.PluginErrorReports.record(cause, com.github.gerolndnr.connectionguard.core.cloud.PluginErrorReports.Context.LOOKUP);
                    outcome.completeExceptionally(error);
                }
            });
        } catch (RuntimeException error) { outcome.completeExceptionally(error); }
        return bounded;
    }
    private static void alertProvider(String name, ProviderHealth state, FailureReason reason) {
        if (logger != null && state.claimAlert()) logger.warning("Connection Guard provider unavailable; protection degraded: provider=" + name + " reason=" + reason
                + "; " + state.describe() + ". Check /cg doctor and dashboard; configured failover/failure policy applies.");
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
        return selectedHealthIds.getOrDefault(name, name);
    }
    private static long elapsed(long started) { return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started); }

    public static synchronized void setRequiredPositiveFlags(int requiredPositiveFlags) {
        decisionLeases.requireIdle();
        ConnectionGuard.requiredPositiveFlags = requiredPositiveFlags;
        policyContextChanged();
    }

    private static void clearProxyCheckHandoffs() {
        if (vpnProviders != null) for (VpnProvider provider : vpnProviders)
            if (provider instanceof com.github.gerolndnr.connectionguard.core.vpn.ProxyCheckVpnProvider)
                ((com.github.gerolndnr.connectionguard.core.vpn.ProxyCheckVpnProvider) provider).client().clear();
    }
    public static synchronized void setVpnProviders(ArrayList<VpnProvider> vpnProviders) {
        decisionLeases.requireIdle();
        clearProxyCheckHandoffs();
        ConnectionGuard.vpnProviders = vpnProviders;
        health.clear(); selectedHealthIds = Collections.emptyMap(); failover = false; if (tor != null) { tor.close(); tor = null; }
        policyContextChanged();
    }

    public static synchronized void setFailover(boolean enabled, int attempts) {
        decisionLeases.requireIdle();
        if (enabled && requiredPositiveFlags != 1 || attempts < 1 || attempts > 16) throw new IllegalArgumentException("Invalid failover policy.");
        failover = enabled; externalAttempts = attempts;
        policyContextChanged();
    }

    public static synchronized void setGeoProvider(GeoProvider geoProvider) {
        decisionLeases.requireIdle();
        ConnectionGuard.geoProvider = geoProvider; geoDisabled = false;
        if (geoProvider != null && health.containsKey(quotaKey(geoProvider.getClass().getSimpleName()))) health.get(quotaKey(geoProvider.getClass().getSimpleName())).resetFailures();
        policyContextChanged();
    }

    public static synchronized void setCacheProvider(CacheProvider cacheProvider) {
        decisionLeases.requireIdle();
        ConnectionGuard.cacheProvider = cacheProvider;
        policyContextChanged();
    }

    public static void setLogger(Logger logger) {
        ConnectionGuard.logger = logger;
    }

    public static synchronized void setVpnCacheExpirationTime(int vpnCacheExpirationTime) {
        decisionLeases.requireIdle();
        ConnectionGuard.vpnCacheExpirationTime = vpnCacheExpirationTime;
        policyContextChanged();
    }

    public static synchronized void setGeoCacheExpirationTime(int geoCacheExpirationTime) {
        decisionLeases.requireIdle();
        ConnectionGuard.geoCacheExpirationTime = geoCacheExpirationTime;
        policyContextChanged();
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
