package com.github.gerolndnr.connectionguard.core.webhook;

import com.github.gerolndnr.connectionguard.api.v1.*;
import com.github.gerolndnr.connectionguard.api.v1.DecisionObservation.*;
import java.time.Instant;
import java.util.*;
import java.util.regex.*;

/** Literal one-pass substitution using captured facts, never additional lookups. */
public final class WebhookTemplate {
    private static final Pattern VARIABLES=Pattern.compile("%(NAME|UUID|IS_VPN|IP|LOCATION|COUNTRY|CITY|ISP|TIME)%");
    private WebhookTemplate() {}
    public static String render(String template,DecisionObservation event,WebhookContext context) {
        Map<String,String> values=new HashMap<>();
        values.put("NAME",known(context.playerName));values.put("IP",event.getIp());
        values.put("UUID",event.getReason()==Reason.IDENTITY_UNAVAILABLE ? "UNKNOWN" : event.getTrustedUuid().map(UUID::toString).orElse("UNKNOWN"));
        values.put("IS_VPN",event.getVpnCheck()==Check.POSITIVE ? "true" : event.getVpnCheck()==Check.NEGATIVE ? "false" : "UNKNOWN");
        String country=context.country;
        if(country==null) {
            Set<String> countries=new HashSet<>();
            for(Source source:event.getSources()) if(source.getScope()==Scope.VPN
                    && source.getObservation().getStatus()!=DetectionObservation.Status.UNKNOWN
                    && source.getObservation().getMetadata().getCountry()!=null)
                countries.add(source.getObservation().getMetadata().getCountry());
            if(countries.size()==1)country=countries.iterator().next();
        }
        values.put("COUNTRY",known(country));values.put("CITY",known(context.city));values.put("ISP",known(context.isp));
        values.put("LOCATION",country==null ? "UNKNOWN" : context.city==null ? country : context.city+", "+country);
        values.put("TIME",event.getObservedAt()<=253402300799999L ? Instant.ofEpochMilli(event.getObservedAt()).toString() : "UNKNOWN");
        Matcher matcher=VARIABLES.matcher(template);StringBuffer result=new StringBuffer();
        while(matcher.find())matcher.appendReplacement(result,Matcher.quoteReplacement(values.get(matcher.group(1))));
        matcher.appendTail(result);return result.toString();
    }
    private static String known(String value) { return value==null ? "UNKNOWN" : value; }
}
