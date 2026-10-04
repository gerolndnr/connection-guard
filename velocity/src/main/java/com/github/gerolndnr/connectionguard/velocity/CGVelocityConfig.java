package com.github.gerolndnr.connectionguard.velocity;

import dev.dejvokep.boostedyaml.YamlDocument;
import dev.dejvokep.boostedyaml.settings.general.GeneralSettings;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class CGVelocityConfig {
    private File configFile;
    private File languageFile;
    private volatile YamlDocument config;
    private volatile YamlDocument languageConfig;
    private Path dataDirectory;
    private com.github.gerolndnr.connectionguard.core.messages.MessageCatalog messages;
    public com.github.gerolndnr.connectionguard.core.messages.MessageCatalog getMessages() { return messages; }

    public CGVelocityConfig(Path dataDirectory) {
        this.dataDirectory = dataDirectory;
    }

    public void load() {
        File translationFolder = dataDirectory.resolve("translation").toFile();
        if (!translationFolder.exists()) {
            translationFolder.mkdirs();
        }
        configFile = new File(dataDirectory.toFile(), "config.yml");
        if (!configFile.exists()) {
            try {
                InputStream in = ConnectionGuardVelocityPlugin.class.getResourceAsStream("/config.yml");
                Files.copy(in, configFile.toPath());
            } catch (IOException e) {
                ConnectionGuardVelocityPlugin.getInstance().getLogger().error("Connection Guard | " + e.getMessage());
                return;
            }
        }
        try {
            config = YamlDocument.create(configFile, GeneralSettings.builder().setUseDefaults(true).build());
            // Dashboard settings layer over config.yml in memory; the file is never written.
            com.github.gerolndnr.connectionguard.core.cloud.CloudManagedConfig.overlay(dataDirectory, config::set);
        } catch (IOException e) {
            ConnectionGuardVelocityPlugin.getInstance().getLogger().error("Connection Guard | " + e.getMessage());
        }

        com.github.gerolndnr.connectionguard.core.messages.LanguageFiles.Loaded<YamlDocument> selectedMessages = loadMessages(config.get("message-language"));
        languageFile = selectedMessages.file.toFile();
        languageConfig = selectedMessages.document;
        messages = selectedMessages.messages;
    }

    public void reloadValidated() throws IOException {
        synchronized (com.github.gerolndnr.connectionguard.core.ConnectionGuard.class) {
            YamlDocument next = YamlDocument.create(configFile, GeneralSettings.builder().setUseDefaults(false).build());
            com.github.gerolndnr.connectionguard.core.cloud.CloudManagedConfig.overlay(dataDirectory, next::set);
            com.github.gerolndnr.connectionguard.core.messages.LanguageFiles.Loaded<YamlDocument> selectedMessages = loadMessages(next.get("message-language"));
            com.github.gerolndnr.connectionguard.core.config.ProviderConfiguration draft = new com.github.gerolndnr.connectionguard.core.config.ProviderConfiguration(
                    next::get, next.getSection("provider.vpn").getKeys().stream().map(Object::toString).collect(java.util.stream.Collectors.toList()), dataDirectory, selectedMessages.messages);
            com.github.gerolndnr.connectionguard.core.ConnectionGuard.applyProviders(draft);
            config = next;
            languageFile = selectedMessages.file.toFile();
            languageConfig = selectedMessages.document;
            messages = selectedMessages.messages;
            com.github.gerolndnr.connectionguard.core.cloud.CloudSync.refresh();
        }
    }

    private com.github.gerolndnr.connectionguard.core.messages.LanguageFiles.Loaded<dev.dejvokep.boostedyaml.YamlDocument> loadMessages(Object language) {
        return com.github.gerolndnr.connectionguard.core.messages.LanguageFiles.load(dataDirectory, language, new com.github.gerolndnr.connectionguard.core.messages.LanguageFiles.Parser<dev.dejvokep.boostedyaml.YamlDocument>() {
            public dev.dejvokep.boostedyaml.YamlDocument parse(java.nio.file.Path file) throws Exception {
                return dev.dejvokep.boostedyaml.YamlDocument.create(file.toFile(),
                        dev.dejvokep.boostedyaml.settings.general.GeneralSettings.builder().setUseDefaults(false).build());
            }
            public Object value(dev.dejvokep.boostedyaml.YamlDocument document, String key) { return document.get(key); }
        });
    }

    public YamlDocument getLanguageConfig() {
        return languageConfig;
    }

    public YamlDocument getConfig() {
        return config;
    }
}
