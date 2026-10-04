package com.github.gerolndnr.connectionguard.core.webhook;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;
class WebhookSettingsTest {
    private static Map<String,Object> enabled() {
        Map<String,Object> fields=new HashMap<>();fields.put("behavior.vpn.send-webhook.enabled",true);fields.put("behavior.vpn.send-webhook.url","https://example.invalid/synthetic-private-token");return fields;
    }
    @Test void absentNewOptionsPreserveTextCompatibilityWithDisabledOtherScope() {
        WebhookSettings value=new WebhookSettings(enabled()::get);assertTrue(value.vpn.isLegacyText());assertFalse(value.hasEmbeds());assertFalse(value.geo.enabled);assertFalse(value.vpn.includeIp);
        assertEquals(1000,value.vpn.cooldownMillis);assertFalse(value.fingerprint().contains("synthetic-private-token"));
        assertThrows(UnsupportedOperationException.class,()->value.vpn.events.clear());
    }
    @ParameterizedTest @ValueSource(strings={"http://example.invalid/synthetic-private-token","https://user:synthetic-private-token@example.invalid/","https://example.invalid/#synthetic-private-token"," https://example.invalid/synthetic-private-token","https://example.invalid/synthetic-private-token\n"})
    void endpointValidationIsStrictAndErrorsDoNotExposeSecrets(String endpoint) {
        Map<String,Object> fields=enabled();fields.put("behavior.vpn.send-webhook.url",endpoint);
        Exception error=assertThrows(IllegalArgumentException.class,()->new WebhookSettings(fields::get));assertFalse(error.getMessage().contains("synthetic-private-token"));
    }
    @Test void completeRecipientDraftFingerprintChangesForPrivacyEventsModeOrEndpoint() {
        Map<String,Object> fields=enabled();fields.put("behavior.vpn.send-webhook.format","EMBED");String base=new WebhookSettings(fields::get).fingerprint();
        for(Map.Entry<String,Object> option:Arrays.asList(new AbstractMap.SimpleEntry<String,Object>("include-ip",true),new AbstractMap.SimpleEntry<String,Object>("events",Arrays.asList("UNKNOWN")),new AbstractMap.SimpleEntry<String,Object>("url","https://example.invalid/replaced"),new AbstractMap.SimpleEntry<String,Object>("cooldown-ms",2000))){
            Map<String,Object> changed=new HashMap<>(fields);changed.put("behavior.vpn.send-webhook."+option.getKey(),option.getValue());assertNotEquals(base,new WebhookSettings(changed::get).fingerprint());
        }
        fields.put("operation.mode","OBSERVE");assertNotEquals(base,new WebhookSettings(fields::get).fingerprint());
    }
    @Test void duplicateUnsupportedOrEmptyEventsFailBeforeActivation() {
        for(Object invalid:Arrays.asList(Collections.emptyList(),Arrays.asList("FLAG","FLAG"),Arrays.asList("synthetic-private-value"),Arrays.asList(1),"FLAG")){
            Map<String,Object> fields=enabled();fields.put("behavior.vpn.send-webhook.events",invalid);
            Exception error=assertThrows(IllegalArgumentException.class,()->new WebhookSettings(fields::get));assertFalse(error.getMessage().contains("synthetic-private-value"));
        }
    }
    @Test void excessiveCooldownOrInvalidFormatIsRejected() {
        Map<String,Object> fields=enabled();fields.put("behavior.vpn.send-webhook.cooldown-ms",60001);assertThrows(IllegalArgumentException.class,()->new WebhookSettings(fields::get));
        fields.put("behavior.vpn.send-webhook.cooldown-ms",-1);assertThrows(IllegalArgumentException.class,()->new WebhookSettings(fields::get));
        fields.put("behavior.vpn.send-webhook.cooldown-ms",0);fields.put("behavior.vpn.send-webhook.format","synthetic-private-value");
        Exception error=assertThrows(IllegalArgumentException.class,()->new WebhookSettings(fields::get));assertFalse(error.getMessage().contains("synthetic-private-value"));
    }
}
