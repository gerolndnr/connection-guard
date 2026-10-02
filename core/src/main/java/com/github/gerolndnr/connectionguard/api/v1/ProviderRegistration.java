package com.github.gerolndnr.connectionguard.api.v1;

/** Close on addon shutdown. Registration alone never activates a provider in operator configuration. */
public interface ProviderRegistration extends AutoCloseable {
    String getId();
    boolean isRegistered();
    @Override void close();
}
