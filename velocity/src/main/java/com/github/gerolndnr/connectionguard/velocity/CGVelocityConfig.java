package com.github.gerolndnr.connectionguard.velocity;

import dev.dejvokep.boostedyaml.YamlDocument;
import dev.dejvokep.boostedyaml.settings.general.GeneralSettings;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

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
                InputStream in = getClass().getClassLoader().getResourceAsStream("config.yml");
                Files.copy(in, configFile.toPath());
            } catch (IOException e) {
                ConnectionGuardVelocityPlugin.getInstance().getLogger().error("Connection Guard | " + e.getMessage());
                return;
            }
        }
        try {
            config = YamlDocument.create(configFile, GeneralSettings.builder().setUseDefaults(true).build());
        } catch (IOException e) {
            ConnectionGuardVelocityPlugin.getInstance().getLogger().error("Connection Guard | " + e.getMessage());
        }

        String selectedLanguageFileName = config.getString("message-language") + ".yml";
        languageFile = new File(dataDirectory.resolve("translation").toFile(), selectedLanguageFileName);
        if (!languageFile.exists()) {
            try {
                InputStream in = getClass().getClassLoader().getResourceAsStream("translation" + File.separator + "en.yml");
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

    public YamlDocument getLanguageConfig() {
        return languageConfig;
    }

    public YamlDocument getConfig() {
        return config;
    }
}
