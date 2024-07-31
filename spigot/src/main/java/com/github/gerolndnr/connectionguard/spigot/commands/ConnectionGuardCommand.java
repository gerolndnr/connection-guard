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
                ConnectionGuardSpigotPlugin.getInstance().getConfig().getString("command.no-permission")
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

            if (Bukkit.getPlayer(entry) != null) {
                ipAddress = Bukkit.getPlayer(entry).getAddress().getHostName();
            } else {
                try {
                    ipAddress = Bukkit.getPlayer(UUID.fromString(entry)).getAddress().getHostName();
                } catch (Exception e) {
                    try {
                        ipAddress = InetAddress.getByName(entry).getHostAddress();
                    } catch (UnknownHostException ex) {
                        commandSender.sendMessage("§7» §bConnection Guard §7| Please enter the name or the uuid of an online player or use the ip address instead.");
                        return;
                    }
                }
            }

            VpnResult vpnResult = ConnectionGuard.getVpnResult(ipAddress).join();
            Optional<GeoResult> geoResultOptional = ConnectionGuard.getGeoResult(ipAddress).join();

            commandSender.sendMessage("§7————————————————————————————");
            commandSender.sendMessage("§7» Result for §b" + entry);
            if (vpnResult.isVpn()) {
                commandSender.sendMessage("§7IP: §f" + ipAddress + " §7(VPN: §fYes§7)");
            } else {
                commandSender.sendMessage("§7IP: §f" + ipAddress + " §7(VPN: No)");
            }
            if (geoResultOptional.isPresent()) {
                GeoResult geoResult = geoResultOptional.get();
                commandSender.sendMessage("§7Country: §f" + geoResult.getCountryName());
                commandSender.sendMessage("§7City: §f" + geoResult.getCityName());
                commandSender.sendMessage("§7ISP: §f" + geoResult.getIspName());
            } else {
                commandSender.sendMessage("§7No geo data found");
            }
            commandSender.sendMessage("§7————————————————————————————");
        });

        return true;
    }

    private boolean clearCache(CommandSender commandSender, String entry) {
        // Async, because InetAddress.getByName could affect the main thread (used to determine, if it is a valid hostname/ip address)
        CompletableFuture.runAsync(() -> {
            String ipAddress;

            Player player = Bukkit.getPlayer(entry);
            if (player != null) {
                ipAddress = player.getAddress().getHostName();
            } else {
                try {
                    UUID uuid = UUID.fromString(entry);
                    player = Bukkit.getPlayer(uuid);

                    if (player == null) {
                        commandSender.sendMessage("§7» §eConnection Guard §7| The uuid is from a player not currently online on the server. Use the ip address instead.");
                        return;
                    }
                    ipAddress = player.getAddress().getHostName();
                } catch (IllegalArgumentException e) {
                    try {
                        ipAddress = InetAddress.getByName(entry).getHostAddress();
                    } catch (UnknownHostException ex) {
                        commandSender.sendMessage("§7» §eConnection Guard §7| Please enter the name or the uuid of an online player or use the ip address instead.");
                        return;
                    }
                    ipAddress = entry;
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
        commandSender.sendMessage("§8╔════════ » §bConnection Guard §7v" + ConnectionGuardSpigotPlugin.getInstance().getDescription().getVersion() + " « §8════════╗");
        commandSender.sendMessage("§7 - /connectionguard §fhelp §7(this information)");
        if (commandSender.hasPermission("connectionguard.command.info"))
            commandSender.sendMessage("§7 - /connectionguard §finfo <Player/UUID/IP> §7(information about player or ip)");

        if (commandSender.hasPermission("connectionguard.command.clear"))
            commandSender.sendMessage("§7 - /connectionguard §fclear (<Player/UUID/IP>) §7(clear cache)");

        if (commandSender.hasPermission("connectionguard.command.reload"))
            commandSender.sendMessage("§7 - /connectionguard §freload §7(reload config)");

        commandSender.sendMessage("§8╚═════════════════════════════════╝");

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
