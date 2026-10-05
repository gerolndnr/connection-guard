package com.github.gerolndnr.connectionguard.core.http;

import com.github.gerolndnr.connectionguard.core.identity.Exemptions;
import com.google.gson.*;
import java.util.Map;

/** RFC 5952 spelling for transport; cache identity continues to use Exemptions.normalize. */
public final class ProviderAddresses {
    private ProviderAddresses() { }
    public static String compressed(String literal) {
        String normalized = Exemptions.normalize(literal);
        if (normalized.indexOf(':') < 0) return normalized;
        String[] words = normalized.split(":", -1);
        int best = -1, length = 0;
        for (int i = 0; i < words.length;) {
            if (!words[i].equals("0")) { i++; continue; }
            int start = i; while (i < words.length && words[i].equals("0")) i++;
            if (i - start > length && i - start >= 2) { best = start; length = i - start; }
        }
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < words.length;) {
            if (i == best) { out.append("::"); i += length; continue; }
            if (out.length() > 0 && out.charAt(out.length() - 1) != ':') out.append(':');
            out.append(words[i++]);
        }
        return out.toString();
    }
    /** Providers may echo compressed or expanded IPv6. Accept one equivalent literal only. */
    public static JsonObject response(JsonObject json, String ip) {
        String normalized = Exemptions.normalize(ip);
        JsonObject answer = null;
        for (Map.Entry<String, JsonElement> entry : json.entrySet()) {
            String candidate;
            try { candidate = Exemptions.normalize(entry.getKey()); }
            catch (IllegalArgumentException metadata) { continue; }
            if (!candidate.equals(normalized)) continue;
            if (answer != null || !entry.getValue().isJsonObject()) throw new IllegalArgumentException("Ambiguous response address.");
            answer = entry.getValue().getAsJsonObject();
        }
        if (answer == null) throw new IllegalArgumentException("Missing requested address.");
        return answer;
    }
}
