package com.github.gerolndnr.connectionguard.spigot.commands;

import com.github.gerolndnr.connectionguard.core.ConnectionGuard;
import com.github.gerolndnr.connectionguard.core.geo.GeoResult;
import com.github.gerolndnr.connectionguard.core.vpn.VpnResult;
import com.github.gerolndnr.connectionguard.spigot.ConnectionGuardSpigotPlugin;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class ConnectionGuardCommand implements TabExecutor {
    @Override
    public boolean onCommand(CommandSender commandSender, Command command, String s, String[] strings) {
        String noPermissionMessage = ChatColor.translateAlternateColorCodes(
                '&',
                ConnectionGuardSpigotPlugin.getInstance().getLanguageConfig().getString("command.no-permission")
        );

        if (strings.length == 0) {
            if (!commandSender.hasPermission("connectionguard.command.help")) {
                commandSender.sendMessage(noPermissionMessage);
            }
            return sendHelpMessage(commandSender);
        }
        if (strings.length == 1) {
            switch (strings[0].toLowerCase()) {
                case "help":
                    if (!commandSender.hasPermission("connectionguard.command.help")) {
                        commandSender.sendMessage(noPermissionMessage);
                        return true;
                    }
                    return sendHelpMessage(commandSender);
                case "reload":
                    if (!commandSender.hasPermission("connectionguard.command.reload")) {
                        commandSender.sendMessage(noPermissionMessage);
                        return true;
                    }
                    return reloadPlugin(commandSender);
                case "clear":
                    if (!commandSender.hasPermission("connectionguard.command.clear")) {
                        commandSender.sendMessage(noPermissionMessage);
                        return true;
                    }
                    return clearCache(commandSender);
                default:
                    return sendUnknownSubcommandMessage(commandSender);
            }
        }

        if (strings.length == 2) {
            switch (strings[0].toLowerCase()) {
                case "clear":
                    if (!commandSender.hasPermission("connectionguard.command.clear")) {
                        commandSender.sendMessage(noPermissionMessage);
                        return true;
                    }
                    return clearCache(commandSender, strings[1]);
                case "info":
                    if (!commandSender.hasPermission("connectionguard.command.info")) {
                        commandSender.sendMessage(noPermissionMessage);
                        return true;
                    }
                    return sendInformationMessage(commandSender, strings[1]);
                default:
                    return sendUnknownSubcommandMessage(commandSender);
            }
        }
        return sendUnknownSubcommandMessage(commandSender);
    }

    private boolean sendUnknownSubcommandMessage(CommandSender commandSender) {
        commandSender.sendMessage("§7» §bConnection Guard §7| Unknown sub-command. Enter §e/connectionguard §7to see all available commands!");

        return true;
    }

    private boolean sendInformationMessage(CommandSender commandSender, String entry) {
        CompletableFuture.runAsync(() -> {
            String ipAddress;
            String queriedInput;

            if (Bukkit.getPlayer(entry) != null) {
                Player player = Bukkit.getPlayer(entry);
                ipAddress = player.getAddress().getAddress().getHostAddress();
                queriedInput = player.getName();
            } else {
                try {
                    Player player = Bukkit.getPlayer(UUID.fromString(entry));
                    ipAddress = Bukkit.getPlayer(UUID.fromString(entry)).getAddress().getAddress().getHostAddress();
                    queriedInput = player.getName();
                } catch (Exception e) {
                    try {
                        ipAddress = InetAddress.getByName(entry).getHostAddress();
                        queriedInput = ipAddress;
                    } catch (UnknownHostException ex) {
                        commandSender.sendMessage(
                                ChatColor.translateAlternateColorCodes(
                                        '&',
                                        ConnectionGuardSpigotPlugin.getInstance().getLanguageConfig().getString("messages.invalid-argument")
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

            String isVpn = ChatColor.translateAlternateColorCodes(
                    '&',
                    ConnectionGuardSpigotPlugin.getInstance().getLanguageConfig().getString("messages.info.not-vpn")
            );
            if (vpnResult.isVpn()) {
                isVpn = ChatColor.translateAlternateColorCodes(
                        '&',
                        ConnectionGuardSpigotPlugin.getInstance().getLanguageConfig().getString("messages.info.is-vpn")
                );
            }

            for (String line : ConnectionGuardSpigotPlugin.getInstance().getLanguageConfig().getStringList("messages.info.text")) {
                commandSender.sendMessage(
                        ChatColor.translateAlternateColorCodes(
                                '&',
                                line.replaceAll("%INPUT%", queriedInput)
                                        .replaceAll("%COUNTRY%", geoResult.getCountryName())
                                        .replaceAll("%CITY%", geoResult.getCityName())
                                        .replaceAll("%ISP%", geoResult.getIspName())
                                        .replaceAll("%IS_VPN%", isVpn)
                                        .replaceAll("%IP%", ipAddress)
                        )
                );
            }
        });

        return true;
    }

    private boolean clearCache(CommandSender commandSender, String entry) {
        // Async, because InetAddress.getByName could affect the main thread (used to determine, if it is a valid hostname/ip address)
        CompletableFuture.runAsync(() -> {
            String ipAddress;
            String queriedInput;

            if (Bukkit.getPlayer(entry) != null) {
                Player player = Bukkit.getPlayer(entry);
                ipAddress = player.getAddress().getHostName();
                queriedInput = player.getName();
            } else {
                try {
                    Player player = Bukkit.getPlayer(UUID.fromString(entry));
                    ipAddress = Bukkit.getPlayer(UUID.fromString(entry)).getAddress().getHostName();
                    queriedInput = player.getName();
                } catch (Exception e) {
                    try {
                        ipAddress = InetAddress.getByName(entry).getHostAddress();
                        queriedInput = ipAddress;
                    } catch (UnknownHostException ex) {
                        commandSender.sendMessage(
                                ChatColor.translateAlternateColorCodes(
                                        '&',
                                        ConnectionGuardSpigotPlugin.getInstance().getLanguageConfig().getString("messages.invalid-argument")
                                )
                        );
                        return;
                    }
                }
            }

            ConnectionGuard.getCacheProvider().removeGeoResult(ipAddress);
            ConnectionGuard.getCacheProvider().removeVpnResult(ipAddress);
            commandSender.sendMessage("§7» §eConnection Guard §7| Removing the cache entry for §e" + entry + "§7.");
        });

        return true;
    }

    private boolean clearCache(CommandSender commandSender) {
        ConnectionGuard.getCacheProvider().removeAllVpnResults();
        ConnectionGuard.getCacheProvider().removeAllGeoResults();
        commandSender.sendMessage("§7» §eConnection Guard §7| Clearing all caches.");
        return true;
    }

    private boolean sendHelpMessage(CommandSender commandSender) {
        for (String line : ConnectionGuardSpigotPlugin.getInstance().getLanguageConfig().getStringList("messages.help")) {
            commandSender.sendMessage(
                    ChatColor.translateAlternateColorCodes('&', line)
            );
        }

        return true;
    }

    private boolean reloadPlugin(CommandSender commandSender) {
        ConnectionGuardSpigotPlugin.getInstance().reloadConfig();
        commandSender.sendMessage("§7» §eConnection Guard §7| Config has been reloaded.");
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender commandSender, Command command, String s, String[] strings) {
        List<String> proposals = new ArrayList<>();
        if (strings.length == 1) {
            if (commandSender.hasPermission("connectionguard.command.help"))
                proposals.add("help");
            if (commandSender.hasPermission("connectionguard.command.info"))
                proposals.add("info");
            if (commandSender.hasPermission("connectionguard.command.clear"))
                proposals.add("clear");
            if (commandSender.hasPermission("connectionguard.command.reload"))
                proposals.add("reload");
        }
        return proposals;
    }
}
