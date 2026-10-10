package com.github.gerolndnr.connectionguard.core.webhook;

import com.github.gerolndnr.connectionguard.core.config.GuardSettings;
import okhttp3.HttpUrl;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.Function;

/** Complete immutable notification draft; endpoints/credentials never enter observation payloads. */
public final class WebhookSettings {
    public enum Format { TEXT, EMBED }
    public enum Event { FLAG, DENY, ERROR, UNKNOWN }
    public final Destination vpn, geo;
    private final String fingerprint;
    public WebhookSettings(Function<String,Object> values) {
        vpn = new Destination(values, "vpn"); geo = new Destination(values, "geo");
        String mode = GuardSettings.string(values, "operation.mode", "ENFORCE").toUpperCase(Locale.ROOT);
        fingerprint = digest(mode + "\n" + vpn.signature() + "\n" + geo.signature());
    }
    public boolean hasEmbeds() { return vpn.enabled && vpn.format == Format.EMBED || geo.enabled && geo.format == Format.EMBED; }
    public boolean hasText() { return vpn.isLegacyText() || geo.isLegacyText(); }
    public String fingerprint() { return fingerprint; }
    static String digest(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(); for (byte part : bytes) result.append(String.format("%02x", part & 255)); return result.toString();
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 unavailable."); }
    }
    public static final class Destination {
        public final boolean enabled, includeIp;
        public final Format format;
        public final Set<Event> events;
        public final int cooldownMillis;
        private final String url;
        private Destination(Function<String,Object> values, String scope) {
            String path = "behavior." + scope + ".send-webhook.";
            enabled = GuardSettings.bool(values,path+"enabled",false);
            try { format = Format.valueOf(GuardSettings.string(values,path+"format","TEXT").toUpperCase(Locale.ROOT)); }
            catch (IllegalArgumentException invalid) { throw new IllegalArgumentException(path+"format must be TEXT or EMBED (value redacted)."); }
            includeIp = GuardSettings.bool(values,path+"include-ip",false);
            cooldownMillis = GuardSettings.integer(values,path+"cooldown-ms",1000);
            if (cooldownMillis < 0 || cooldownMillis > 60000) throw new IllegalArgumentException("Webhook cooldown must be 0..60000 ms.");
            Object raw = values.apply(path+"events"); EnumSet<Event> selected = EnumSet.noneOf(Event.class);
            if (raw == null) selected.addAll(Arrays.asList(Event.FLAG,Event.DENY,Event.ERROR));
            else {
                if (!(raw instanceof List) || ((List<?>) raw).isEmpty() || ((List<?>) raw).size()>4) throw new IllegalArgumentException("Webhook events require 1..4 unique supported events.");
                for (Object item : (List<?>) raw) {
                    if (!(item instanceof String)) throw new IllegalArgumentException("Webhook event must be text (value redacted).");
                    try { if (!selected.add(Event.valueOf(((String)item).toUpperCase(Locale.ROOT)))) throw new IllegalArgumentException(); }
                    catch (IllegalArgumentException invalid) { throw new IllegalArgumentException("Invalid or duplicate webhook event (values redacted)."); }
                }
            }
            events = Collections.unmodifiableSet(selected);
            url = enabled ? GuardSettings.string(values,path+"url","") : "";
            if (enabled) {
                HttpUrl parsed = url.length()<=2048 ? HttpUrl.parse(url) : null;
                if (parsed == null || !parsed.isHttps() || !parsed.username().isEmpty() || !parsed.password().isEmpty()
                        || parsed.fragment()!=null || url.chars().anyMatch(Character::isISOControl) || !url.equals(url.trim()))
                    throw new IllegalArgumentException("Enabled webhook requires a valid HTTPS endpoint without userinfo/fragment (value redacted).");
            }
        }
        String endpoint() { return url; }
        private String signature() { return enabled+":"+format+":"+includeIp+":"+cooldownMillis+":"+events+":"+url; }
        public boolean isLegacyText() { return enabled && format == Format.TEXT; }
    }
}
