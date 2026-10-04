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
    private YamlDocument config;
    private YamlDocument languageConfig;
    private Path dataDirectory;

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

        String selectedLanguageFileName = config.getString("message-language") + ".yml";
        languageFile = new File(dataDirectory.resolve("translation").toFile(), selectedLanguageFileName);
        if (!languageFile.exists()) {
            try {
                InputStream in = ConnectionGuardVelocityPlugin.class.getResourceAsStream("/translation/en.yml");

                Files.copy(in, languageFile.toPath());
            } catch (IOException e) {
                ConnectionGuardVelocityPlugin.getInstance().getLogger().error("Connection Guard | " + e.getMessage());
                return;
            }
        }
        try {
            languageConfig = YamlDocument.create(languageFile, GeneralSettings.builder().setUseDefaults(true).build());
        } catch (IOException e) {
            ConnectionGuardVelocityPlugin.getInstance().getLogger().error("Connection Guard | " + e.getMessage());
            return;
        }
    }

    public void reloadValidated() throws IOException {
        YamlDocument next = YamlDocument.create(configFile, GeneralSettings.builder().setUseDefaults(false).build());
        com.github.gerolndnr.connectionguard.core.cloud.CloudManagedConfig.overlay(dataDirectory, next::set);
        com.github.gerolndnr.connectionguard.core.config.ProviderConfiguration draft = new com.github.gerolndnr.connectionguard.core.config.ProviderConfiguration(
                next::get, next.getSection("provider.vpn").getKeys().stream().map(Object::toString).collect(java.util.stream.Collectors.toList()), dataDirectory);
        com.github.gerolndnr.connectionguard.core.ConnectionGuard.applyProviders(draft);
        config = next;
        languageConfig.reload();
    }
    public YamlDocument getLanguageConfig() {
        return languageConfig;
    }

    public YamlDocument getConfig() {
        return config;
    }
}
