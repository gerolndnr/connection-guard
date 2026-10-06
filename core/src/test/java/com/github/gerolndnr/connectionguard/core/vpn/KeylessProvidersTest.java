package com.github.gerolndnr.connectionguard.core.vpn;

import com.github.gerolndnr.connectionguard.core.lookup.*;
import com.google.gson.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.*;
import static org.junit.jupiter.api.Assertions.*;

class KeylessProvidersTest {
    static Stream<JsonObject> recorded() throws IOException {
        try (Reader reader = new InputStreamReader(KeylessProvidersTest.class.getResourceAsStream("/providers/keyless-recorded.json"), StandardCharsets.UTF_8)) {
            return StreamSupport.stream(JsonParser.parseReader(reader).getAsJsonObject().getAsJsonArray("cases").spliterator(), false)
                    .map(JsonElement::getAsJsonObject).collect(Collectors.toList()).stream();
        }
    }
    @ParameterizedTest @MethodSource("recorded")
    void realRecordedTextFlagsAndStrictJsonFlagsRetainTheirVerdicts(JsonObject fixture) {
        String ip = fixture.get("ip").getAsString(); JsonObject answers = fixture.getAsJsonObject("responses");
        if (answers.has("blackbox")) {
            String answer = answers.get("blackbox").getAsString();
            VpnResult result = BlackboxVpnProvider.parse(ip, answer).get();
            assertEquals(answer.equals("Y"), result.isVpn());
            assertTrue(result.getDetails().getClassifications().isEmpty(), "Aggregate list never fabricates explicit VPN/hosting flags.");
        }
        if (answers.has("ipcheck")) assertEquals(answers.get("ipcheck").getAsString().equals("TRUE"), IpCheckVpnProvider.parse(ip, answers.get("ipcheck").getAsString()).get().isVpn());
        if (answers.has("zowi")) {
            JsonObject s = answers.getAsJsonObject("zowi").getAsJsonObject("security");
            boolean positive = s.getAsJsonObject("vpn").get("detected").getAsBoolean() || s.get("proxy").getAsBoolean() || s.get("tor").getAsBoolean();
            VpnResult result = ZowiVpnProvider.parse(ip, answers.getAsJsonObject("zowi")).get();
            assertEquals(positive, result.isVpn());
            assertEquals(!positive && s.get("hosting").getAsBoolean() ? ProviderVote.Status.UNKNOWN : positive ? ProviderVote.Status.POSITIVE : ProviderVote.Status.NEGATIVE, result.getStatus());
        }
    }
    @Test void recordedUnflaggedResidentialControlsRemainNegativeWithoutSuppressingRecordedFalsePositives() throws Exception {
        List<JsonObject> controls = recorded().filter(f -> f.get("cohort").getAsString().startsWith("residential")
                && f.getAsJsonObject("responses").get("blackbox").getAsString().equals("N")).collect(Collectors.toList());
        assertTrue(controls.size() >= 10);
        for (JsonObject f : controls) assertEquals(ProviderVote.Status.NEGATIVE, BlackboxVpnProvider.parse(f.get("ip").getAsString(), "N").get().getStatus());
        // Known Y flags on labelled residential subjects remain Y in the fixtures, not hidden as clean.
        assertTrue(recorded().anyMatch(f -> f.get("cohort").getAsString().startsWith("residential") && f.getAsJsonObject("responses").get("blackbox").getAsString().equals("Y")));
    }
    @ParameterizedTest @ValueSource(strings = {"", "E", "y", "n", "true", "false", "FALSE", "0", "[]", "{}", "<html>N</html>", "N\uFFFD", "N\u0000", "\"N\""})
    void unexpectedBlackboxAnswersNeverBecomeClean(String answer) { assertFalse(BlackboxVpnProvider.parse("192.0.2.1", answer).isPresent()); }
    @ParameterizedTest @ValueSource(strings = {"", "E", "N", "true", "false", "0", "[]", "{}", "<html>FALSE</html>", "FALSE\uFFFD", "FALSE\u0000", "\"FALSE\""})
    void unexpectedIpCheckAnswersNeverBecomeClean(String answer) { assertFalse(IpCheckVpnProvider.parse("192.0.2.1", answer).isPresent()); }
    @Test void whitespaceIsAllowedAndBlackboxHasAnExplicitBroadListingDescription() {
        VpnResult result = BlackboxVpnProvider.parse("192.0.2.1", " Y\r\n").get();
        assertTrue(result.isVpn()); assertEquals(BlackboxVpnProvider.LISTED_REASON, result.getVpnProviderName().get());
        assertFalse(BlackboxVpnProvider.parse("192.0.2.1", "N\n").get().isVpn());
        assertFalse(IpCheckVpnProvider.parse("192.0.2.1", "FALSE\r\n").get().isVpn());
    }
    private static JsonObject json(String s) { return JsonParser.parseString(s).getAsJsonObject(); }
    @ParameterizedTest @ValueSource(strings = {"{}", "{\"security\":null}", "{\"security\":{}}", "{\"security\":{\"vpn\":{\"detected\":false},\"proxy\":false}}"})
    void missingZowiSignalsStayUnknown(String body) { assertFalse(ZowiVpnProvider.parse("192.0.2.1", json(body)).isPresent()); }
    @ParameterizedTest @ValueSource(strings = {"{\"security\":false}", "{\"security\":{\"vpn\":false}}", "{\"security\":{\"vpn\":{\"detected\":\"false\"}}}", "{\"security\":{\"proxy\":\"false\"}}", "{\"security\":{\"tor\":0}}", "{\"security\":{\"vpn\":{\"detected\":true},\"hosting\":\"false\"}}"})
    void malformedZowiDetectionFieldsInvalidateTheAnswer(String body) { assertThrows(IllegalArgumentException.class, () -> ZowiVpnProvider.parse("192.0.2.1", json(body))); }
    @Test void objectProxyVotesButHostingAndResidentialProxyAloneNeverBlock() {
        assertTrue(ZowiVpnProvider.parse("192.0.2.1", json("{\"security\":{\"proxy\":{\"detected\":true}}}")).get().isVpn());
        VpnResult hosting = ZowiVpnProvider.parse("192.0.2.1", json("{\"security\":{\"vpn\":{\"detected\":false},\"proxy\":false,\"tor\":false,\"hosting\":true}}")).get();
        assertEquals(ProviderVote.Status.UNKNOWN, hosting.getStatus()); assertEquals(FailureReason.NO_EVIDENCE, hosting.getSourceReason());
        assertEquals(Boolean.TRUE, hosting.getDetails().get(DetectionDetails.Type.HOSTING));
        assertFalse(ZowiVpnProvider.parse("192.0.2.1", json("{\"security\":{\"vpn\":{\"detected\":false},\"proxy\":false,\"tor\":false,\"residential_proxy\":{\"detected\":true}}}")).get().isVpn());
    }
    @Test void fixedHttpsRequestsCompressIpv6AndOnlySendTheIp() {
        String ip = "2001:0DB8:0:0:0:0:0:7";
        okhttp3.Request b = new BlackboxVpnProvider().request(ip), i = new IpCheckVpnProvider().request(ip), z = new ZowiVpnProvider().request(ip);
        assertEquals("https://blackbox.ipinfo.app/api/v1/2001:db8::7", b.url().toString());
        assertEquals("https://api.zowi.gay/2001:db8::7", z.url().toString());
        assertEquals("2001:db8::7", i.url().queryParameter("ip")); assertTrue(i.url().encodedQuery().contains("%3A"));
        for (okhttp3.Request request : Arrays.asList(b, i, z)) { assertEquals("GET", request.method()); assertEquals(0, request.headers().size()); assertNull(request.body()); }
        assertThrows(IllegalArgumentException.class, () -> new BlackboxVpnProvider(okhttp3.HttpUrl.get("http://example.invalid/")));
    }
}
