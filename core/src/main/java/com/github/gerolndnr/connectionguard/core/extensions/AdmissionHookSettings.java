package com.github.gerolndnr.connectionguard.core.extensions;
import com.github.gerolndnr.connectionguard.core.config.GuardSettings;
import java.util.*;import java.util.function.Function;
/** Explicit independent selection; no detector cache or automatic native dependency loading. */
public final class AdmissionHookSettings {
    public final List<String> ids; public final boolean closed;
    public AdmissionHookSettings(Function<String,Object> value){
        boolean enabled=GuardSettings.bool(value,"integrations.admission.enabled",false);
        Object raw=value.apply("integrations.admission.ids");List<String> selected=new ArrayList<>();Set<String> seen=new HashSet<>();
        if(raw!=null){
            if(!(raw instanceof List) || ((List<?>)raw).size()>4)throw new IllegalArgumentException("Admission IDs must be a list of at most four IDs.");
            for(Object item:(List<?>)raw){
                if(!(item instanceof String) || !((String)item).matches("[a-z][a-z0-9-]{0,31}") || !seen.add((String)item))
                    throw new IllegalArgumentException("Invalid or duplicate admission ID (values redacted).");
                if(enabled)selected.add((String)item);
            }
        }
        if(enabled && selected.isEmpty())throw new IllegalArgumentException("Enabled admission checks require explicit selected IDs.");
        Object policy=value.apply("integrations.admission.failure-policy");
        String failure=policy==null?"OPEN":policy instanceof String?((String)policy).toUpperCase(Locale.ROOT):"";
        if(!failure.equals("OPEN") && !failure.equals("CLOSED"))throw new IllegalArgumentException("Admission failure policy must be OPEN or CLOSED (value redacted).");
        ids=Collections.unmodifiableList(selected);closed=failure.equals("CLOSED");
    }
}
