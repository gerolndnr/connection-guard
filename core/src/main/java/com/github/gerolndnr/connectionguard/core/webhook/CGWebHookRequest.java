package com.github.gerolndnr.connectionguard.core.webhook;

import com.github.gerolndnr.connectionguard.api.v1.*;
import com.github.gerolndnr.connectionguard.api.v1.DecisionObservation.*;
import java.util.*;
import java.time.Instant;

/** Bounded Discord payload. Rich mode permits typed public facts, never arbitrary provider text. */
public class CGWebHookRequest {
    private final String content;
    private final List<Embed> embeds;
    private final AllowedMentions allowed_mentions = new AllowedMentions();
    public CGWebHookRequest(String content) {
        this.content = safeText(content,2000); this.embeds = null;
        if (this.content.isEmpty()) throw new IllegalArgumentException("Empty webhook message.");
    }
    private CGWebHookRequest(Embed embed) { content=null; embeds=Collections.singletonList(embed); }
    public String getContent() { return content; }
    static CGWebHookRequest embedded(DecisionObservation event, Set<Scope> scopes, boolean includeIp, int threshold) {
        return embedded(event, scopes, includeIp, threshold, com.github.gerolndnr.connectionguard.core.messages.MessageCatalog.defaults("en"));
    }
    static CGWebHookRequest embedded(DecisionObservation event, Set<Scope> scopes, boolean includeIp, int threshold,
                                           com.github.gerolndnr.connectionguard.core.messages.MessageCatalog messages) {
        if (threshold<1 || threshold>16) throw new IllegalArgumentException("Invalid captured VPN minimum.");
        String title = messages.getString(event.getOutcome()==Outcome.DENY ? "webhook.title-deny" : event.getOutcome()==Outcome.ERROR ? "webhook.title-error" : "webhook.title-allow");
        int color=event.getOutcome()==Outcome.DENY ? 0xD94B4B : event.getOutcome()==Outcome.ERROR ? 0xE3A328 : 0x3886C7;
        Embed embed=new Embed(title,messages.getString("webhook.description"),color,event.getObservedAt());
        embed.add(messages.getString("webhook.field.decision"), messages.text("webhook.decision",event.getOutcome(),event.getReason(),event.getPlatform(),event.getPhase(),event.getMode(),event.hasProcessingError(),scopes));
        embed.add(messages.getString("webhook.field.checks"), messages.text("webhook.checks",event.getVpnCheck(),threshold,event.getGeoCheck(),event.getIdentityTrust(),event.getDurationMillis()));
        if (includeIp) embed.add(messages.getString("webhook.field.address"),event.getIp());
        int positive=0,negative=0,unknown=0;
        for(Source source:event.getSources()) if(source.isVoting()) {
            switch(source.getObservation().getStatus()) { case POSITIVE:positive++;break;case NEGATIVE:negative++;break;case UNKNOWN:unknown++;break;default:break; }
        }
        embed.add(messages.getString("webhook.field.votes"), messages.text("webhook.votes",positive,negative,unknown,event.getFlags()));
        Map<String,Rule> byRule=new LinkedHashMap<>();
        for(Rule rule:event.getRules()) if(rule.isSelected() || rule.getMatch()==Match.UNKNOWN || rule.getMatch()==Match.CONFLICT) {
            String key=rule.getEvaluatedScope()+":"+rule.getId();
            if(!byRule.containsKey(key) || rule.isSelected())byRule.put(key,rule);
        }
        List<Rule> relevant=new ArrayList<>(byRule.values());
        relevant.sort(Comparator.comparing(Rule::isSelected).reversed());
        StringBuilder rules=new StringBuilder(); int shownRules=0;
        for(Rule rule:relevant) {
            String line=rule.getId()+": "+rule.getEffect()+" / "+rule.getEvaluatedScope()+" / "+rule.getMatch()+(rule.isSelected()?messages.getString("webhook.selected"):"")+"\n";
            if(shownRules==8 || rules.length()+line.length()>900) break;
            rules.append(line); shownRules++;
        }
        if(!relevant.isEmpty()) embed.add(messages.getString("webhook.field.rules"),rules.toString()+messages.text("webhook.rules-shown",shownRules,relevant.size()));
        if(!event.getAdmissionChecks().isEmpty()) {
            StringBuilder admission=new StringBuilder();
            for(AdmissionObservation check:event.getAdmissionChecks()) admission.append(check.getId()).append(": ").append(check.getResponse().getStatus())
                .append(" / ").append(check.getResponse().getReason()).append(" / ").append(check.getDurationMillis()).append(" ms\n");
            embed.add(messages.getString("webhook.field.admission"),admission.toString());
        }
        int shownSources=0;
        for(Source source:event.getSources()) {
            if(shownSources==8) break;
            DetectionObservation observation=source.getObservation(); DetectionMetadata details=observation.getMetadata();
            String value=messages.text("webhook.source",observation.getStatus(),observation.getReason(),source.isVoting(),source.isFromCache(),source.getDurationMillis(),
                    details.getTypes().isEmpty()?"UNKNOWN":details.getTypes(),known(details.getExactRisk()),known(details.getConfidence()),known(details.getAsn()),known(details.getCountry()),
                    observation.getValidUntil()==0?messages.getString("webhook.not-supplied"):Long.toString(observation.getValidUntil()));
            if(!embed.add(messages.text("webhook.source-label",source.getScope(),source.getId()),value)) break;
            shownSources++;
        }
        embed.footer=new Footer(messages.text("webhook.footer",shownSources,event.getSources().size(),
                messages.getString(includeIp?"webhook.address-included":"webhook.address-redacted")));

        return new CGWebHookRequest(embed);
    }
    private static String known(Object value) { return value==null?"UNKNOWN":value.toString(); }
    static String safeText(String raw,int limit) {
        if(raw==null) return ""; StringBuilder clean=new StringBuilder();
        for(int offset=0;offset<raw.length() && clean.length()<limit;) {
            int point=raw.codePointAt(offset); offset+=Character.charCount(point);
            if(point>=0xD800 && point<=0xDFFF || Character.getType(point)==Character.FORMAT
                    || Character.isISOControl(point) && point!='\n' && point!='\t') continue;
            if(clean.length()+Character.charCount(point)>limit) break;
            clean.appendCodePoint(point);
        }
        return clean.toString();
    }
    private static final class AllowedMentions { private final List<String> parse=Collections.emptyList(); }
    private static final class Embed {
        private final String title,description,timestamp;
        private final int color;
        private final List<Field> fields=new ArrayList<>();
        private Footer footer;
        private transient int characters;
        private Embed(String title,String description,int color,long observedAt) {
            this.title=title;this.description=description;this.color=color;
            timestamp=observedAt<=253402300799999L?Instant.ofEpochMilli(observedAt).toString():null;
            characters=title.length()+description.length();
        }
        private boolean add(String name,String value) {
            if(name.isEmpty() || value.isEmpty() || name.length()>256 || value.length()>1024
                    || fields.size()>=25 || characters+name.length()+value.length()>5700) return false;
            fields.add(new Field(name,value));characters+=name.length()+value.length();return true;
        }
    }
    private static final class Field { private final String name,value;private final boolean inline=false;private Field(String name,String value){this.name=name;this.value=value;} }
    private static final class Footer { private final String text;private Footer(String text){this.text=text;} }
}
