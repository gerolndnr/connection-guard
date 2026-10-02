package com.github.gerolndnr.connectionguard.core.extensions;

import com.github.gerolndnr.connectionguard.api.v1.*;
import java.util.*;

/** Opaque trusted addon callbacks stay out of serialization and configuration drafts. */
public final class ExtensionRegistry {
    private static final Map<String, Entry> entries = new HashMap<>();
    private ExtensionRegistry() { }
    public static synchronized ProviderRegistration register(ProviderDescriptor descriptor, DetectionProvider provider) {
        Objects.requireNonNull(descriptor); Objects.requireNonNull(provider);
        if (entries.containsKey(descriptor.getId())) throw new IllegalArgumentException("Provider ID is already registered.");
        if (entries.size() >= 8) throw new IllegalStateException("At most eight extension providers may register.");
        Entry entry = new Entry(descriptor, provider); entries.put(descriptor.getId(), entry); return entry;
    }
    public static synchronized Entry find(String id) { return entries.get(id); }
    public static synchronized void closeAll() {
        for (Entry entry : new ArrayList<>(entries.values())) entry.close();
    }
    public static final class Entry implements ProviderRegistration {
        public final ProviderDescriptor descriptor;
        final DetectionProvider callback;
        private volatile boolean registered = true;
        private Entry(ProviderDescriptor descriptor, DetectionProvider callback) { this.descriptor = descriptor; this.callback = callback; }
        @Override public String getId() { return descriptor.getId(); }
        @Override public boolean isRegistered() { return registered; }
        @Override public void close() {
            synchronized (ExtensionRegistry.class) {
                registered = false;
                entries.remove(getId(), this);
            }
        }
    }
}
