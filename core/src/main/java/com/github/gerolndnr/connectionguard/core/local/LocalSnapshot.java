package com.github.gerolndnr.connectionguard.core.local;

import com.github.gerolndnr.connectionguard.core.lookup.*;
import com.github.gerolndnr.connectionguard.core.geo.GeoResult;
import com.github.gerolndnr.connectionguard.core.vpn.VpnResult;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.util.*;

/** A complete immutable generation. Absent list membership is not negative evidence. */
public final class LocalSnapshot {
    public final LocalSource source;
    public final String version, timeBasis;
    public final long dataTime, fetchedAt;
    private final NetworkIndex index;
    private final BoundedMmdb database;
    private LocalSnapshot(LocalSource source, String version, String timeBasis, long dataTime, long fetchedAt,
                          NetworkIndex index, BoundedMmdb database) {
        this.source = source; this.version = version; this.timeBasis = timeBasis; this.dataTime = dataTime;
        this.fetchedAt = fetchedAt; this.index = index; this.database = database;
    }
    static LocalSnapshot missing(LocalSource source) { return new LocalSnapshot(source, "missing", "UNKNOWN", 0, 0, null, null); }
    static LocalSnapshot parse(LocalSource source, byte[] bytes, long asOf, long fetchedAt, String timeBasis, long now) {
        if (bytes.length == 0 || bytes.length > (source.isDatabase() ? BoundedMmdb.MAX_BYTES : 4 * 1024 * 1024)) throw new IllegalArgumentException("Local data size exceeds its bound.");
        String version = LocalSource.hash(bytes);
        if (source.isDatabase()) {
            BoundedMmdb database = new BoundedMmdb(bytes);
            String type = database.getDatabaseType();
            if (!(source.kind == LocalSource.Kind.ASN ? type.matches(".*-ASN(?:-Lite)?") : type.matches(".*-(?:Country|City)(?:-Lite)?"))) throw new IllegalArgumentException("MMDB type does not match the configured source.");
            asOf = database.getBuildTime(); timeBasis = "MMDB_BUILD";
            dates(asOf, fetchedAt, now);
            return new LocalSnapshot(source, version, timeBasis, asOf, fetchedAt, null, database);
        }
        dates(asOf, fetchedAt, now);
        if (!Arrays.asList("IMPORT_AS_OF", "HTTP_LAST_MODIFIED", "FETCH").contains(timeBasis)) throw new IllegalArgumentException("Invalid list time basis.");
        final String text;
        try { text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString(); }
        catch (CharacterCodingException invalid) { throw new IllegalArgumentException("List must be valid UTF-8."); }
        List<String> networks = new ArrayList<>();
        int start = 0, lines = 0;
        for (int i = 0; i <= text.length(); i++) {
            if (i - start > 257) throw new IllegalArgumentException("Local list line exceeds 256 characters.");
            if (i < text.length() && text.charAt(i) != '\n') continue;
            if (++lines > 200000) throw new IllegalArgumentException("Local list physical line count exceeds its bound.");
            int end = i > start && text.charAt(i - 1) == '\r' ? i - 1 : i;
            if (end - start > 256) throw new IllegalArgumentException("Local list line exceeds 256 characters.");
            String entry = text.substring(start, end).trim(); start = i + 1;
            if (!entry.isEmpty() && !entry.startsWith("#")) {
                if (networks.size() >= NetworkIndex.MAX_RECORDS) throw new IllegalArgumentException("Local list record count exceeds its bound.");
                networks.add(entry);
            }
        }
        if (networks.isEmpty()) throw new IllegalArgumentException("Empty local list cannot replace valid data.");
        return new LocalSnapshot(source, version, timeBasis, asOf, fetchedAt, new NetworkIndex(networks), null);
    }
    private static void dates(long dataTime, long fetchedAt, long now) {
        if (dataTime <= 0 || fetchedAt <= 0 || dataTime > now + 300000 || fetchedAt > now + 300000) throw new IllegalArgumentException("Invalid local data date (values redacted).");
    }
    public long validUntil() { return dataTime == 0 ? 0 : dataTime + source.maxAgeMillis; }
    public FailureReason readiness(long now) { return dataTime == 0 ? FailureReason.NO_EVIDENCE : now >= validUntil() ? FailureReason.STALE_DATA : FailureReason.NONE; }
    public VpnResult vpn(String ip, long now) {
        VpnResult result = new VpnResult(ip, false);
        FailureReason reason = readiness(now);
        DetectionDetails details = DetectionDetails.empty();
        boolean positive = false;
        if (reason == FailureReason.NONE) {
            if (database != null) details = details(ip);
            else if (index.contains(ip)) {
                DetectionDetails.Type type = DetectionDetails.Type.valueOf(source.kind.name());
                details = new DetectionDetails(Collections.singletonMap(type, true), null, null, null, null, null, null);
                positive = source.isVoting();
            }
            reason = positive ? FailureReason.NONE : FailureReason.NO_EVIDENCE;
        }
        result.setDetails(details);
        if (positive) result.setStatus(ProviderVote.Status.POSITIVE); else result.setUnknown(reason);
        result.setSourceVersion(version.equals("missing") ? null : version);
        result.setValidUntil(readiness(now) == FailureReason.NONE ? validUntil() : 0);
        return result;
    }
    public Optional<GeoResult> geo(String ip, long now) {
        if (source.kind != LocalSource.Kind.GEO || readiness(now) != FailureReason.NONE) return Optional.empty();
        Map<String, Object> fields = database.lookup(ip);
        String country = country(fields);
        if (country == null) return Optional.empty();
        String city = nestedText(fields, "city", "names", "en");
        GeoResult result = new GeoResult(ip, country, city == null ? "Unknown" : city, "Unknown");
        result.setValidUntil(validUntil()); result.setSourceVersion(version);
        return Optional.of(result);
    }
    private DetectionDetails details(String ip) {
        Map<String, Object> fields = database.lookup(ip);
        Long asn = fields.containsKey("autonomous_system_number") ? BoundedMmdb.number(fields.get("autonomous_system_number")) : null;
        String isp = text(fields.get("autonomous_system_organization"));
        return new DetectionDetails(null, asn, isp, null, country(fields), null, null);
    }
    private static String country(Map<?, ?> fields) {
        String country = nestedText(fields, "country", "iso_code");
        if (country != null && !country.matches("[A-Z]{2}")) throw new IllegalArgumentException("Invalid local country field.");
        return country;
    }
    private static String nestedText(Map<?, ?> fields, String... keys) {
        Object next = fields;
        for (String key : keys) { if (!(next instanceof Map)) return null; next = ((Map<?, ?>) next).get(key); if (next == null) return null; }
        return text(next);
    }
    private static String text(Object value) {
        if (value == null) return null;
        if (!(value instanceof String)) throw new IllegalArgumentException("Invalid local text field.");
        return (String) value;
    }
    public String describe(long now) {
        return "local." + source.id + " type=" + source.kind + " state=" + readiness(now) + " version=" + version
                + " dataAgeMs=" + (dataTime == 0 ? "UNKNOWN" : Math.max(0, now - dataTime)) + " timeBasis=" + timeBasis
                + " fetchAgeMs=" + (fetchedAt == 0 ? "UNKNOWN" : Math.max(0, now - fetchedAt))
                + (index == null ? database == null ? " coverage=UNKNOWN" : " database=" + database.getDatabaseType() + " ipVersion=" + database.getIpVersion()
                : " records=" + index.getRecords() + " ipv4Intervals=" + index.getIpv4Intervals() + " ipv6Intervals=" + index.getIpv6Intervals())
                + " source=" + source.source + " license=" + source.license + " notice=" + source.notice;
    }
}
