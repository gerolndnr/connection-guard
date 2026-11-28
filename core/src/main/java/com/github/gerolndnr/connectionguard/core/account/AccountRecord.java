package com.github.gerolndnr.connectionguard.core.account;

import java.util.*;

/** One provider's answer for one account: counts and the bans in force, as the provider's lists publish them. */
public final class AccountRecord {
    public static final int SHOWN_BANS = 5;
    public final String source;
    public final UUID uuid;
    public final int bansTotal, bansInForce, listsInForce;
    public final Set<String> categoriesInForce;
    /** Up to {@link #SHOWN_BANS} bans in force, newest first. Empty when the provider withholds the record. */
    public final List<Ban> activeBans;
    public final String profileUrl;
    public final long checkedAt;

    public AccountRecord(String source, UUID uuid, int bansTotal, int bansInForce, int listsInForce, Collection<String> categoriesInForce,
                         List<Ban> activeBans, String profileUrl, long checkedAt) {
        this.source = Objects.requireNonNull(source); this.uuid = Objects.requireNonNull(uuid);
        if (bansTotal < 0 || bansInForce < 0 || listsInForce < 0 || bansInForce > bansTotal) throw new IllegalArgumentException("Invalid ban counts.");
        this.bansTotal = bansTotal; this.bansInForce = bansInForce; this.listsInForce = listsInForce;
        this.categoriesInForce = Collections.unmodifiableSet(new LinkedHashSet<>(categoriesInForce));
        this.activeBans = Collections.unmodifiableList(new ArrayList<>(activeBans.subList(0, Math.min(SHOWN_BANS, activeBans.size()))));
        this.profileUrl = profileUrl; this.checkedAt = checkedAt;
    }

    /** List names of the bans in force, each once, in order. */
    public List<String> lists() {
        Set<String> names = new LinkedHashSet<>();
        for (Ban ban : activeBans) names.add(ban.list);
        return new ArrayList<>(names);
    }

    public static final class Ban {
        public final String list;
        public final Set<String> categories;
        public final String reason;
        public final Long createdAt, expiresAt;
        public Ban(String list, Collection<String> categories, String reason, Long createdAt, Long expiresAt) {
            this.list = Objects.requireNonNull(list); this.categories = Collections.unmodifiableSet(new LinkedHashSet<>(categories));
            this.reason = reason; this.createdAt = createdAt; this.expiresAt = expiresAt;
        }
    }
}
