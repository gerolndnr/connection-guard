package com.github.gerolndnr.connectionguard.core.account;

import com.github.gerolndnr.connectionguard.core.commands.AccountCommands;
import com.github.gerolndnr.connectionguard.core.lookup.FailureReason;
import com.github.gerolndnr.connectionguard.core.lookup.LookupException;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import okhttp3.HttpUrl;
import okhttp3.Request;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Synthetic accounts and bodies shaped like Mizan's 2026-11-09 answers; no public lookups. */
class AccountIntelligenceTest {
    private static final UUID PREMIUM = UUID.fromString("4f3c2b1a-0000-4000-8000-000000000001");
    private static final UUID OTHER = UUID.fromString("4f3c2b1a-0000-4000-8000-000000000002");
    private static final UUID OFFLINE = UUID.fromString("4f3c2b1a-0000-3000-8000-000000000003");

    @AfterEach void off() { AccountIntelligence.shutdown(); AccountIntelligence.setNotifier(null); }

    private static Object value(Map<String, Object> map, String path) {
        Object current = map;
        for (String key : path.split("\\.")) current = current instanceof Map ? ((Map<?, ?>) current).get(key) : null;
        return current;
    }
    private static AccountSettings settings(Object... pairs) {
        Map<String, Object> values = new HashMap<>();
        values.put("provider.account.mizan.enabled", true);
        for (int i = 0; i < pairs.length; i += 2) values.put((String) pairs[i], pairs[i + 1]);
        return new AccountSettings(values::get);
    }
    static String body(UUID uuid, int inForce, String categories, String bans) {
        String subject = uuid.toString().replace("-", "");
        return "{\"data\":{\"object\":\"player\",\"id\":\"minecraft:" + subject + "\",\"provider\":\"minecraft\",\"subject\":\"" + subject + "\","
                + "\"profile\":\"https://mizan.ac/players/minecraft/" + subject + "\",\"banned\":" + (inForce > 0) + ","
                + "\"bans\":{\"total\":" + (inForce + 1) + ",\"inForce\":" + inForce + ",\"ended\":1,\"lists\":2,\"listsInForce\":" + Math.min(inForce, 2) + ","
                + "\"lastAt\":null,\"categories\":[" + categories + "],\"categoriesInForce\":[" + categories + "],\"games\":[\"minecraft\"]},"
                + "\"record\":" + (bans == null ? "null" : "{\"bans\":[" + bans + "],\"names\":[],\"sanctions\":[],\"labels\":[],\"links\":[]}") + "}}";
    }
    static String ban(String list, String categories, boolean active, String reason) {
        return "{\"source\":{\"id\":\"list_x\",\"key\":\"x\",\"name\":\"" + list + "\",\"kind\":\"staff\"},\"issuer\":null,\"detector\":null,"
                + "\"categories\":[" + categories + "],\"reason\":" + (reason == null ? "null" : "\"" + reason + "\"") + ",\"reasonWithheld\":null,"
                + "\"scope\":\"server\",\"createdAt\":\"2026-11-28T04:42:00.000Z\",\"expiresAt\":\"2026-12-05T04:42:00.000Z\",\"endedAt\":null,\"active\":" + active + "}";
    }

    @Test void templateKeepsItOffAndAMissingSectionStaysOff() {
        Map<String, Object> yaml = new Yaml().load(getClass().getResourceAsStream("/config.yml"));
        AccountSettings template = new AccountSettings(k -> value(yaml, k));
        assertFalse(template.enabled());
        assertTrue(template.notifyStaff);
        assertEquals(new LinkedHashSet<>(AccountSettings.DEFAULT_CATEGORIES), template.categories);
        assertEquals(100, template.minuteBudget);
        assertEquals(60, template.cacheMinutes);
        AccountSettings old = new AccountSettings(k -> null);
        assertFalse(old.enabled());
        assertEquals(new LinkedHashSet<>(AccountSettings.DEFAULT_CATEGORIES), old.categories);
    }

    @Test void invalidSettingsAreRejectedWithoutEchoingTheKey() {
        assertThrows(IllegalArgumentException.class, () -> settings("provider.account.mizan.minute-budget", 0));
        assertThrows(IllegalArgumentException.class, () -> settings("provider.account.mizan.minute-budget", 301));
        assertThrows(IllegalArgumentException.class, () -> settings("behavior.account.notify-categories", Collections.emptyList()));
        assertThrows(IllegalArgumentException.class, () -> settings("behavior.account.notify-categories", Arrays.asList("Cheating")));
        assertThrows(IllegalArgumentException.class, () -> settings("provider.cache.expiration.account", 0));
        IllegalArgumentException key = assertThrows(IllegalArgumentException.class, () -> settings("provider.account.mizan.api-key", "mzn_secret value"));
        assertFalse(key.getMessage().contains("secret"));
        assertEquals(250, settings("provider.account.mizan.api-key", "mzn_example").minuteBudget);
    }

    @Test void onlySelectedCategoriesInForceWarn() {
        AccountRecord cheating = MizanAccountProvider.parse(PREMIUM, json(body(PREMIUM, 1, "\"cheating\"", ban("Example SMP", "\"cheating\"", true, "Killaura"))), 1);
        AccountRecord chat = MizanAccountProvider.parse(PREMIUM, json(body(PREMIUM, 1, "\"hate\"", ban("Example SMP", "\"hate\"", true, null))), 1);
        AccountRecord uncategorized = MizanAccountProvider.parse(PREMIUM, json(body(PREMIUM, 1, "", ban("Example SMP", "", true, "Compromised account"))), 1);
        AccountRecord clean = MizanAccountProvider.parse(PREMIUM, json(body(PREMIUM, 0, "", null)), 1);
        AccountSettings defaults = settings();
        assertTrue(defaults.warns(cheating));
        assertFalse(defaults.warns(chat));
        assertFalse(defaults.warns(uncategorized));
        assertFalse(defaults.warns(clean));
        AccountSettings any = settings("behavior.account.notify-categories", Collections.singletonList("*"));
        assertTrue(any.warns(chat)); assertTrue(any.warns(uncategorized)); assertFalse(any.warns(clean));
        String line = AccountIntelligence.message(defaults, "Steve_1", cheating);
        assertTrue(line.contains("Steve_1") && line.contains("cheating") && line.contains("Example SMP"), line);
    }

    @Test void parserKeepsBansInForceOnlyAndRejectsAnotherAccount() {
        String bans = ban("Old SMP", "\"cheating\"", false, "old") + "," + ban("Example §cSMP&4", "\"cheating\"", true, "Fly\nhack");
        AccountRecord record = MizanAccountProvider.parse(PREMIUM, json(body(PREMIUM, 1, "\"cheating\"", bans)), 7);
        assertEquals(1, record.bansInForce);
        assertEquals(1, record.activeBans.size());
        AccountRecord.Ban only = record.activeBans.get(0);
        assertEquals("Example cSMP& 4", only.list);
        assertEquals("Flyhack", only.reason);
        assertEquals(Collections.singleton("cheating"), only.categories);
        assertEquals(java.time.Instant.parse("2026-12-05T04:42:00Z").toEpochMilli(), only.expiresAt);
        assertTrue(record.profileUrl.startsWith("https://mizan.ac/players/minecraft/"));
        assertEquals(7, record.checkedAt);
        assertThrows(LookupException.class, () -> MizanAccountProvider.parse(OTHER, json(body(PREMIUM, 0, "", null)), 1));
        AccountRecord withheld = MizanAccountProvider.parse(PREMIUM, json(body(PREMIUM, 2, "\"cheating\"", null)), 1);
        assertTrue(withheld.activeBans.isEmpty());
        assertTrue(AccountIntelligence.message(settings(), "Steve_1", withheld).contains("(2)"));
        assertEquals("a& 4b", MizanAccountProvider.clean("a&4b", 64));
        assertEquals("Tom & Jerry", MizanAccountProvider.clean("Tom & Jerry", 64));
    }

    @Test void requestCarriesOnlyTheUuidAndTheOptionalKey() {
        Request keyless = new MizanAccountProvider("").request(PREMIUM);
        assertEquals("https://api.mizan.ac/v1/players/" + PREMIUM, keyless.url().toString());
        assertNull(keyless.header("X-API-Key"));
        assertEquals(MizanAccountProvider.API_VERSION, keyless.header("Mizan-Version"));
        assertEquals("GET", keyless.method());
        assertEquals("mzn_example", new MizanAccountProvider("mzn_example").request(PREMIUM).header("X-API-Key"));
    }

    @Test void loopbackAnswersAndRateLimitsAreRead() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger calls = new AtomicInteger();
        server.createContext("/v1/players/", exchange -> {
            boolean limited = calls.incrementAndGet() > 1;
            byte[] out = (limited ? "{\"code\":\"rate_limited\"}" : body(PREMIUM, 1, "\"cheating\"", ban("Example SMP", "\"cheating\"", true, "x"))).getBytes(StandardCharsets.UTF_8);
            if (limited) exchange.getResponseHeaders().add("Retry-After", "7");
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(limited ? 429 : 200, out.length);
            exchange.getResponseBody().write(out); exchange.close();
        });
        server.start();
        try {
            MizanAccountProvider provider = new MizanAccountProvider("", HttpUrl.get("http://127.0.0.1:" + server.getAddress().getPort()));
            assertEquals(1, provider.check(PREMIUM).bansInForce);
            LookupException limited = assertThrows(LookupException.class, () -> provider.check(PREMIUM));
            assertEquals(FailureReason.RATE_LIMIT, limited.getReason());
            assertEquals(7000, limited.getRetryAfterMillis());
        } finally { server.stop(0); }
        assertThrows(IllegalArgumentException.class, () -> new MizanAccountProvider("", HttpUrl.get("https://example.com")));
    }

    @Test void joinsAreCheckedOnceWarnOnceAndOfflineAccountsAreSkipped() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch warned = new CountDownLatch(1);
        List<String> messages = new CopyOnWriteArrayList<>();
        AccountIntelligence.setNotifier((text, permission) -> { assertEquals(AccountIntelligence.NOTIFY_PERMISSION, permission); messages.add(text); warned.countDown(); });
        AccountIntelligence.configure(settings(), new AccountProvider() {
            public String id() { return "fixture"; }
            public AccountRecord check(UUID uuid) {
                calls.incrementAndGet();
                return MizanAccountProvider.parse(uuid, json(body(uuid, 1, "\"cheating\"", ban("Example SMP", "\"cheating\"", true, "x"))), System.currentTimeMillis());
            }
        });
        AccountIntelligence.joined(OFFLINE, "Cracked_1", true);
        AccountIntelligence.joined(PREMIUM, "Steve_1", false);
        AccountIntelligence.joined(PREMIUM, "Steve_1", true);
        assertTrue(warned.await(5, TimeUnit.SECONDS));
        AccountIntelligence.lookup(PREMIUM).get(5, TimeUnit.SECONDS);
        AccountIntelligence.joined(PREMIUM, "Steve_1", true);
        AccountIntelligence.lookup(PREMIUM).get(5, TimeUnit.SECONDS);
        Thread.sleep(100);
        assertEquals(1, calls.get(), "cached after the first check");
        assertEquals(1, messages.size(), "one warning per account within the interval");
        assertEquals(Optional.of(PREMIUM), AccountIntelligence.recent("steve_1"));
        assertTrue(AccountIntelligence.describe().contains("notCheckable=2"), AccountIntelligence.describe());
    }

    @Test void notifyStaffOffStillLogsButSendsNothingInGame() throws Exception {
        List<String> messages = new CopyOnWriteArrayList<>();
        AccountIntelligence.setNotifier((text, permission) -> messages.add(text));
        CountDownLatch checked = new CountDownLatch(1);
        AccountIntelligence.configure(settings("behavior.account.notify-staff", false), new AccountProvider() {
            public String id() { return "fixture"; }
            public AccountRecord check(UUID uuid) {
                checked.countDown();
                return MizanAccountProvider.parse(uuid, json(body(uuid, 1, "\"cheating\"", null)), System.currentTimeMillis());
            }
        });
        AccountIntelligence.joined(PREMIUM, "Steve_1", true);
        assertTrue(checked.await(5, TimeUnit.SECONDS));
        AccountIntelligence.lookup(PREMIUM).get(5, TimeUnit.SECONDS);
        Thread.sleep(100);
        assertTrue(messages.isEmpty());
        assertTrue(AccountIntelligence.describe().contains("warnings=1"), AccountIntelligence.describe());
    }

    @Test void commandShowsTheRecordAndNeedsItsPermission() throws Exception {
        AccountIntelligence.configure(settings(), new AccountProvider() {
            public String id() { return "fixture"; }
            public AccountRecord check(UUID uuid) {
                return MizanAccountProvider.parse(uuid, json(body(uuid, 1, "\"cheating\"", ban("Example SMP", "\"cheating\"", true, "Killaura"))), System.currentTimeMillis());
            }
        });
        List<String> denied = new ArrayList<>();
        assertTrue(AccountCommands.handle(new String[]{"account", PREMIUM.toString()}, p -> false, denied::add));
        assertEquals(1, denied.size());
        List<String> lines = new CopyOnWriteArrayList<>();
        assertTrue(AccountCommands.handle(new String[]{"account", PREMIUM.toString().replace("-", "")}, AccountCommands.PERMISSION::equals, lines::add));
        long until = System.currentTimeMillis() + 5000;
        while (lines.size() < 4 && System.currentTimeMillis() < until) Thread.sleep(20);
        assertTrue(lines.get(1).contains("Example SMP") && lines.get(1).contains("Killaura") && lines.get(1).contains("2026-11-28"), lines.toString());
        List<String> offline = new ArrayList<>();
        AccountCommands.handle(new String[]{"account", OFFLINE.toString()}, p -> true, offline::add);
        assertTrue(offline.get(0).contains("premium"), offline.toString());
        assertFalse(AccountCommands.handle(new String[]{"explain", "192.0.2.1"}, p -> true, offline::add));
    }

    private static JsonObject json(String text) { return JsonParser.parseString(text).getAsJsonObject(); }
}
