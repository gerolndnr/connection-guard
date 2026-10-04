package com.github.gerolndnr.connectionguard.core.webhook;

import com.github.gerolndnr.connectionguard.api.v1.*;
import com.github.gerolndnr.connectionguard.api.v1.DecisionObservation.*;
import com.google.gson.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class WebhookRoutingTest {
    private static Map<String,Object> settings(){
        Map<String,Object> result=new HashMap<>();for(String scope:Arrays.asList("vpn","geo")){
            String p="behavior."+scope+".send-webhook.";result.put(p+"enabled",true);result.put(p+"format","EMBED");result.put(p+"url","https://example.invalid/synthetic");
        }return result;
    }
    private static DecisionObservation event(Mode mode){return new DecisionObservation(Platform.VELOCITY,Phase.LOGIN,mode,IdentityTrust.UNTRUSTED,null,"192.0.2.5",Outcome.DENY,Reason.VPN_FLAG,Check.POSITIVE,Check.KNOWN,1000,5,false,EnumSet.of(Flag.VPN,Flag.GEO),Collections.emptyList(),Collections.emptyList());}
    @Test void identicalRichRecipientsCombineWithMostRestrictivePrivacyAndCooldown(){
        Map<String,Object> settings=settings();settings.put("behavior.vpn.send-webhook.include-ip",true);settings.put("behavior.vpn.send-webhook.cooldown-ms",0);settings.put("behavior.geo.send-webhook.cooldown-ms",2000);
        List<CGWebHookHelper.Notification> plan=CGWebHookHelper.plan(event(Mode.ENFORCE),new WebhookSettings(settings::get));assertEquals(1,plan.size());
        assertFalse(plan.get(0).includeIp);assertEquals(2000,plan.get(0).cooldown);assertEquals(EnumSet.of(Scope.VPN,Scope.GEO),plan.get(0).scopes);
        assertThrows(UnsupportedOperationException.class,()->plan.get(0).scopes.clear());
    }
    @Test void separateRecipientsKeepTheirExplicitPrivacyChoices(){
        Map<String,Object> settings=settings();settings.put("behavior.vpn.send-webhook.include-ip",true);settings.put("behavior.geo.send-webhook.url","https://example.invalid/other");
        List<CGWebHookHelper.Notification> plan=CGWebHookHelper.plan(event(Mode.ENFORCE),new WebhookSettings(settings::get));assertEquals(2,plan.size());assertTrue(plan.get(0).includeIp);assertFalse(plan.get(1).includeIp);
    }
    @Test void mixedLegacyAndRichRecipientsDoNotGenerateARichDuplicateForLegacyScope(){
        Map<String,Object> settings=settings();settings.put("behavior.geo.send-webhook.format","TEXT");
        List<CGWebHookHelper.Notification> plan=CGWebHookHelper.plan(event(Mode.ENFORCE),new WebhookSettings(settings::get));assertEquals(1,plan.size());assertEquals(EnumSet.of(Scope.VPN),plan.get(0).scopes);
    }
    @Test void observationPlanningIsEmptyForAllRecipientAndPrivacyChoices(){assertTrue(CGWebHookHelper.plan(event(Mode.OBSERVE),new WebhookSettings(settings()::get)).isEmpty());}
    @Test void selectedAndUnresolvedTraceForTheSameRuleHasOneRenderedEntry(){
        List<Rule> rules=Arrays.asList(new Rule("strict",Scope.VPN,Effect.DENY,Match.UNKNOWN,true),new Rule("strict",Scope.VPN,Effect.DENY,Match.UNKNOWN,false));
        DecisionObservation event=new DecisionObservation(Platform.VELOCITY,Phase.LOGIN,Mode.ENFORCE,IdentityTrust.UNTRUSTED,null,"192.0.2.5",Outcome.DENY,Reason.ACCESS_RULE,Check.UNKNOWN,Check.EXEMPT,1000,5,false,EnumSet.of(Flag.ACCESS_POLICY),Collections.emptyList(),rules);
        JsonObject payload=JsonParser.parseString(new Gson().toJson(CGWebHookRequest.embedded(event,EnumSet.of(Scope.VPN),false,1))).getAsJsonObject();
        assertTrue(payload.toString().contains("Shown: 1/1 relevant rules"));assertTrue(payload.toString().contains("(selected)"));
    }
}
