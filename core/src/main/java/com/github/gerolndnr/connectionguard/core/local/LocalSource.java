package com.github.gerolndnr.connectionguard.core.local;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Public attribution is separate from the optional download endpoint. No endpoint appears in errors. */
public final class LocalSource {
    public enum Kind { VPN, PROXY, TOR, RELAY, HOSTING, GEO, ASN }
    public final String id, source, license, notice;
    public final Kind kind;
    public final long maxAgeMillis;
    private final String downloadUrl;
    public LocalSource(String id, Kind kind, String source, String license, String notice, int maxAgeHours, String downloadUrl) {
        if (id == null || !id.matches("[a-z][a-z0-9-]{0,31}") || kind == null || !text(source, 200) || !text(license, 100)
                || !text(notice, 200) || maxAgeHours < 1 || maxAgeHours > 87600) throw new IllegalArgumentException("Invalid local source/attribution/age settings (values redacted).");
        if (source.contains("://")) try {
            URI publicSource = URI.create(source);
            if (!"https".equals(publicSource.getScheme()) || publicSource.getHost() == null || publicSource.getUserInfo() != null
                    || publicSource.getRawQuery() != null || publicSource.getFragment() != null) throw new IllegalArgumentException();
        } catch (IllegalArgumentException invalid) { throw new IllegalArgumentException("Source attribution URL must be public HTTPS without credentials/query/fragment."); }
        if (downloadUrl != null && !downloadUrl.isEmpty()) {
            try {
                URI url = URI.create(downloadUrl);
                if (!"https".equals(url.getScheme()) || url.getHost() == null || url.getUserInfo() != null || url.getFragment() != null
                        || url.getRawQuery() != null || downloadUrl.length() > 1000 || kind == Kind.GEO || kind == Kind.ASN) throw new IllegalArgumentException();
            } catch (IllegalArgumentException invalid) { throw new IllegalArgumentException("List download URL must be public HTTPS without credentials/query/fragment; MMDB imports are operator-provided."); }
        }
        this.id = id; this.kind = kind; this.source = source; this.license = license; this.notice = notice;
        this.maxAgeMillis = maxAgeHours * 3600000L; this.downloadUrl = downloadUrl == null ? "" : downloadUrl;
    }
    public boolean isDatabase() { return kind == Kind.GEO || kind == Kind.ASN; }
    public boolean isVoting() { return !isDatabase() && kind != Kind.HOSTING; }
    public String downloadUrl() { return downloadUrl; }
    public String attributionFingerprint() { return hash((id + "\n" + kind + "\n" + source + "\n" + license + "\n" + notice).getBytes(StandardCharsets.UTF_8)); }
    public String fingerprint() { return hash((attributionFingerprint() + "\n" + maxAgeMillis + "\n" + downloadUrl).getBytes(StandardCharsets.UTF_8)); }
    static String hash(byte[] bytes) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder hex = new StringBuilder(); for (byte part : hash) hex.append(String.format("%02x", part & 255)); return hex.toString();
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 unavailable."); }
    }
    private static boolean text(String value, int max) { return value != null && !value.trim().isEmpty() && value.length() <= max
            && !value.chars().anyMatch(Character::isISOControl) && value.indexOf('\u00a7') < 0; }
}
