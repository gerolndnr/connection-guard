package com.github.gerolndnr.connectionguard.api.v1;

/** Registration alone does not activate an observer; explicit selection and reload are required. */
public interface ObserverRegistration extends AutoCloseable {
    String getId();
    boolean isRegistered();
    @Override void close();
}
