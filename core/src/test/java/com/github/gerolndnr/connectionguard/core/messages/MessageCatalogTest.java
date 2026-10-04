package com.github.gerolndnr.connectionguard.core.messages;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class MessageCatalogTest {
    @Test void allBuiltinsHaveTheSameCompleteSchemaAndReadableUnicode() {
        MessageCatalog en=MessageCatalog.defaults("en"),de=MessageCatalog.defaults("de"),es=MessageCatalog.defaults("es");
        assertEquals(en.keys(),de.keys());assertEquals(en.keys(),es.keys());assertTrue(en.keys().size()>=86);
        assertTrue(de.getString("messages.access-denied").contains("Zugangsregeln"));
        assertTrue(es.getString("messages.access-denied").contains("Conexión"));
        assertTrue(de.getStringList("messages.help").get(0).contains("Befehlsübersicht"));
        assertTrue(es.getStringList("messages.help").get(0).contains("Comandos"));
    }
    @Test void olderPartialOverridesUseTheSelectedLocaleAndCannotMutateTheDraft() {
        List<String> custom=new ArrayList<>(Arrays.asList("Custom help"));
        MessageCatalog messages=MessageCatalog.read("de",key->key.equals("messages.help")?custom:key.equals("messages.vpn-block")?"Custom block %NAME%":null);
        custom.set(0,"changed");assertEquals("Custom help",messages.getStringList("messages.help").get(0));
        assertEquals("Custom block %NAME%",messages.getString("messages.vpn-block"));
        assertTrue(messages.getString("messages.geo-block").contains("Verbindungen"));
        assertThrows(UnsupportedOperationException.class,()->messages.getStringList("messages.help").add("extra"));
        assertThrows(UnsupportedOperationException.class,()->messages.keys().clear());
    }
    @Test void unknownCustomLocaleUsesEnglishDefaultsWithExistingOverrides() {
        MessageCatalog custom=MessageCatalog.read("community-BR",key->key.equals("messages.access-denied")?"Community denial":null);
        assertEquals("community-BR",custom.language());assertEquals("Community denial",custom.getString("messages.access-denied"));
        assertEquals(MessageCatalog.defaults("en").getString("webhook.title-deny"),custom.getString("webhook.title-deny"));
    }
    @Test void substitutionsAreLiteralAndDoNotReinterpretArguments() {
        MessageCatalog de=MessageCatalog.defaults("de");String argument="$1\\path{0}%IP%";
        assertEquals("Keine passende "+argument+"-Regel.",de.text("ops.rules-missing",argument));
        assertThrows(IllegalArgumentException.class,()->de.text("ops.rule-stored","only-one"));
        assertEquals(" (selected)",MessageCatalog.defaults("en").getString("webhook.selected"));
        assertTrue(MessageCatalog.defaults("en").getStringList("messages.help").get(1).startsWith(" "));
    }
    @Test void invalidTypesPlaceholdersUnicodeAndLimitsAreRejectedWithoutEchoingContents() {
        for(Object bad:Arrays.asList(17,Arrays.asList("wrong type"),"",String.join("",Collections.nCopies(2001,"x")),"bad%SECRET%","bad{99}","bad\u0001","bad\u202e","bad\ud800")) {
            IllegalArgumentException error=assertThrows(IllegalArgumentException.class,()->MessageCatalog.read("de",key->key.equals("messages.access-denied")?bad:null));
            assertFalse(error.getMessage().contains("SECRET"));if(!String.valueOf(bad).isEmpty())assertFalse(error.getMessage().contains(String.valueOf(bad)));
        }
        assertThrows(IllegalArgumentException.class,()->MessageCatalog.read("es",key->key.equals("messages.help")?Arrays.asList(17):null));
        assertThrows(IllegalArgumentException.class,()->MessageCatalog.read("es",key->key.equals("messages.help")?Collections.nCopies(33,"line"):null));
        assertThrows(IllegalArgumentException.class,()->MessageCatalog.read("es",key->key.equals("webhook.field.decision")?String.join("",Collections.nCopies(81,"x")):null));
    }
    @Test void knownWarningsTranslateWithoutChangingTechnicalOrThirdPartyFacts() {
        MessageCatalog de=MessageCatalog.defaults("de"),en=MessageCatalog.defaults("en");
        assertEquals(de.getString("ops.warning-0"),de.translate(en.getString("ops.warning-0")));
        assertEquals("fixture=UNKNOWN reason=BUDGET_EXHAUSTED",de.translate("fixture=UNKNOWN reason=BUDGET_EXHAUSTED"));
        assertEquals("third-party text",de.translate("third-party text"));
        assertTrue(de.getString("messages.info.unknown").contains("UNKNOWN"));
    }
}
