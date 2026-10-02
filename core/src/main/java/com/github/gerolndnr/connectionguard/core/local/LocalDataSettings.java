package com.github.gerolndnr.connectionguard.core.local;

import com.github.gerolndnr.connectionguard.core.config.GuardSettings;
import java.util.*;
import java.util.function.Function;

public final class LocalDataSettings {
    public final boolean vpnEnabled, geoEnabled;
    public final List<LocalSource> sources;
    public final int updateHours;
    public LocalDataSettings(Function<String, Object> value) {
        vpnEnabled = GuardSettings.bool(value, "provider.vpn.local.enabled", false);
        geoEnabled = GuardSettings.string(value, "provider.geo.service", "IP-API").equalsIgnoreCase("Local");
        updateHours = GuardSettings.integer(value, "provider.local.update-hours", 0);
        if (updateHours < 0 || updateHours > 168) throw new IllegalArgumentException("Local update-hours must be 0 (disabled) or 1..168.");
        List<LocalSource> checked = new ArrayList<>();
        Object raw = value.apply("provider.local.sources");
        if (raw != null) {
            if (!(raw instanceof List) || ((List<?>) raw).size() > 8) throw new IllegalArgumentException("Local sources must be a list of at most eight entries.");
            Set<String> ids = new HashSet<>(); int geo = 0, asn = 0;
            for (Object entry : (List<?>) raw) {
                if (!(entry instanceof Map)) throw new IllegalArgumentException("Invalid local source entry.");
                Map<?, ?> map = (Map<?, ?>) entry;
                for (Object key : map.keySet()) if (!Arrays.asList("id", "type", "source", "license", "notice", "max-age-hours", "download-url").contains(key)) throw new IllegalArgumentException("Unknown local source field.");
                Function<String, Object> fields = map::get;
                LocalSource.Kind kind;
                try { kind = LocalSource.Kind.valueOf(GuardSettings.string(fields, "type", "").toUpperCase(Locale.ROOT)); }
                catch (IllegalArgumentException invalid) { throw new IllegalArgumentException("Local source type must be VPN, PROXY, TOR, RELAY, HOSTING, GEO or ASN."); }
                LocalSource source = new LocalSource(GuardSettings.string(fields, "id", ""), kind,
                        GuardSettings.string(fields, "source", ""), GuardSettings.string(fields, "license", ""), GuardSettings.string(fields, "notice", ""),
                        GuardSettings.integer(fields, "max-age-hours", 168), GuardSettings.string(fields, "download-url", ""));
                if (!ids.add(source.id) || (kind == LocalSource.Kind.GEO && ++geo > 1) || (kind == LocalSource.Kind.ASN && ++asn > 1)) throw new IllegalArgumentException("Local source IDs must be unique; at most one GEO and one ASN source.");
                checked.add(source);
            }
        }
        if ((vpnEnabled || geoEnabled) && checked.isEmpty()) throw new IllegalArgumentException("Enabled local data requires explicitly attributed sources.");
        if (geoEnabled && checked.stream().noneMatch(source -> source.kind == LocalSource.Kind.GEO)) throw new IllegalArgumentException("Local geo service requires a GEO source.");
        sources = Collections.unmodifiableList(checked);
    }
    public int votingProviders() { return vpnEnabled ? (int) sources.stream().filter(LocalSource::isVoting).count() : 0; }
}
