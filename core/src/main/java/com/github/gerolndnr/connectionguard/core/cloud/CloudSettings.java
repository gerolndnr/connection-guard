package com.github.gerolndnr.connectionguard.core.cloud;

import com.github.gerolndnr.connectionguard.core.config.GuardSettings;
import java.net.URI;
import java.util.Locale;
import java.util.function.Function;

/**
 * Connection Guard Cloud settings. The cloud is on by default and can be turned off by config,
 * by command (persisted marker) or by the environment for hosts and CI.
 */
public final class CloudSettings {
    public static final String DEFAULT_ENDPOINT = "https://api.connectionguard.net";
    public static final String ENV_SWITCH = "CONNECTIONGUARD_CLOUD";
    public static final String PROPERTY_SWITCH = "connectionguard.cloud";

    public final boolean enabled;
    public final boolean errorReports;
    /** Why the cloud is off, or null when enabled. */
    public final String disabledBy;
    public final URI endpoint;
    public final String networkToken;

    private CloudSettings(boolean enabled, String disabledBy, URI endpoint, String networkToken, boolean reports) {
        this.enabled = enabled; this.errorReports = enabled && reports; this.disabledBy = disabledBy; this.endpoint = endpoint; this.networkToken = networkToken;
    }

    public static CloudSettings read(Function<String, Object> value, Function<String, String> environment, boolean disabledByCommand) {
        URI endpoint = endpoint(GuardSettings.string(value, "cloud.endpoint", DEFAULT_ENDPOINT));
        String token = GuardSettings.string(value, "cloud.network-token", "").trim();
        if (!token.isEmpty() && !token.matches("cgn_[A-Za-z0-9_-]{32,64}")) throw new IllegalArgumentException("cloud.network-token is malformed (value redacted).");
        boolean reports = GuardSettings.bool(value, "cloud.error-reports", true);
        String env = environment.apply(ENV_SWITCH);
        String property = environment.apply(PROPERTY_SWITCH);
        if (isOff(env)) return new CloudSettings(false, "environment variable " + ENV_SWITCH, endpoint, token, reports);
        if (isOff(property)) return new CloudSettings(false, "system property " + PROPERTY_SWITCH, endpoint, token, reports);
        if (!GuardSettings.bool(value, "cloud.enabled", true)) return new CloudSettings(false, "cloud.enabled: false", endpoint, token, reports);
        if (disabledByCommand) return new CloudSettings(false, "/cg cloud disable", endpoint, token, reports);
        return new CloudSettings(true, null, endpoint, token, reports);
    }

    public static CloudSettings read(Function<String, Object> value, boolean disabledByCommand) {
        return read(value, key -> key.equals(ENV_SWITCH) ? System.getenv(key) : System.getProperty(key), disabledByCommand);
    }

    private static boolean isOff(String raw) {
        if (raw == null) return false;
        String v = raw.trim().toLowerCase(Locale.ROOT);
        return v.equals("false") || v.equals("0") || v.equals("off") || v.equals("no") || v.equals("disabled");
    }

    /** HTTPS only; plain HTTP is accepted solely for a loopback self-hosted or development backend. */
    static URI endpoint(String raw) {
        final URI uri;
        try { uri = new URI(raw.trim().replaceAll("/+$", "")); }
        catch (Exception invalid) { throw new IllegalArgumentException("cloud.endpoint must be an https:// URL (value redacted)."); }
        String host = uri.getHost();
        if (host == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null)
            throw new IllegalArgumentException("cloud.endpoint must be an https:// URL without credentials or query (value redacted).");
        boolean loopback = host.equals("localhost") || host.equals("127.0.0.1") || host.equals("[::1]") || host.equals("::1");
        if (!"https".equalsIgnoreCase(uri.getScheme()) && !("http".equalsIgnoreCase(uri.getScheme()) && loopback))
            throw new IllegalArgumentException("cloud.endpoint must use https:// (http:// only for localhost).");
        return uri;
    }
}
