package com.github.gerolndnr.connectionguard.bungee.commands;

import com.github.gerolndnr.connectionguard.bungee.ConnectionGuardBungeePlugin;
import com.github.gerolndnr.connectionguard.core.ConnectionGuard;
import com.github.gerolndnr.connectionguard.core.commands.OperationsCommands;
import com.github.gerolndnr.connectionguard.core.vpn.VpnResult;
import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.CommandSender;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.plugin.Command;
import net.md_5.bungee.api.plugin.TabExecutor;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class ConnectionGuardBungeeCommand extends Command implements TabExecutor {
    public ConnectionGuardBungeeCommand() {
        super("connectionguard", "connectionguard.command", "cg");
    }

    @Override
    public void execute(CommandSender commandSender, String[] args) {
        try { executeGuard(commandSender, args); }
        catch (RuntimeException | LinkageError failure) { com.github.gerolndnr.connectionguard.core.cloud.PluginErrorReports.record(failure, com.github.gerolndnr.connectionguard.core.cloud.PluginErrorReports.Context.COMMAND); throw failure; }
    }
    private void executeGuard(CommandSender commandSender, String[] args) {
        final com.github.gerolndnr.connectionguard.core.messages.MessageCatalog messages = ConnectionGuard.getMessages();
        String noPermissionMessage = ChatColor.translateAlternateColorCodes(
                '&',
                messages.getString("command.no-permission")
        );

        if (OperationsCommands.handle(args, commandSender::hasPermission, text -> commandSender.sendMessage(new TextComponent(text)))) return;
        if (args.length == 0) {
            if (!commandSender.hasPermission("connectionguard.command.help")) {
                commandSender.sendMessage(noPermissionMessage);
                return;
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

    private void sendUnknownSubcommandMessage(CommandSender commandSender) {
        final com.github.gerolndnr.connectionguard.core.messages.MessageCatalog messages = ConnectionGuard.getMessages();
        commandSender.sendMessage(
                ChatColor.translateAlternateColorCodes(
                        '&',
                        messages.getString("command.unknown-subcommand")
                )
        );

        return;
    }

    private boolean sendInformationMessage(CommandSender commandSender, String entry) {
        final com.github.gerolndnr.connectionguard.core.messages.MessageCatalog messages = ConnectionGuard.getMessages();
        CompletableFuture.runAsync(() -> {
            String ipAddress;
            String queriedInput;

            if (ConnectionGuardBungeePlugin.getInstance().getProxy().getPlayer(entry) != null) {
                ProxiedPlayer player = ConnectionGuardBungeePlugin.getInstance().getProxy().getPlayer(entry);
                ipAddress = player.getAddress().getAddress().getHostAddress();
                queriedInput = player.getName();
            } else {
                try {
                    ProxiedPlayer player = ConnectionGuardBungeePlugin.getInstance().getProxy().getPlayer(UUID.fromString(entry));
                    ipAddress = player.getAddress().getAddress().getHostAddress();
                    queriedInput = player.getName();
                } catch (Exception e) {
                    try {
                        ipAddress = InetAddress.getByName(entry).getHostAddress();
                        queriedInput = ipAddress;
                    } catch (UnknownHostException ex) {
                        commandSender.sendMessage(
                                ChatColor.translateAlternateColorCodes(
                                        '&',
                                        messages.getString("messages.invalid-argument")
                                )
                        );
                        return;
                    }
                }
            }

            VpnResult vpnResult = ConnectionGuard.getVpnResult(ipAddress).join();
            com.github.gerolndnr.connectionguard.core.commands.LookupInformation info = com.github.gerolndnr.connectionguard.core.commands.LookupInformation.asOf(
                    vpnResult, ConnectionGuard.getGeoLookup(ipAddress).join(), System.currentTimeMillis());

            String isVpn = ChatColor.translateAlternateColorCodes(
                    '&',
                    messages.getString("messages.info.not-vpn")
            );
            if (info.getVpnStatus() == com.github.gerolndnr.connectionguard.core.lookup.ProviderVote.Status.POSITIVE) {
                isVpn = ChatColor.translateAlternateColorCodes(
                        '&',
                        messages.getString("messages.info.is-vpn")
                );
            }

            if (info.getVpnStatus() == com.github.gerolndnr.connectionguard.core.lookup.ProviderVote.Status.UNKNOWN) isVpn = "UNKNOWN";

            for (String line : messages.getStringList("messages.info.text")) {
                commandSender.sendMessage(
                        ChatColor.translateAlternateColorCodes(
                                '&',
                                line.replace("%INPUT%", queriedInput)
                                        .replace("%COUNTRY%", info.getCountry())
                                        .replace("%CITY%", info.getCity())
                                        .replace("%ISP%", info.getIsp())
                                        .replace("%IS_VPN%", isVpn)
                                        .replace("%IP%", ipAddress)
                        )
                );
            }
        });

        return true;
    }

    private boolean clearCache(CommandSender commandSender, String entry) {
        final com.github.gerolndnr.connectionguard.core.messages.MessageCatalog messages = ConnectionGuard.getMessages();
        // Async, because InetAddress.getByName could affect the main thread (used to determine, if it is a valid hostname/ip address)
        CompletableFuture.runAsync(() -> {
            String ipAddress;
            String queriedInput;

            if (ConnectionGuardBungeePlugin.getInstance().getProxy().getPlayer(entry) != null) {
                ProxiedPlayer player = ConnectionGuardBungeePlugin.getInstance().getProxy().getPlayer(entry);
                ipAddress = player.getAddress().getHostName();
                queriedInput = player.getName();
            } else {
                try {
                    ProxiedPlayer player = ConnectionGuardBungeePlugin.getInstance().getProxy().getPlayer(UUID.fromString(entry));
                    ipAddress = ConnectionGuardBungeePlugin.getInstance().getProxy().getPlayer(UUID.fromString(entry)).getAddress().getHostName();
                    queriedInput = player.getName();
                } catch (Exception e) {
                    try {
                        ipAddress = InetAddress.getByName(entry).getHostAddress();
                        queriedInput = ipAddress;
                    } catch (UnknownHostException ex) {
                        commandSender.sendMessage(
                                ChatColor.translateAlternateColorCodes(
                                        '&',
                                        messages.getString("messages.invalid-argument")
                                )
                        );
                        return;
                    }
                }
            }

            ConnectionGuard.getCacheProvider().removeGeoResult(ipAddress);
            ConnectionGuard.getCacheProvider().removeVpnResult(ipAddress);
            commandSender.sendMessage(
                    ChatColor.translateAlternateColorCodes(
                            '&',
                            messages.getString("command.clear.clear-specific")
                                    .replace("%ENTRY%", queriedInput)
                    )
            );
        });

        return true;
    }

    private boolean clearCache(CommandSender commandSender) {
        final com.github.gerolndnr.connectionguard.core.messages.MessageCatalog messages = ConnectionGuard.getMessages();
        ConnectionGuard.getCacheProvider().removeAllVpnResults();
        ConnectionGuard.getCacheProvider().removeAllGeoResults();
        commandSender.sendMessage(ChatColor.translateAlternateColorCodes(
                '&',
                messages.getString("command.clear.clear-all")
        ));
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
        try { ConnectionGuardBungeePlugin.getInstance().reloadAllConfigs(); }
        catch (IllegalArgumentException | IllegalStateException rejected) {
            commandSender.sendMessage(new TextComponent(ConnectionGuard.getMessages().getString("command.reload-rejected"))); return true;
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
    public Iterable<String> onTabComplete(CommandSender commandSender, String[] strings) {
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
                for (ProxiedPlayer player : ConnectionGuardBungeePlugin.getInstance().getProxy().getPlayers()) {
                    proposals.add(player.getName());
                }
            }
            if (strings[0].equalsIgnoreCase("clear")) {
                proposals.add("1.1.1.1");
                for (ProxiedPlayer player : ConnectionGuardBungeePlugin.getInstance().getProxy().getPlayers()) {
                    proposals.add(player.getName());
                }
            }
        }
        return proposals;
    }
}
