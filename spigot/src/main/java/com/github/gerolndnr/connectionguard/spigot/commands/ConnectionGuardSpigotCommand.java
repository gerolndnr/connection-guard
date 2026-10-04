package com.github.gerolndnr.connectionguard.spigot.commands;

import com.github.gerolndnr.connectionguard.core.ConnectionGuard;
import com.github.gerolndnr.connectionguard.core.commands.OperationsCommands;
import com.github.gerolndnr.connectionguard.spigot.ConnectionGuardSpigotPlugin;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

public class ConnectionGuardSpigotCommand implements TabExecutor {
    @Override
    public boolean onCommand(CommandSender commandSender, Command command, String s, String[] args) {
        final com.github.gerolndnr.connectionguard.core.messages.MessageCatalog messages = ConnectionGuard.getMessages();
        String noPermissionMessage = ChatColor.translateAlternateColorCodes(
                '&',
                messages.getString("command.no-permission")
        );

        if (OperationsCommands.handle(args, commandSender::hasPermission,
                text -> ConnectionGuardSpigotPlugin.getInstance().tasks().reply(commandSender, text))) return true;
        if (args.length == 0) {
            if (!commandSender.hasPermission("connectionguard.command.help")) {
                commandSender.sendMessage(noPermissionMessage);
                return true;
            }
            return sendHelpMessage(commandSender);
        }
        if (args.length == 1) {
            switch (args[0].toLowerCase()) {
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

        if (args.length == 2) {
            switch (args[0].toLowerCase()) {
                case "clear":
                    if (!commandSender.hasPermission("connectionguard.command.clear")) {
                        commandSender.sendMessage(noPermissionMessage);
                        return true;
                    }
                    return clearCache(commandSender, args[1]);
                case "info":
                    if (!commandSender.hasPermission("connectionguard.command.info")) {
                        commandSender.sendMessage(noPermissionMessage);
                        return true;
                    }
                    return sendInformationMessage(commandSender, args[1]);
                default:
                    return sendUnknownSubcommandMessage(commandSender);
            }
        }
        return sendUnknownSubcommandMessage(commandSender);
    }

    private boolean sendUnknownSubcommandMessage(CommandSender commandSender) {
        final com.github.gerolndnr.connectionguard.core.messages.MessageCatalog messages = ConnectionGuard.getMessages();
        commandSender.sendMessage(
                ChatColor.translateAlternateColorCodes(
                        '&',
                        messages.getString("command.unknown-subcommand")
                )
        );

        return true;
    }

    private void reply(CommandSender sender, String text) {
        ConnectionGuardSpigotPlugin.getInstance().tasks().reply(sender, text);
    }
    private void target(CommandSender sender, String entry, java.util.function.Consumer<com.github.gerolndnr.connectionguard.spigot.PlatformTasks.Target> action) {
        final com.github.gerolndnr.connectionguard.core.messages.MessageCatalog messages = ConnectionGuard.getMessages();
        ConnectionGuardSpigotPlugin.getInstance().tasks().target(entry, action,
            () -> reply(sender, messages.getString("command.target-invalid")));
    }
    private boolean sendInformationMessage(CommandSender sender, String entry) {
        final com.github.gerolndnr.connectionguard.core.messages.MessageCatalog messages = ConnectionGuard.getMessages();
        target(sender, entry, selected -> ConnectionGuard.getVpnResult(selected.ip).thenCombine(ConnectionGuard.getGeoLookup(selected.ip), (rawVpn, rawGeo) -> {
            com.github.gerolndnr.connectionguard.core.commands.LookupInformation info = com.github.gerolndnr.connectionguard.core.commands.LookupInformation.asOf(rawVpn, rawGeo, System.currentTimeMillis());
            String flag = info.getVpnStatus() == com.github.gerolndnr.connectionguard.core.lookup.ProviderVote.Status.UNKNOWN ? messages.getString("messages.info.unknown")
                    : ChatColor.translateAlternateColorCodes('&', messages.getString(info.getVpnStatus() == com.github.gerolndnr.connectionguard.core.lookup.ProviderVote.Status.POSITIVE ? "messages.info.is-vpn" : "messages.info.not-vpn"));
            for (String line : messages.getStringList("messages.info.text")) reply(sender,
                    ChatColor.translateAlternateColorCodes('&', line.replace("%INPUT%", selected.display).replace("%COUNTRY%", info.getCountry())
                    .replace("%CITY%", info.getCity()).replace("%ISP%", info.getIsp()).replace("%IS_VPN%", flag).replace("%IP%", selected.ip)));
            return null;
        }).exceptionally(error -> { reply(sender, messages.getString("command.info-unavailable")); return null; }));
        return true;
    }
    private boolean clearCache(CommandSender sender, String entry) {
        final com.github.gerolndnr.connectionguard.core.messages.MessageCatalog messages = ConnectionGuard.getMessages();
        target(sender, entry, selected -> ConnectionGuard.getCacheProvider().removeGeoResult(selected.ip)
            .thenCombine(ConnectionGuard.getCacheProvider().removeVpnResult(selected.ip), (geo, vpn) -> {
                reply(sender, Boolean.TRUE.equals(geo) && Boolean.TRUE.equals(vpn)
                    ? ChatColor.translateAlternateColorCodes('&', messages.getString("command.clear.clear-specific").replace("%ENTRY%", selected.display))
                    : messages.getString("command.clear-unavailable"));
                return null;
            }).exceptionally(error -> { reply(sender, messages.getString("command.clear-unavailable")); return null; }));
        return true;
    }
    private boolean clearCache(CommandSender sender) {
        final com.github.gerolndnr.connectionguard.core.messages.MessageCatalog messages = ConnectionGuard.getMessages();
        ConnectionGuard.getCacheProvider().removeAllVpnResults().thenCombine(ConnectionGuard.getCacheProvider().removeAllGeoResults(), (vpn, geo) -> {
            reply(sender, Boolean.TRUE.equals(vpn) && Boolean.TRUE.equals(geo)
                ? ChatColor.translateAlternateColorCodes('&', messages.getString("command.clear.clear-all"))
                : messages.getString("command.clear-unavailable"));
            return null;
        }).exceptionally(error -> { reply(sender, messages.getString("command.clear-unavailable")); return null; });
        return true;
    }

    private boolean sendHelpMessage(CommandSender commandSender) {
        final com.github.gerolndnr.connectionguard.core.messages.MessageCatalog messages = ConnectionGuard.getMessages();
        for (String line : messages.getStringList("messages.help")) {
            commandSender.sendMessage(
                    ChatColor.translateAlternateColorCodes('&', line)
            );
        }

        return true;
    }

    private boolean reloadPlugin(CommandSender commandSender) {
        try { ConnectionGuardSpigotPlugin.getInstance().reloadAllConfigs(); }
        catch (IllegalArgumentException | IllegalStateException rejected) {
            commandSender.sendMessage(ConnectionGuard.getMessages().getString("command.reload-rejected")); return true;
        }
        commandSender.sendMessage(
                ChatColor.translateAlternateColorCodes(
                        '&',
                        ConnectionGuard.getMessages().getString("command.config-reload")
                )
        );
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender commandSender, Command command, String s, String[] strings) {
        List<String> proposals = new ArrayList<>();
        for (String operation : OperationsCommands.NAMES) if (commandSender.hasPermission("connectionguard.command." + operation)) proposals.add(operation);
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
        if (strings.length == 2) {
            if (strings[0].equalsIgnoreCase("info")) {
                proposals.add("1.1.1.1");
                for (Player player : Bukkit.getOnlinePlayers()) {
                    proposals.add(player.getName());
                }
            }
            if (strings[0].equalsIgnoreCase("clear")) {
                proposals.add("1.1.1.1");
                for (Player player : Bukkit.getOnlinePlayers()) {
                    proposals.add(player.getName());
                }
            }
        }
        return proposals;
    }
}
