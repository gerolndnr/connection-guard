package com.github.gerolndnr.connectionguard.core.webhook;

import com.github.gerolndnr.connectionguard.api.v1.*;
import com.github.gerolndnr.connectionguard.api.v1.DecisionObservation.*;
import com.github.gerolndnr.connectionguard.core.messages.MessageCatalog;
import com.google.gson.Gson;
import com.google.gson.JsonParser;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import static org.junit.jupiter.api.Assertions.*;

class WebhookTemplateTest {
    private static final UUID UUID_VALUE=UUID.fromString("12345678-1234-1234-1234-123456789abc");
    private static DecisionObservation event(Check check,IdentityTrust trust,Reason reason,List<Source> sources) {
        return new DecisionObservation(Platform.VELOCITY,Phase.LOGIN,Mode.ENFORCE,trust,UUID_VALUE,"192.0.2.55",
                Outcome.DENY,reason,check,Check.KNOWN,1000,10,false,EnumSet.of(Flag.VPN),sources,Collections.emptyList());
    }
    private static Source country(String country,DetectionObservation.Status status) {
        return new Source("synthetic",Scope.VPN,new DetectionObservation(status,status==DetectionObservation.Status.UNKNOWN
                ? DetectionObservation.Reason.NO_EVIDENCE : DetectionObservation.Reason.NONE,
                new DetectionMetadata(null,null,null,null,country,null,null),0,null),5,true,false);
    }
    @Test void operatorControlsExactlyTheRequestedFieldsAndTheirOrder() {
        String template="%NAME%, %UUID%, %IS_VPN%, %IP%, %LOCATION%, %TIME%";
        WebhookContext context=WebhookContext.EMPTY.withPlayerName("FixtureUser").withLocation("DE","Berlin","Fixture ISP");
        assertEquals("FixtureUser, "+UUID_VALUE+", true, 192.0.2.55, Berlin, DE, 1970-01-01T00:00:01Z",
                WebhookTemplate.render(template,event(Check.POSITIVE,IdentityTrust.AUTHENTICATED,Reason.VPN_FLAG,Collections.emptyList()),context));
        String selected="%TIME% | %NAME% | %IS_VPN%";
        assertEquals("1970-01-01T00:00:01Z | FixtureUser | true",WebhookTemplate.render(selected,event(Check.POSITIVE,IdentityTrust.AUTHENTICATED,Reason.VPN_FLAG,Collections.emptyList()),context));
    }
    @ParameterizedTest @EnumSource(Check.class) void unperformedChecksNeverBecomeNegative(Check check) {
        String expected=check==Check.POSITIVE ? "true" : check==Check.NEGATIVE ? "false" : "UNKNOWN";
        assertEquals(expected,WebhookTemplate.render("%IS_VPN%",event(check,IdentityTrust.UNTRUSTED,Reason.VPN_FLAG,Collections.emptyList()),WebhookContext.EMPTY));
    }
    @Test void missingUnverifiedOrLostIdentityNeverInventsAUuid() {
        assertEquals("UNKNOWN",WebhookTemplate.render("%UUID%",event(Check.POSITIVE,IdentityTrust.UNTRUSTED,Reason.VPN_FLAG,Collections.emptyList()),WebhookContext.EMPTY));
        assertEquals("UNKNOWN",WebhookTemplate.render("%UUID%",event(Check.POSITIVE,IdentityTrust.AUTHENTICATED,Reason.IDENTITY_UNAVAILABLE,Collections.emptyList()),WebhookContext.EMPTY));
    }
    @Test void countryOnlyUsesExistingUnambiguousKnownSourceFacts() {
        DecisionObservation e=event(Check.NEGATIVE,IdentityTrust.UNTRUSTED,Reason.GEO_FLAG,Collections.singletonList(country("DE",DetectionObservation.Status.NEGATIVE)));
        assertEquals("DE | UNKNOWN | UNKNOWN",WebhookTemplate.render("%LOCATION% | %CITY% | %ISP%",e,WebhookContext.EMPTY));
        for(List<Source> sources:Arrays.asList(Arrays.asList(country("DE",DetectionObservation.Status.NEGATIVE),country("US",DetectionObservation.Status.POSITIVE)),
                Collections.singletonList(country("DE",DetectionObservation.Status.UNKNOWN))))
            assertEquals("UNKNOWN",WebhookTemplate.render("%LOCATION%",event(Check.UNKNOWN,IdentityTrust.UNTRUSTED,Reason.UNKNOWN_ALLOWED,sources),WebhookContext.EMPTY));
    }
    @Test void substitutionIsOnePassAndLiteralWithBoundedSanitizedValues() {
        WebhookContext context=WebhookContext.EMPTY.withPlayerName("@everyone %IP% $\\\u202e\n").withLocation("DE","%UUID%","Fixture ISP");
        String rendered=WebhookTemplate.render("%NAME% | %CITY% | %ISP%",event(Check.POSITIVE,IdentityTrust.UNTRUSTED,Reason.VPN_FLAG,Collections.emptyList()),context);
        assertEquals("@everyone %IP% $\\ | %UUID% | Fixture ISP",rendered);
        com.google.gson.JsonObject payload=JsonParser.parseString(new Gson().toJson(new CGWebHookRequest(rendered))).getAsJsonObject();
        assertEquals(0,payload.getAsJsonObject("allowed_mentions").getAsJsonArray("parse").size());
        assertTrue(WebhookTemplate.render("%NAME%",event(Check.POSITIVE,IdentityTrust.UNTRUSTED,Reason.VPN_FLAG,Collections.emptyList()),
                WebhookContext.EMPTY.withPlayerName(String.join("",Collections.nCopies(300,"a")))).length()<=200);
    }
    @ParameterizedTest @EnumSource(Platform.class) void theSameTemplateWorksOnEveryNativeAdapter(Platform platform) {
        DecisionObservation e=new DecisionObservation(platform,Phase.LOGIN,Mode.ENFORCE,IdentityTrust.PLATFORM_ONLINE,UUID_VALUE,
                "192.0.2.55",Outcome.DENY,Reason.VPN_FLAG,Check.POSITIVE,Check.UNKNOWN,1000,10,false,EnumSet.of(Flag.VPN),Collections.emptyList(),Collections.emptyList());
        assertEquals("FixtureUser | true",WebhookTemplate.render("%NAME% | %IS_VPN%",e,WebhookContext.EMPTY.withPlayerName("FixtureUser")));
    }
    @Test void allLocalesAcceptCustomVariablesOnlyInWebhookTemplatesAndRejectUnsupportedOnes() {
        String template="%NAME% | %UUID% | %IS_VPN% | %IP% | %LOCATION% | %COUNTRY% | %CITY% | %ISP% | %TIME%";
        for(String language:Arrays.asList("en","de","es")) {
            MessageCatalog catalog=MessageCatalog.read(language,key->key.equals("messages.vpn-webhook") || key.equals("messages.geo-webhook") ? template : null);
            assertEquals(template,catalog.getString("messages.vpn-webhook"));assertEquals(template,catalog.getString("messages.geo-webhook"));
            assertThrows(IllegalArgumentException.class,()->MessageCatalog.read(language,key->key.equals("messages.vpn-webhook")?"%SECRET%":null));
            assertThrows(IllegalArgumentException.class,()->MessageCatalog.read(language,key->key.equals("messages.vpn-notify")?"%UUID%":null));
        }
    }
    @Test void historicalDefaultTemplatesStillRenderTheSameKnownValues() {
        WebhookContext c=WebhookContext.EMPTY.withPlayerName("FixtureUser").withLocation("DE","Berlin","Fixture ISP");
        DecisionObservation e=event(Check.POSITIVE,IdentityTrust.AUTHENTICATED,Reason.VPN_FLAG,Collections.emptyList());
        assertEquals("FixtureUser (192.0.2.55) tried to connect with a vpn.",WebhookTemplate.render(MessageCatalog.defaults("en").getString("messages.vpn-webhook"),e,c));
        assertEquals("FixtureUser (192.0.2.55, Fixture ISP) tried to connect from Berlin, DE.",WebhookTemplate.render(MessageCatalog.defaults("en").getString("messages.geo-webhook"),e,c));
        assertFalse(new Gson().toJson(e).contains("FixtureUser"));assertFalse(new Gson().toJson(e).contains("Berlin"));
    }
}
