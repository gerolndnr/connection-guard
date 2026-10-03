package com.github.gerolndnr.connectionguard.spigot;

import java.io.File;
import java.io.IOException;
import java.util.UUID;
import java.util.logging.Level;
import org.bstats.MetricsBase;
import org.bstats.json.JsonObjectBuilder;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * bStats 3.0.2 Bukkit data/config adapter with Paper/Folia-aware dispatch.
 * Adapted from org.bstats.bukkit.Metrics, Copyright (c) 2021 Bastian Oppermann.
 * MIT terms are retained in META-INF/connection-guard/bStats-MIT.txt.
 */
final class PlatformMetrics {
    private final JavaPlugin plugin;
    private final MetricsBase metricsBase;
    PlatformMetrics(JavaPlugin plugin, PlatformTasks tasks, int serviceId) {
        this.plugin = plugin;
        File configFile = new File(new File(plugin.getDataFolder().getParentFile(), "bStats"), "config.yml");
        YamlConfiguration config = YamlConfiguration.loadConfiguration(configFile);
        if (!config.isSet("serverUuid")) {
            config.addDefault("enabled", true);
            config.addDefault("serverUuid", UUID.randomUUID().toString());
            config.addDefault("logFailedRequests", false);
            config.addDefault("logSentData", false);
            config.addDefault("logResponseStatusText", false);
            config.options().header("bStats (https://bstats.org) collects aggregate plugin and runtime statistics.\n"
                    + "Set enabled to false to disable submission.").copyDefaults(true);
            try { config.save(configFile); }
            catch (IOException ignored) { }
        }
        metricsBase = new MetricsBase("bukkit", config.getString("serverUuid"), serviceId,
                config.getBoolean("enabled", true), this::appendPlatformData, this::appendServiceData,
                tasks::global, plugin::isEnabled,
                (message, error) -> plugin.getLogger().log(Level.WARNING, message, error),
                message -> plugin.getLogger().log(Level.INFO, message),
                config.getBoolean("logFailedRequests", false), config.getBoolean("logSentData", false),
                config.getBoolean("logResponseStatusText", false));
    }
    private void appendPlatformData(JsonObjectBuilder builder) {
        // MetricsBase invokes this inside the supplied dispatcher, never its timer thread.
        builder.appendField("playerAmount", Bukkit.getOnlinePlayers().size());
        builder.appendField("onlineMode", Bukkit.getOnlineMode() ? 1 : 0);
        builder.appendField("bukkitVersion", Bukkit.getVersion());
        builder.appendField("bukkitName", Bukkit.getName());
        builder.appendField("javaVersion", System.getProperty("java.version"));
        builder.appendField("osName", System.getProperty("os.name"));
        builder.appendField("osArch", System.getProperty("os.arch"));
        builder.appendField("osVersion", System.getProperty("os.version"));
        builder.appendField("coreCount", Runtime.getRuntime().availableProcessors());
    }
    private void appendServiceData(JsonObjectBuilder builder) {
        builder.appendField("pluginVersion", plugin.getDescription().getVersion());
    }
    void close() { metricsBase.shutdown(); }
}
