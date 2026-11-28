package com.github.gerolndnr.connectionguard.core.account;

import java.util.UUID;

/** A source of public ban records for an account. Called on Account Intelligence's own workers, never on a login path. */
public interface AccountProvider {
    String id();
    /** The account's record; throws {@link com.github.gerolndnr.connectionguard.core.lookup.LookupException} on failure. */
    AccountRecord check(UUID uuid);
}
