package com.github.gerolndnr.connectionguard.api.v1;
/** Closing this exact handle cannot close a later registration using the same ID. */
public interface AdmissionRegistration extends AutoCloseable { String getId(); boolean isRegistered(); @Override void close(); }
