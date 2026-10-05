import com.github.gerolndnr.connectionguard.core.ConnectionGuard;
import com.github.gerolndnr.connectionguard.core.cache.NoCacheProvider;
import com.github.gerolndnr.connectionguard.core.geo.GeoResult;
import com.github.gerolndnr.connectionguard.core.lookup.*;
import com.github.gerolndnr.connectionguard.core.vpn.*;
import com.github.gerolndnr.connectionguard.libs.com.google.gson.*;
import java.io.*;
import java.lang.management.ManagementFactory;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.*;

/** Actual unmodified CG lookup core with controlled typed source inputs. No HTTP, platform or account claim. */
public final class CoreLookupBenchmark {
    private static final ScheduledExecutorService fixture = Executors.newSingleThreadScheduledExecutor();
    private static long cpu() {
        java.lang.management.OperatingSystemMXBean bean = ManagementFactory.getOperatingSystemMXBean();
        return bean instanceof com.sun.management.OperatingSystemMXBean ? ((com.sun.management.OperatingSystemMXBean) bean).getProcessCpuTime() : -1;
    }
    private static void idle() throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (!ConnectionGuard.getLookupRuntime().isIdle() && System.nanoTime() < end) Thread.sleep(2);
        if (!ConnectionGuard.getLookupRuntime().isIdle()) throw new IllegalStateException("Owned lookup did not drain");
    }
    public static void main(String[] args) throws Exception {
        JsonObject suite = JsonParser.parseString(new String(Files.readAllBytes(Paths.get(args[0])), java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
        int rounds = Integer.parseInt(args[1]), samples = Integer.parseInt(args[2]);
        JsonArray rows = new JsonArray();
        Logger logger = Logger.getAnonymousLogger(); logger.setLevel(Level.OFF); ConnectionGuard.setLogger(logger);
        try {
            ConnectionGuard.configureLookup(new LookupSettings(350, 200, 4, 32, 64, 1000, 50));
            ConnectionGuard.setCacheProvider(new NoCacheProvider()); ConnectionGuard.setRequiredPositiveFlags(1);
            for (int round = 0; round < rounds; round++) {
                List<JsonElement> cases = new ArrayList<>(); suite.getAsJsonArray("cases").forEach(cases::add);
                if (round % 2 != 0) Collections.reverse(cases);
                for (JsonElement item : cases) {
                    JsonObject entry = item.getAsJsonObject(); String id = entry.get("id").getAsString(), behavior = entry.get("fixture").getAsString();
                    if (entry.get("track").getAsString().equals("rules") || (behavior.equals("warm_cache") || behavior.equals("warm_negative_cache")) || entry.get("track").getAsString().equals("geo") && !behavior.equals("geo_gb")) {
                        JsonObject skipped = new JsonObject(); skipped.addProperty("case_id", id); skipped.addProperty("round", round);
                        skipped.addProperty("phase", "measure"); skipped.addProperty("sample", 0); skipped.addProperty("status", "unsupported");
                        skipped.addProperty("reason", "core_lookup_does_not_measure_native_policy_or_sqlite"); rows.add(skipped); continue;
                    }
                    idle(); AtomicInteger requests = new AtomicInteger();
                    ConnectionGuard.setVpnProviders(new ArrayList<>(Collections.singletonList((VpnProvider) ip -> {
                        requests.incrementAndGet();
                        if (behavior.startsWith("timeout")) return new CompletableFuture<>();
                        VpnResult result = new VpnResult(ip, !behavior.equals("negative"));
                        if (behavior.startsWith("503")) result.setUnknown(FailureReason.HTTP_ERROR);
                        else if (behavior.startsWith("429")) result.setUnknown(FailureReason.RATE_LIMIT);
                        else if (behavior.startsWith("malformed") || behavior.startsWith("missing")) result.setUnknown(FailureReason.INVALID_RESPONSE);
                        CompletableFuture<Optional<VpnResult>> value = new CompletableFuture<>();
                        if (behavior.equals("slow_positive") || behavior.equals("shared_positive") || behavior.equals("unique_positive"))
                            fixture.schedule(() -> value.complete(Optional.of(result)), 50, TimeUnit.MILLISECONDS);
                        else value.complete(Optional.of(result));
                        return value;
                    })));
                    ConnectionGuard.setGeoProvider(ip -> { requests.incrementAndGet(); return CompletableFuture.completedFuture(Optional.of(new GeoResult(ip, "GB", "", "Owned fixture"))); });
                    int parallel = entry.has("concurrency") ? entry.get("concurrency").getAsInt() : 1;
                    for (String phase : new String[]{"warmup", "measure"}) for (int sample = 0; sample < (phase.equals("warmup") ? 1 : samples); sample++) {
                        int before = requests.get(); long batchStart = System.nanoTime(), beforeCpu = cpu();
                        List<CompletableFuture<JsonObject>> batch = new ArrayList<>();
                        for (int index = 0; index < parallel; index++) {
                            String ip = behavior.equals("unique_positive") ? "192.0.2." + (50 + index) : entry.get("ip").getAsString();
                            long start = System.nanoTime();
                            CompletableFuture<JsonObject> observation;
                            if (behavior.equals("geo_gb")) observation = ConnectionGuard.getGeoLookup(ip).thenApply(value -> {
                                JsonObject measured = new JsonObject(); measured.addProperty("outcome", value.getResult().isPresent() ? value.getResult().get().getCountryName() : "UNKNOWN");
                                measured.addProperty("duration_ns", System.nanoTime() - start); measured.addProperty("reason", value.getReason().name()); return measured;
                            });
                            else observation = ConnectionGuard.getVpnResult(ip).thenApply(value -> {
                                JsonObject measured = new JsonObject(); measured.addProperty("outcome", value.getStatus().name());
                                measured.addProperty("duration_ns", System.nanoTime() - start);
                                JsonArray reasons = new JsonArray(); value.getVotes().forEach(vote -> reasons.add(vote.getReason().name())); measured.add("source_reasons", reasons); return measured;
                            });
                            batch.add(observation);
                        }
                        CompletableFuture.allOf(batch.toArray(new CompletableFuture<?>[0])).get(2, TimeUnit.SECONDS);
                        long elapsed = System.nanoTime() - batchStart, afterCpu = cpu();
                        int calls = requests.get() - before;
                        for (int index = 0; index < batch.size(); index++) {
                            JsonObject row = batch.get(index).get(); row.addProperty("case_id", id); row.addProperty("round", round);
                            row.addProperty("phase", phase); row.addProperty("sample", sample * parallel + index); row.addProperty("status", "measured");
                            row.addProperty("batch_id", round + "-" + id + "-" + phase + "-" + sample); row.addProperty("batch_requests", calls);
                            if (parallel == 1) row.addProperty("requests", calls); else row.add("requests", JsonNull.INSTANCE);
                            row.addProperty("batch_elapsed_ns", elapsed);
                            if (beforeCpu >= 0 && afterCpu >= beforeCpu) row.addProperty("batch_process_cpu_ns", afterCpu - beforeCpu);
                            row.addProperty("heap_used_after_batch_bytes", ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed()); rows.add(row);
                        }
                        idle();
                    }
                }
            }
            System.out.println(rows.toString());
        } finally {
            ConnectionGuard.shutdown(); fixture.shutdownNow(); fixture.awaitTermination(2, TimeUnit.SECONDS);
        }
    }
}
