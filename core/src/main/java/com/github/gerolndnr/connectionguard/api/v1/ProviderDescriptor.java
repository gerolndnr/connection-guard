package com.github.gerolndnr.connectionguard.api.v1;

/** Public stable ID/version and hash of detection configuration; never pass endpoints or secrets as labels. */
public final class ProviderDescriptor {
    private final String id, version, configurationFingerprint;
    private final boolean voting;
    public ProviderDescriptor(String id, String version, String configurationFingerprint, boolean voting) {
        if (id == null || !id.matches("[a-z][a-z0-9-]{0,31}") || version == null || !version.matches("[A-Za-z0-9_.-]{1,64}")
                || configurationFingerprint == null || !configurationFingerprint.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("Invalid public provider descriptor (values redacted).");
        this.id = id; this.version = version; this.configurationFingerprint = configurationFingerprint; this.voting = voting;
    }
    public String getId() { return id; }
    public String getVersion() { return version; }
    public String getConfigurationFingerprint() { return configurationFingerprint; }
    public boolean isVoting() { return voting; }
}
