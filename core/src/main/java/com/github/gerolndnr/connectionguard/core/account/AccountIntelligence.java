package com.github.gerolndnr.connectionguard.core.account;

import com.github.gerolndnr.connectionguard.core.ConnectionGuard;
import com.github.gerolndnr.connectionguard.core.lookup.FailureReason;
import com.github.gerolndnr.connectionguard.core.lookup.LookupException;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

/**
 * Account Intelligence, the third module next to VPN and Geo detection: after a login has been admitted, the account is
 * checked against public ban lists, and staff are told when a ban in force matches the selected categories. It never
 * denies, kicks, delays or changes a login decision, and runs on its own two workers, not on the lookup workers.
 * Only premium Java accounts are checked (version-4 UUID from an online-mode login): an offline-mode UUID is derived from
 * the name alone and is shared by everyone who picks that name.
 */
public final class AccountIntelligence {
    public static final String NOTIFY_PERMISSION = "connectionguard.notify.account";
    static final long NOTIFY_INTERVAL_MILLIS = TimeUnit.MINUTES.toMillis(10);
    private static final int QUEUE = 512, CACHE_MAX = 10000, NAMES_MAX = 2048;

    /** Platform delivery to online players holding {@code permission}; must not block. Text uses '&' colour codes. */
    public interface Notifier { void staff(String text, String permission); }

    private static volatile Runtime runtime;
    private static volatile Notifier notifier;
    private static final Map<String, UUID> RECENT = Collections.synchronizedMap(new LinkedHashMap<String, UUID>(64, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, UUID> eldest) { return size() > NAMES_MAX; }
    });

    private AccountIntelligence() { }

    public static void setNotifier(Notifier next) { notifier = next; }

    public static synchronized void configure(AccountSettings settings) {
        configure(settings, settings.enabled() ? new MizanAccountProvider(settings.mizanKey()) : null);
    }
    static synchronized void configure(AccountSettings settings, AccountProvider provider) {
        Runtime current = runtime;
        if (!settings.enabled() || provider == null) { runtime = null; if (current != null) current.close(); return; }
        // Same provider, key, budget and cache: keep queue, cache and counters; notification choices apply at once.
        if (current != null && current.signature.equals(settings.signature())) { current.settings = settings; return; }
        runtime = new Runtime(settings, provider);
        if (current != null) current.close();
    }
    public static synchronized void shutdown() {
        Runtime current = runtime; runtime = null;
        if (current != null) current.close();
        RECENT.clear();
    }

    public static boolean enabled() { return runtime != null; }

    /** A premium Java account: authenticated by Mojang (online mode or verified forwarding) and a version-4 UUID. */
    public static boolean checkable(UUID uuid, boolean authenticated) { return authenticated && uuid != null && uuid.version() == 4; }

    /** Called by the platform after the login was admitted. Returns at once; any warning arrives later. */
    public static void joined(UUID uuid, String name, boolean authenticated) {
        Runtime current = runtime;
        if (current == null || uuid == null) return;
        if (name != null && name.matches("[A-Za-z0-9_]{1,16}")) RECENT.put(name.toLowerCase(Locale.ROOT), uuid);
        if (!checkable(uuid, authenticated)) { current.notCheckable.incrementAndGet(); return; }
        current.check(uuid).thenAccept(record -> current.notify(name, record));
    }

    /** For /cg account: the cached or a new record. Fails with a {@link LookupException} reason when unavailable. */
    public static CompletableFuture<AccountRecord> lookup(UUID uuid) {
        Runtime current = runtime;
        if (current == null) { CompletableFuture<AccountRecord> off = new CompletableFuture<>(); off.completeExceptionally(new LookupException(FailureReason.CANCELLED)); return off; }
        return current.check(uuid);
    }

    /** UUID of a player who joined since the last restart, by name. */
    public static Optional<UUID> recent(String name) { return name == null ? Optional.empty() : Optional.ofNullable(RECENT.get(name.toLowerCase(Locale.ROOT))); }

    public static AccountSettings settings() { Runtime current = runtime; return current == null ? null : current.settings; }

    public static String describe() {
        Runtime current = runtime;
        if (current == null) return "Account Intelligence: off";
        AccountSettings s = current.settings;
        return "Account Intelligence: " + current.provider.id() + " (" + (s.keyed() ? "own key" : "no key") + ") budget=" + s.minuteBudget
                + "/min cache=" + s.cacheMinutes + "min notify=" + (s.notifyStaff ? "staff+console" : "console") + " categories=" + s.categories
                + " checks=" + current.checks.get() + " warnings=" + current.warnings.get() + " notCheckable=" + current.notCheckable.get()
                + " skipped=" + current.skipped.get() + " failures=" + current.failures.get()
                + (current.lastFailure == null ? "" : " lastFailure=" + current.lastFailure);
    }

    /** The staff line for a record, or null when the record does not warn under the current settings. */
    static String message(AccountSettings settings, String name, AccountRecord record) {
        if (!settings.warns(record)) return null;
        List<String> categories = new ArrayList<>();
        for (String category : record.categoriesInForce) if (settings.categories.contains(category) || settings.categories.contains(AccountSettings.ANY)) categories.add(category);
        List<String> lists = record.lists();
        String shown = lists.isEmpty() ? String.valueOf(record.listsInForce) : String.join(", ", lists.subList(0, Math.min(3, lists.size())))
                + (record.listsInForce > Math.min(3, lists.size()) ? " +" + (record.listsInForce - Math.min(3, lists.size())) : "");
        String player = name != null && name.matches("[A-Za-z0-9_]{1,16}") ? name : record.uuid.toString();
        return ConnectionGuard.getMessages().getString("messages.account-notify").replace("%NAME%", player)
                .replace("%CATEGORIES%", categories.isEmpty() ? "-" : String.join(", ", categories)).replace("%LISTS%", shown);
    }

    private static final class Runtime {
        final String signature;
        final AccountProvider provider;
        volatile AccountSettings settings;
        final ThreadPoolExecutor workers;
        final ConcurrentHashMap<UUID, AccountRecord> cache = new ConcurrentHashMap<>();
        final ConcurrentHashMap<UUID, CompletableFuture<AccountRecord>> inflight = new ConcurrentHashMap<>();
        final ConcurrentHashMap<UUID, Long> notified = new ConcurrentHashMap<>();
        final ArrayDeque<Long> sent = new ArrayDeque<>();
        final AtomicLong checks = new AtomicLong(), warnings = new AtomicLong(), notCheckable = new AtomicLong(), skipped = new AtomicLong(), failures = new AtomicLong();
        volatile FailureReason lastFailure;
        long pausedUntil;

        Runtime(AccountSettings settings, AccountProvider provider) {
            this.settings = settings; this.provider = provider; this.signature = settings.signature();
            workers = new ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(QUEUE), task -> {
                Thread thread = new Thread(task, "ConnectionGuard Account Intelligence"); thread.setDaemon(true); return thread;
            }, new ThreadPoolExecutor.AbortPolicy());
        }

        CompletableFuture<AccountRecord> check(UUID uuid) {
            AccountRecord cached = cache.get(uuid);
            long now = System.currentTimeMillis();
            if (cached != null && now - cached.checkedAt < TimeUnit.MINUTES.toMillis(settings.cacheMinutes)) return CompletableFuture.completedFuture(cached);
            CompletableFuture<AccountRecord> created = new CompletableFuture<>();
            CompletableFuture<AccountRecord> running = inflight.putIfAbsent(uuid, created);
            if (running != null) return running;
            created.whenComplete((value, error) -> inflight.remove(uuid, created));
            try {
                workers.execute(() -> {
                    try { created.complete(fetch(uuid)); }
                    catch (Throwable failure) { created.completeExceptionally(failure); }
                });
            } catch (RejectedExecutionException full) {
                // A join wave larger than the queue: these accounts stay unchecked until they join again.
                skipped.incrementAndGet();
                created.completeExceptionally(new LookupException(FailureReason.OVERLOADED));
            }
            return created;
        }

        private AccountRecord fetch(UUID uuid) throws InterruptedException {
            permit();
            checks.incrementAndGet();
            try {
                AccountRecord record = provider.check(uuid);
                if (cache.size() >= CACHE_MAX) prune();
                cache.put(uuid, record);
                return record;
            } catch (LookupException failure) {
                failures.incrementAndGet(); lastFailure = failure.getReason();
                if (failure.getReason() == FailureReason.RATE_LIMIT || failure.getReason() == FailureReason.BUDGET_EXHAUSTED)
                    synchronized (sent) { pausedUntil = Math.max(pausedUntil, System.currentTimeMillis() + Math.max(1000, failure.getRetryAfterMillis())); }
                throw failure;
            } catch (RuntimeException failure) {
                failures.incrementAndGet(); lastFailure = FailureReason.INVALID_RESPONSE;
                throw new LookupException(FailureReason.INVALID_RESPONSE);
            }
        }

        /** Waits until the minute budget and any rate-limit pause the provider announced allow one more request. */
        private void permit() throws InterruptedException {
            while (true) {
                long wait;
                synchronized (sent) {
                    long now = System.currentTimeMillis();
                    while (!sent.isEmpty() && now - sent.peekFirst() >= 60000) sent.pollFirst();
                    if (now >= pausedUntil && sent.size() < settings.minuteBudget) { sent.addLast(now); return; }
                    wait = Math.max(pausedUntil - now, sent.size() < settings.minuteBudget ? 0 : sent.peekFirst() + 60000 - now);
                }
                Thread.sleep(Math.max(50, Math.min(wait, 60000)));
            }
        }

        private void prune() {
            long horizon = System.currentTimeMillis() - TimeUnit.MINUTES.toMillis(settings.cacheMinutes);
            cache.values().removeIf(record -> record.checkedAt < horizon);
            if (cache.size() >= CACHE_MAX) cache.clear();
            notified.values().removeIf(at -> at < System.currentTimeMillis() - NOTIFY_INTERVAL_MILLIS);
        }

        void notify(String name, AccountRecord record) {
            AccountSettings current = settings;
            String text = message(current, name, record);
            if (text == null) return;
            long now = System.currentTimeMillis();
            Long last = notified.get(record.uuid);
            if (last != null && now - last < NOTIFY_INTERVAL_MILLIS) return;
            notified.put(record.uuid, now);
            warnings.incrementAndGet();
            Logger logger = ConnectionGuard.getLogger();
            if (logger != null) logger.info(text.replaceAll("&[0-9A-Fa-fK-Ok-oRr]", ""));
            Notifier target = notifier;
            if (current.notifyStaff && target != null) {
                try { target.staff(text, NOTIFY_PERMISSION); }
                catch (RuntimeException failure) { com.github.gerolndnr.connectionguard.core.cloud.PluginErrorReports.record(failure, com.github.gerolndnr.connectionguard.core.cloud.PluginErrorReports.Context.OTHER); }
            }
        }

        void close() { workers.shutdownNow(); }
    }
}
