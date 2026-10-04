package com.github.gerolndnr.connectionguard.core.webhook;

import com.github.gerolndnr.connectionguard.api.v1.*;
import com.github.gerolndnr.connectionguard.api.v1.DecisionObservation.*;
import com.google.gson.*;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WebhookPayloadTest {
    private static final UUID PRIVATE_UUID=UUID.fromString("00000000-0000-0000-0000-000000000011");
    private static DecisionObservation event(Outcome outcome,Reason reason,Mode mode,Set<Flag> flags,List<Source> sources,List<Rule> rules) {
        return new DecisionObservation(Platform.VELOCITY,Phase.LOGIN,mode,IdentityTrust.AUTHENTICATED,PRIVATE_UUID,
                "192.0.2.55",outcome,reason,Check.UNKNOWN,Check.NOT_CHECKED,1000,17,false,flags,sources,rules);
    }
    private static JsonObject payload(DecisionObservation event,boolean ip) {
        return JsonParser.parseString(new Gson().toJson(CGWebHookRequest.embedded(event,EnumSet.of(Scope.VPN),ip,2))).getAsJsonObject();
    }
    private static String fields(JsonObject payload){return payload.getAsJsonArray("embeds").get(0).getAsJsonObject().getAsJsonArray("fields").toString();}
    @Test void richFactsKeepDecimalsUnknownsAndCacheWhileOmittingIdentityAndProviderText() {
        Map<DetectionMetadata.Type,Boolean> types=new EnumMap<>(DetectionMetadata.Type.class);types.put(DetectionMetadata.Type.VPN,true);
        DetectionMetadata metadata=DetectionMetadata.withExactRisk(types,64500L,"synthetic-private-isp","synthetic-private-operator","PT",new BigDecimal("79.999"),null);
        Source source=new Source("fixture",Scope.VPN,DetectionObservation.positive(metadata),7,true,true);
        JsonObject value=payload(event(Outcome.ALLOW,Reason.FLAG_ALLOWED,Mode.ENFORCE,EnumSet.of(Flag.VPN),Collections.singletonList(source),Collections.emptyList()),false);
        String json=value.toString();
        assertTrue(json.contains("connection allowed"));assertTrue(json.contains("FLAG_ALLOWED"));assertTrue(json.contains("79.999"));
        assertTrue(json.contains("cached: true"));assertTrue(json.contains("confidence: UNKNOWN"));assertTrue(json.contains("configured minimum: 2"));
        for(String hidden:Arrays.asList("192.0.2.55",PRIVATE_UUID.toString(),"synthetic-private-isp","synthetic-private-operator"))assertFalse(json.contains(hidden));
        assertEquals(0,value.getAsJsonObject("allowed_mentions").getAsJsonArray("parse").size());
        assertFalse(value.has("content"));assertEquals(1,value.getAsJsonArray("embeds").size());
    }
    @Test void addressRequiresExplicitOptInAndDoesNotAddUuid() {
        JsonObject value=payload(event(Outcome.DENY,Reason.LOOKUP_UNAVAILABLE,Mode.ENFORCE,Collections.emptySet(),Collections.emptyList(),Collections.emptyList()),true);
        assertTrue(value.toString().contains("192.0.2.55"));assertFalse(value.toString().contains(PRIVATE_UUID.toString()));
        assertTrue(value.toString().contains("connection denied"));
    }
    @Test void unknownSourceIsNeverRenderedAsKnownNegativeOrZero() {
        Source source=new Source("fixture",Scope.VPN,DetectionObservation.unknown(DetectionObservation.Reason.BUDGET_EXHAUSTED),0,true,false);
        String fields=fields(payload(event(Outcome.ALLOW,Reason.UNKNOWN_ALLOWED,Mode.ENFORCE,Collections.emptySet(),Collections.singletonList(source),Collections.emptyList()),false));
        assertTrue(fields.contains("UNKNOWN / BUDGET_EXHAUSTED"));assertTrue(fields.contains("Types: UNKNOWN"));assertTrue(fields.contains("Risk (source value): UNKNOWN"));
        assertTrue(fields.contains("Positive: 0 / negative: 0 / unknown: 1"));
    }
    @Test void maximumFactsFitDiscordBoundsAndDescribeOmissionsWithoutTruncatingRisk() {
        char[] id=new char[100];Arrays.fill(id,'x');char[] digits=new char[126];Arrays.fill(digits,'9');
        BigDecimal risk=new BigDecimal("0."+new String(digits));
        DetectionMetadata details=DetectionMetadata.withExactRisk(null,4294967295L,null,null,"PT",risk,100);
        List<Source> sources=new ArrayList<>();for(int i=0;i<17;i++)sources.add(new Source(new String(id),Scope.VPN,DetectionObservation.positive(details),Long.MAX_VALUE,true,true));
        List<Rule> rules=new ArrayList<>();for(int i=0;i<1026;i++)rules.add(new Rule("rule"+i,Scope.ALL,Effect.DENY,Match.UNKNOWN,i==1025));
        JsonObject value=payload(event(Outcome.DENY,Reason.ACCESS_RULE,Mode.ENFORCE,EnumSet.of(Flag.ACCESS_POLICY),sources,rules),false);
        JsonObject embed=value.getAsJsonArray("embeds").get(0).getAsJsonObject();JsonArray fields=embed.getAsJsonArray("fields");
        int length=embed.get("title").getAsString().length()+embed.get("description").getAsString().length()+embed.getAsJsonObject("footer").get("text").getAsString().length();
        assertTrue(fields.size()<=25);
        for(JsonElement field:fields){String name=field.getAsJsonObject().get("name").getAsString(),text=field.getAsJsonObject().get("value").getAsString();assertTrue(name.length()<=256);assertTrue(text.length()<=1024);length+=name.length()+text.length();}
        assertTrue(length<=6000);assertTrue(value.toString().contains("Sources shown: 8/17"));
        assertTrue(fields.toString().contains(risk.toString()));assertTrue(fields.toString().contains("rule1025"));assertTrue(fields.toString().contains("/1026 relevant rules"));
    }
    @Test void textCompatibilitySuppressesMentionsAndBoundsUnicodeWithoutBrokenSurrogates() {
        char[] content=new char[2100];Arrays.fill(content,'x');
        String unsafe="@everyone\u202e\u0000\ud800"+new String(content)+"\ud83d\ude00";
        CGWebHookRequest value=new CGWebHookRequest(unsafe);JsonObject json=JsonParser.parseString(new Gson().toJson(value)).getAsJsonObject();
        assertEquals(2000,value.getContent().length());assertFalse(value.getContent().contains("\u202e"));assertFalse(value.getContent().contains("\u0000"));assertFalse(value.getContent().contains("\ud800"));
        assertEquals(0,json.getAsJsonObject("allowed_mentions").getAsJsonArray("parse").size());assertFalse(json.has("embeds"));
        assertEquals("x",CGWebHookRequest.safeText("x\ud83d\ude00",2));
    }
    private static WebhookSettings settings(String... events) {
        Map<String,Object> fields=new HashMap<>();fields.put("behavior.vpn.send-webhook.enabled",true);fields.put("behavior.vpn.send-webhook.url","https://example.invalid/synthetic-token");
        fields.put("behavior.vpn.send-webhook.format","EMBED");fields.put("behavior.vpn.send-webhook.events",Arrays.asList(events));return new WebhookSettings(fields::get);
    }
    @Test void flagAllowedIsReportedButOrdinaryAllowedChecksAreQuiet() {
        WebhookSettings settings=settings("FLAG");
        assertTrue(CGWebHookHelper.selected(event(Outcome.ALLOW,Reason.FLAG_ALLOWED,Mode.ENFORCE,EnumSet.of(Flag.VPN),Collections.emptyList(),Collections.emptyList()),Scope.VPN,settings.vpn));
        assertFalse(CGWebHookHelper.selected(event(Outcome.ALLOW,Reason.CHECKS_COMPLETE,Mode.ENFORCE,Collections.emptySet(),Collections.emptyList(),Collections.emptyList()),Scope.VPN,settings.vpn));
        assertFalse(CGWebHookHelper.selected(event(Outcome.ALLOW,Reason.FLAG_ALLOWED,Mode.OBSERVE,EnumSet.of(Flag.VPN),Collections.emptyList(),Collections.emptyList()),Scope.VPN,settings.vpn));
    }
    @Test void identityUnavailableIsExplicitUnknownEvenBeforeProviderChecks() {
        WebhookSettings settings=settings("UNKNOWN");
        DecisionObservation event=new DecisionObservation(Platform.VELOCITY,Phase.LOGIN,Mode.ENFORCE,IdentityTrust.UNTRUSTED,null,"192.0.2.55",Outcome.ALLOW,Reason.IDENTITY_UNAVAILABLE,Check.NOT_CHECKED,Check.NOT_CHECKED,1000,1,false,Collections.emptySet(),Collections.emptyList(),Collections.emptyList());
        assertTrue(CGWebHookHelper.selected(event,Scope.VPN,settings.vpn));assertFalse(CGWebHookHelper.selected(event,Scope.VPN,settings("FLAG").vpn));
    }
    @Test void selectedManualDenyWithoutProviderFactsRoutesOnlyToItsScope() {
        WebhookSettings settings=settings("DENY");
        Rule rule=new Rule("manual",Scope.VPN,Effect.DENY,Match.MATCH,true);
        DecisionObservation event=event(Outcome.DENY,Reason.ACCESS_RULE,Mode.ENFORCE,EnumSet.of(Flag.ACCESS_POLICY),Collections.emptyList(),Collections.singletonList(rule));
        assertTrue(CGWebHookHelper.selected(event,Scope.VPN,settings.vpn));assertFalse(CGWebHookHelper.selected(event,Scope.GEO,settings.vpn));
    }
}
