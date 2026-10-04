package com.github.gerolndnr.connectionguard.core.messages;

import com.github.gerolndnr.connectionguard.core.config.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MessageDraftTest {
    @Test void localeChangesDoNotMakeNewDetectionFactsOrLowerSecuritySettings() {
        Map<String,Object> values=new HashMap<>();values.put("provider.geo.service","Disabled");
        ProviderConfiguration en=new ProviderConfiguration(values::get,Collections.emptyList());
        values.put("message-language","de");ProviderConfiguration de=new ProviderConfiguration(values::get,Collections.emptyList());
        assertEquals(en.cacheNamespace,de.cacheNamespace);assertEquals(en.threshold,de.threshold);assertEquals(en.settings.vpnFailure,de.settings.vpnFailure);
        assertTrue(de.messages.getString("messages.access-denied").contains("Zugangsregeln"));
    }
    @Test void localRefreshKeepsTheLoadedCustomCatalogRatherThanReplacingItWithDefaults() {
        Map<String,Object> values=new HashMap<>();values.put("provider.geo.service","Disabled");values.put("message-language","de");
        MessageCatalog custom=MessageCatalog.read("de",key->key.equals("messages.access-denied")?"Operator-selected text":null);
        ProviderConfiguration draft=new ProviderConfiguration(values::get,Collections.emptyList(),null,custom);
        assertSame(custom,draft.refreshLocal().messages);assertEquals(draft.cacheNamespace,draft.refreshLocal().cacheNamespace);
    }
    @Test void mismatchedCatalogAndUnsafeLocaleRejectACompleteDraft() {
        Map<String,Object> values=new HashMap<>();values.put("message-language","de");
        assertThrows(IllegalArgumentException.class,()->new ProviderConfiguration(values::get,Collections.emptyList(),null,MessageCatalog.defaults("es")));
        values.put("message-language","../private-token");
        assertThrows(IllegalArgumentException.class,()->GuardSettings.read(values::get,Collections.emptyList()));
        assertThrows(IllegalArgumentException.class,()->new ProviderConfiguration(values::get,Collections.emptyList()));
    }
    @Test void capturedDecisionKeepsItsLocaleWhenAnotherDraftIsSelected()throws Exception {
        java.lang.reflect.Field active=com.github.gerolndnr.connectionguard.core.ConnectionGuard.class.getDeclaredField("activeDraft");
        active.setAccessible(true);
        synchronized(com.github.gerolndnr.connectionguard.core.ConnectionGuard.class) {
            Object original=active.get(null);
            Map<String,Object> values=new HashMap<>();values.put("provider.geo.service","Disabled");values.put("message-language","de");
            ProviderConfiguration german=new ProviderConfiguration(values::get,Collections.emptyList());
            active.set(null,german);
            com.github.gerolndnr.connectionguard.core.extensions.DecisionCapture captured=
                com.github.gerolndnr.connectionguard.core.extensions.DecisionCapture.begin(
                    com.github.gerolndnr.connectionguard.api.v1.DecisionObservation.Platform.VELOCITY,
                    com.github.gerolndnr.connectionguard.api.v1.DecisionObservation.Phase.LOGIN,"192.0.2.55",null,
                    com.github.gerolndnr.connectionguard.api.v1.DecisionObservation.IdentityTrust.UNTRUSTED);
            try {
                values.put("message-language","es");active.set(null,new ProviderConfiguration(values::get,Collections.emptyList()));
                assertSame(german.messages,captured.messages());
                assertTrue(captured.messages().getString("messages.access-denied").contains("Zugangsregeln"));
                assertTrue(com.github.gerolndnr.connectionguard.core.ConnectionGuard.getMessages().getString("messages.access-denied").contains("Conexión"));
            } finally { active.set(null,original);captured.close(); }
        }
    }

}
