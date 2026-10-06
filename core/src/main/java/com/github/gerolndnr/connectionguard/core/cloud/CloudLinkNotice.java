package com.github.gerolndnr.connectionguard.core.cloud;

import com.github.gerolndnr.connectionguard.core.ConnectionGuard;
import com.github.gerolndnr.connectionguard.core.messages.MessageCatalog;
import java.util.*;

/** Presentation-only snapshot. URLs are validated by CloudSync; no network on a join. */
public final class CloudLinkNotice {
    public static final String PERMISSION = "connectionguard.command.cloud";
    private static final String DASHBOARD = "https://app.connectionguard.net";
    final Object owner;
    final String rawUrl;
    public final String url, title, description, action, hover, help, optional;
    CloudLinkNotice(Object owner, String rawUrl, String source) {
        this.owner = owner; this.rawUrl = rawUrl;
        MessageCatalog messages = ConnectionGuard.getMessages();
        url = sourced(rawUrl == null ? DASHBOARD : rawUrl, source);
        title = messages.getString("cloud.notice-title");
        description = messages.getString("cloud.notice-description");
        action = messages.getString("cloud.notice-action");
        hover = messages.getString("cloud.notice-hover");
        help = messages.getString(rawUrl == null ? "cloud.notice-registering" : "cloud.notice-link-help");
        optional = messages.getString("cloud.notice-optional");
    }
    public List<String> consoleLines() {
        return Arrays.asList("============================================================", title, description,
                action, url, help, optional, "============================================================");
    }
    /** Current API link has no query/fragment; support either safely for future presentation URLs. */
    static String sourced(String url, String source) {
        if (!Arrays.asList("console", "join", "command").contains(source)) throw new IllegalArgumentException("Invalid link source.");
        int fragment = url.indexOf('#');
        String head = fragment < 0 ? url : url.substring(0, fragment);
        return head + (head.contains("?") ? "&" : "?") + "src=" + source + (fragment < 0 ? "" : url.substring(fragment));
    }
}
