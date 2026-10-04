package com.github.gerolndnr.connectionguard.core.webhook;

import com.github.gerolndnr.connectionguard.api.v1.*;
import com.github.gerolndnr.connectionguard.api.v1.DecisionObservation.*;
import com.github.gerolndnr.connectionguard.core.messages.MessageCatalog;
import com.google.gson.*;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LocalizedWebhookTest {
    private DecisionObservation event() {
        DetectionMetadata metadata=DetectionMetadata.withExactRisk(Collections.singletonMap(DetectionMetadata.Type.VPN,true),64500L,"private-isp","private-operator","PT",new BigDecimal("79.999"),null);
        Source source=new Source("fixture",Scope.VPN,DetectionObservation.positive(metadata),17,true,true);
        return new DecisionObservation(Platform.VELOCITY,Phase.LOGIN,Mode.ENFORCE,IdentityTrust.UNTRUSTED,null,"192.0.2.55",Outcome.DENY,Reason.VPN_FLAG,Check.POSITIVE,Check.NOT_CHECKED,1000,19,false,EnumSet.of(Flag.VPN),Collections.singletonList(source),Collections.emptyList());
    }
    @Test void germanAndSpanishTranslateHumanLabelsWithoutChangingCodesDecimalsOrPrivacy() {
        for(String code:Arrays.asList("de","es")) {
            MessageCatalog messages=MessageCatalog.defaults(code);
            JsonObject json=JsonParser.parseString(new Gson().toJson(CGWebHookRequest.embedded(event(),EnumSet.of(Scope.VPN),false,2,messages))).getAsJsonObject();
            JsonObject embed=json.getAsJsonArray("embeds").get(0).getAsJsonObject();
            assertEquals(messages.getString("webhook.title-deny"),embed.get("title").getAsString());
            String full=json.toString();for(String fact:Arrays.asList("DENY","VPN_FLAG","POSITIVE","79.999","64500","PT","UNKNOWN"))assertTrue(full.contains(fact),fact);
            for(String hidden:Arrays.asList("192.0.2.55","private-isp","private-operator"))assertFalse(full.contains(hidden));
            assertEquals(0,json.getAsJsonObject("allowed_mentions").getAsJsonArray("parse").size());
            int total=embed.get("title").getAsString().length()+embed.get("description").getAsString().length()+embed.getAsJsonObject("footer").get("text").getAsString().length();
            for(JsonElement element:embed.getAsJsonArray("fields")) {JsonObject field=element.getAsJsonObject();assertTrue(field.get("name").getAsString().length()<=256);assertTrue(field.get("value").getAsString().length()<=1024);total+=field.get("name").getAsString().length()+field.get("value").getAsString().length();}
            assertTrue(total<=6000);
        }
    }
    @Test void addressOptInIsStillExplicitInEveryLocale() {
        for(String code:Arrays.asList("en","de","es")) {
            String text=new Gson().toJson(CGWebHookRequest.embedded(event(),EnumSet.of(Scope.VPN),true,1,MessageCatalog.defaults(code)));
            assertTrue(text.contains("192.0.2.55"));assertTrue(text.contains("VPN_FLAG"));assertFalse(text.contains("private-operator"));
        }
    }
}
