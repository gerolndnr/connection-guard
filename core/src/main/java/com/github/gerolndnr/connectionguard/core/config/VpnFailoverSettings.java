package com.github.gerolndnr.connectionguard.core.config;

import java.util.*;
import java.util.function.Function;

/** General source selection, independent of provider credentials or subscription. */
public final class VpnFailoverSettings {
    public final boolean enabled;
    public final int maxExternalAttempts;
    public final List<String> order;

    public VpnFailoverSettings(Function<String, Object> value, List<String> providerKeys,
                              com.github.gerolndnr.connectionguard.core.extensions.ExtensionSettings extensions) {
        String legacy = GuardSettings.string(value, "provider.vpn-strategy", "FAILOVER");
        if (!legacy.equalsIgnoreCase("CONSENSUS") && !legacy.equalsIgnoreCase("FAILOVER"))
            throw new IllegalArgumentException("provider.vpn-strategy must be CONSENSUS or FAILOVER.");
        enabled = GuardSettings.bool(value, "provider.vpn-failover.enabled", legacy.equalsIgnoreCase("FAILOVER"));
        maxExternalAttempts = GuardSettings.integer(value, "provider.max-external-attempts", 16);
        if (maxExternalAttempts < 1 || maxExternalAttempts > 16)
            throw new IllegalArgumentException("provider.max-external-attempts must be 1..16.");
        if (providerKeys.stream().anyMatch(id -> id == null || !id.matches("[a-zA-Z0-9_.-]{1,64}")))
            throw new IllegalArgumentException("Invalid provider configuration ID (value redacted).");
        Set<String> available = new HashSet<>(providerKeys);
        available.remove("local");
        for (com.github.gerolndnr.connectionguard.core.extensions.ExtensionSettings.Source source : extensions.sources)
            available.add("extension." + source.id);
        Object raw = value.apply("provider.vpn-failover.order");
        List<String> selected = new ArrayList<>();
        if (raw != null) {
            if (!(raw instanceof List) || ((List<?>) raw).size() > 16)
                throw new IllegalArgumentException("provider.vpn-failover.order must be a list of at most 16 provider IDs.");
            for (Object item : (List<?>) raw) {
                if (!(item instanceof String) || !((String) item).matches("[a-zA-Z0-9_.-]{1,80}")
                        || !available.contains(item) || selected.contains(item))
                    throw new IllegalArgumentException("Invalid, unknown or duplicate failover provider ID (value redacted).");
                selected.add((String) item);
            }
        }
        order = Collections.unmodifiableList(selected);
    }

    /** Local observations precede APIs; IP-API remains the last network option. */
    public int rank(String id, boolean local) {
        if (local) return -1;
        if (id.equals("ip-api")) return Integer.MAX_VALUE;
        int selected = order.indexOf(id);
        if (selected >= 0) return selected;
        return order.size() + (id.equals("proxycheck") ? 0 : id.equals("ipquery") ? 1 : 2);
    }
}
