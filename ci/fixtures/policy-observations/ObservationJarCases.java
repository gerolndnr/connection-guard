import com.github.gerolndnr.connectionguard.core.ConnectionGuard;
import com.github.gerolndnr.connectionguard.core.commands.OperationsCommands;
import com.github.gerolndnr.connectionguard.core.config.GuardSettings;
import com.github.gerolndnr.connectionguard.core.lookup.*;
import com.github.gerolndnr.connectionguard.core.policy.*;
import com.github.gerolndnr.connectionguard.core.rules.*;
import com.github.gerolndnr.connectionguard.core.vpn.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Synthetic offline cases against the combined shaded artifact on Java 8+. */
public final class ObservationJarCases {
    static final String IP = "198.51.100.57";
    static final UUID UUID_VALUE = UUID.fromString("abc524ac-8470-4437-9648-a6c139b112f9");
    static final AtomicInteger requests = new AtomicInteger();
    static void require(boolean value) { if (!value) throw new AssertionError("Actual-JAR observation case failed"); }
    static void passed(String name) { System.out.println("OBSERVATION_JAR_CASE " + name); }
    static List<String> command(String... args) {
        List<String> result = new ArrayList<>(); require(OperationsCommands.handle(args, permission -> true, result::add)); return result;
    }
    static VpnResult positive() {
        VpnResult value = new VpnResult(IP, true);
        value.setVotes(Collections.singletonList(new ProviderVote("fixture", ProviderVote.Status.POSITIVE, FailureReason.NONE, 1)));
        return value;
    }
    static void evaluate() {
        ConnectionGuard.evaluatePolicy(ConnectionGuard.getSettings(), IP, UUID_VALUE, true, false, false, positive(),
                new GeoLookup(Optional.empty(), FailureReason.NO_PROVIDER, false, 0), null, System.currentTimeMillis());
    }
    static PolicyReplay.Cases read(Path archive) throws Exception {
        try (InputStream input = Files.newInputStream(archive)) { return PolicyReplay.readCases(input); }
    }
    public static void main(String[] args) throws Exception {
        Path data = Paths.get(args[0]).resolve("data"); Files.createDirectory(data);
        Path archive = data.resolve("policy/observations.json");
        ConnectionGuard.applySettings(GuardSettings.defaults()); ConnectionGuard.initializeRules(data);
        ConnectionGuard.setVpnProviders(new ArrayList<>(Collections.singletonList(ip -> {
            requests.incrementAndGet(); return CompletableFuture.completedFuture(Optional.of(positive()));
        })));
        try {
            evaluate(); require(!Files.exists(data.resolve("policy"))); passed("default_off_has_no_capture_file");
            List<String> denied = new ArrayList<>(); OperationsCommands.handle(new String[]{"policy", "capture", "start"},
                    permission -> permission.equals("connectionguard.command.policy"), denied::add);
            require(denied.size() == 1 && denied.get(0).contains("permission")); passed("capture_requires_separate_permission");
            command("policy", "capture", "start", "5m", "facts"); evaluate(); require(!Files.exists(archive));
            require(ConnectionGuard.policyObservationCaptureStatus().samples == 1); passed("authenticated_inputs_captured_without_login_io");
            command("policy", "capture", "stop"); String body = new String(Files.readAllBytes(archive), StandardCharsets.UTF_8);
            require(!body.contains(IP) && !body.contains(UUID_VALUE.toString())); require(read(archive).identitiesRedacted);
            passed("default_sample_redacts_real_address_and_uuid");
            require(command("policy", "test", "observations").get(0).contains("Saved local observations: 1"));
            require(requests.get() == 0); passed("saved_replay_has_no_provider_requests");
            AccessRule identityRule = new AccessRule("literal", AccessRule.Effect.DENY, AccessRule.Scope.ALL, IP, 0, "Synthetic local rule");
            PolicyReplay.Snapshot candidate = new PolicyReplay.Snapshot(GuardSettings.defaults(), Collections.singletonList(identityRule));
            boolean rejected = false; try { read(archive).cases.get(0).evaluate(candidate, System.currentTimeMillis()); } catch (IllegalArgumentException expected) { rejected = true; }
            require(rejected); passed("redacted_placeholder_cannot_match_literal_rule");
            command("policy", "capture", "clear");
            command("policy", "capture", "start", "5m", "identities"); evaluate(); command("policy", "capture", "stop");
            body = new String(Files.readAllBytes(archive), StandardCharsets.UTF_8);
            require(body.contains(IP) && body.contains(UUID_VALUE.toString()) && !read(archive).identitiesRedacted);
            passed("explicit_identity_mode_preserves_required_local_inputs");
            require(read(archive).cases.get(0).evaluate(candidate, read(archive).cases.get(0).observedAt).denial != null);
            passed("identity_mode_replays_address_denial");
            Path candidateFile = data.resolve("policy/candidate.json"); Files.write(candidateFile, candidate.toJson().toString().getBytes(StandardCharsets.UTF_8));
            require(command("policy", "test", "observations", "candidate", "captured").get(0).contains("changed outcomes: 1"));
            require(ConnectionGuard.getRuleStore().snapshot().isEmpty() && requests.get() == 0);
            passed("historical_command_does_not_activate_candidate");
            byte[] before = Files.readAllBytes(archive); ConnectionGuard.shutdown(); ConnectionGuard.initializeRules(data);
            require(Arrays.equals(before, Files.readAllBytes(archive)) && ConnectionGuard.policyObservationCaptureStatus().savedSamples == 1);
            passed("restart_preserves_archive_without_resuming_capture");
            command("policy", "capture", "clear"); command("policy", "capture", "start", "5m"); evaluate(); ConnectionGuard.shutdown();
            require(!Files.exists(archive)); passed("shutdown_discards_unsaved_sample");
        } finally { ConnectionGuard.shutdown(); }
    }
}
