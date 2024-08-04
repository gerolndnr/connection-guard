package com.github.gerolndnr.connectionguard.velocity.commands;

import com.github.gerolndnr.connectionguard.core.ConnectionGuard;
import com.github.gerolndnr.connectionguard.core.geo.GeoResult;
import com.github.gerolndnr.connectionguard.core.vpn.VpnResult;
import com.github.gerolndnr.connectionguard.velocity.ConnectionGuardVelocityPlugin;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class ConnectionGuardVelocityCommand implements SimpleCommand {
    @Override
    public void execute(Invocation invocation) {
        CommandSource commandSender = invocation.source();
        String[] args = invocation.arguments();

        Component noPermissionMessage = LegacyComponentSerializer.legacyAmpersand().deserialize(
                ConnectionGuardVelocityPlugin.getInstance().getCgVelocityConfig().getLanguageConfig().getString("command.no-permission")
        );

        if (args.length == 0) {
            if (!commandSender.hasPermission("connectionguard.command.help")) {
                commandSender.sendMessage(noPermissionMessage);
            }
            sendHelpMessage(commandSender);
            return;
        }
        if (args.length == 1) {
            switch (args[0].toLowerCase()) {
                case "help":
                    if (!commandSender.hasPermission("connectionguard.command.help")) {
                        commandSender.sendMessage(noPermissionMessage);
                        return;
                    }
                    sendHelpMessage(commandSender);
                    return;
                case "reload":
                    if (!commandSender.hasPermission("connectionguard.command.reload")) {
                        commandSender.sendMessage(noPermissionMessage);
                        return;
                    }
                    reloadPlugin(commandSender);
                    return;
                case "clear":
                    if (!commandSender.hasPermission("connectionguard.command.clear")) {
                        commandSender.sendMessage(noPermissionMessage);
                        return;
                    }
                    clearCache(commandSender);
                    return;
                default:
                    sendUnknownSubcommandMessage(commandSender);
                    return;
            }
        }

        if (args.length == 2) {
            switch (args[0].toLowerCase()) {
                case "clear":
                    if (!commandSender.hasPermission("connectionguard.command.clear")) {
                        commandSender.sendMessage(noPermissionMessage);
                        return;
                    }
                    clearCache(commandSender, args[1]);
                    return;
                case "info":
                    if (!commandSender.hasPermission("connectionguard.command.info")) {
                        commandSender.sendMessage(noPermissionMessage);
                        return;
                    }
                    sendInformationMessage(commandSender, args[1]);
                    return;
                default:
                    sendUnknownSubcommandMessage(commandSender);
                    return;
            }
        }
        sendUnknownSubcommandMessage(commandSender);
    }

    private void sendUnknownSubcommandMessage(CommandSource commandSender) {
        commandSender.sendMessage(
                LegacyComponentSerializer.legacyAmpersand().deserialize(
                        ConnectionGuardVelocityPlugin.getInstance().getCgVelocityConfig().getLanguageConfig().getString("command.unknown-subcommand")
                )
        );

        return;
    }

    private boolean sendInformationMessage(CommandSource commandSender, String entry) {
        CompletableFuture.runAsync(() -> {
            String ipAddress;
            String queriedInput;

            if (ConnectionGuardVelocityPlugin.getInstance().getProxyServer().getPlayer(entry).isPresent()) {
                Player player = ConnectionGuardVelocityPlugin.getInstance().getProxyServer().getPlayer(entry).get();
                ipAddress = player.getRemoteAddress().getAddress().getHostAddress();
                queriedInput = player.getUsername();
            } else {
                try {
                    Player player = ConnectionGuardVelocityPlugin.getInstance().getProxyServer().getPlayer(UUID.fromString(entry)).get();
                    ipAddress = player.getRemoteAddress().getAddress().getHostAddress();
                    queriedInput = player.getUsername();
                } catch (Exception e) {
                    try {
                        ipAddress = InetAddress.getByName(entry).getHostAddress();
                        queriedInput = ipAddress;
                    } catch (UnknownHostException ex) {
                        commandSender.sendMessage(
                                LegacyComponentSerializer.legacyAmpersand().deserialize(
                                        ConnectionGuardVelocityPlugin.getInstance().getCgVelocityConfig().getLanguageConfig().getString("messages.invalid-argument")
                                )
                        );
                        return;
                    }
                }
            }

            VpnResult vpnResult = ConnectionGuard.getVpnResult(ipAddress).join();
            Optional<GeoResult> geoResultOptional = ConnectionGuard.getGeoResult(ipAddress).join();

            GeoResult geoResult;

            if (geoResultOptional.isPresent()) {
                geoResult = geoResultOptional.get();
            } else {
                geoResult = new GeoResult(ipAddress, "-", "-", "-");
            }

            Component isVpn = LegacyComponentSerializer.legacyAmpersand().deserialize(
                    ConnectionGuardVelocityPlugin.getInstance().getCgVelocityConfig().getLanguageConfig().getString("messages.info.not-vpn")
            );
            if (vpnResult.isVpn()) {
                isVpn = LegacyComponentSerializer.legacyAmpersand().deserialize(
                        ConnectionGuardVelocityPlugin.getInstance().getCgVelocityConfig().getLanguageConfig().getString("messages.info.is-vpn")
                );
            }

            for (String line : ConnectionGuardVelocityPlugin.getInstance().getCgVelocityConfig().getLanguageConfig().getStringList("messages.info.text")) {
                commandSender.sendMessage(
                        LegacyComponentSerializer.legacyAmpersand().deserialize(
                                line.replaceAll("%INPUT%", queriedInput)
                                        .replaceAll("%COUNTRY%", geoResult.getCountryName())
                                        .replaceAll("%CITY%", geoResult.getCityName())
                                        .replaceAll("%ISP%", geoResult.getIspName())
                                        .replaceAll("%IS_VPN%", isVpn.toString())
                                        .replaceAll("%IP%", ipAddress)
                        )
                );
            }
        });

        return true;
    }

    private boolean clearCache(CommandSource commandSender, String entry) {
        // Async, because InetAddress.getByName could affect the main thread (used to determine, if it is a valid hostname/ip address)
        CompletableFuture.runAsync(() -> {
            String ipAddress;
            String queriedInput;

            if (ConnectionGuardVelocityPlugin.getInstance().getProxyServer().getPlayer(entry).isPresent()) {
                Player player = ConnectionGuardVelocityPlugin.getInstance().getProxyServer().getPlayer(entry).get();
                ipAddress = player.getRemoteAddress().getHostName();
                queriedInput = player.getUsername();
            } else {
                try {
                    Player player = ConnectionGuardVelocityPlugin.getInstance().getProxyServer().getPlayer(UUID.fromString(entry)).get();
                    ipAddress = ConnectionGuardVelocityPlugin.getInstance().getProxyServer().getPlayer(UUID.fromString(entry)).get().getRemoteAddress().getHostName();
                    queriedInput = player.getUsername();
                } catch (Exception e) {
                    try {
                        ipAddress = InetAddress.getByName(entry).getHostAddress();
                        queriedInput = ipAddress;
                    } catch (UnknownHostException ex) {
                        commandSender.sendMessage(
                                LegacyComponentSerializer.legacyAmpersand().deserialize(
                                        ConnectionGuardVelocityPlugin.getInstance().getCgVelocityConfig().getLanguageConfig().getString("messages.invalid-argument")
                                )
                        );
                        return;
                    }
                }
            }

            ConnectionGuard.getCacheProvider().removeGeoResult(ipAddress);
            ConnectionGuard.getCacheProvider().removeVpnResult(ipAddress);
            commandSender.sendMessage(
                    LegacyComponentSerializer.legacyAmpersand().deserialize(
                            ConnectionGuardVelocityPlugin.getInstance().getCgVelocityConfig().getLanguageConfig().getString("command.clear.clear-specific")
                                    .replaceAll("%ENTRY%", queriedInput)
                    )
            );
        });

        return true;
    }

    private boolean clearCache(CommandSource commandSender) {
        ConnectionGuard.getCacheProvider().removeAllVpnResults();
        ConnectionGuard.getCacheProvider().removeAllGeoResults();
        commandSender.sendMessage(LegacyComponentSerializer.legacyAmpersand().deserialize(
                ConnectionGuardVelocityPlugin.getInstance().getCgVelocityConfig().getLanguageConfig().getString("command.clear.clear-all")
        ));
        return true;
    }

    private boolean sendHelpMessage(CommandSource commandSender) {
        for (String line : ConnectionGuardVelocityPlugin.getInstance().getCgVelocityConfig().getLanguageConfig().getStringList("messages.help")) {
            commandSender.sendMessage(
                    LegacyComponentSerializer.legacyAmpersand().deserialize(line)
            );
        }

        return true;
    }

    private boolean reloadPlugin(CommandSource commandSender) {
        try {
            ConnectionGuardVelocityPlugin.getInstance().getCgVelocityConfig().getConfig().reload();
            ConnectionGuardVelocityPlugin.getInstance().getCgVelocityConfig().getLanguageConfig().reload();
        } catch (IOException e) {
            ConnectionGuardVelocityPlugin.getInstance().getLogger().error("Boosted YAML | " + e.getMessage());
            return true;
        }

        commandSender.sendMessage(
                LegacyComponentSerializer.legacyAmpersand().deserialize(
                        ConnectionGuardVelocityPlugin.getInstance().getCgVelocityConfig().getLanguageConfig().getString("command.config-reload")
                                .replace("&", "§")
                )
        );
        return true;
    }
}
