package com.github.gerolndnr.connectionguard.core.lookup;

/** Stable, secret-free failure codes. UNKNOWN is never a negative vote. */
public enum FailureReason {
    NONE, TIMEOUT, RATE_LIMIT, HTTP_ERROR, AUTHENTICATION, INVALID_RESPONSE,
    NETWORK, OVERLOADED, CIRCUIT_OPEN, BUDGET_EXHAUSTED, NO_PROVIDER, CACHE_ERROR, CANCELLED
}
