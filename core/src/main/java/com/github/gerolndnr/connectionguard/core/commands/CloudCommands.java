package com.github.gerolndnr.connectionguard.core.commands;

import com.github.gerolndnr.connectionguard.core.cloud.CloudSync;
import java.io.IOException;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Predicate;

/** /cg cloud controls. Network/config work stays on the background Cloud worker. */
public final class CloudCommands {
    private CloudCommands() { }
    public static boolean handle(String[] args, Predicate<String> permission, Consumer<String> reply) {
        if (args.length == 0 || !args[0].equalsIgnoreCase("cloud")) return false;
        final com.github.gerolndnr.connectionguard.core.messages.MessageCatalog messages = com.github.gerolndnr.connectionguard.core.ConnectionGuard.getMessages();
        if (!permission.test("connectionguard.command.cloud")) { reply.accept(messages.getString("ops.permission")); return true; }
        String action = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "status";
        switch (action) {
            case "sync":
                CloudSync.SyncRequest request = CloudSync.requestSync();
                reply.accept(request.initialMessage);
                request.completion.thenAccept(message -> { if (message != null) reply.accept(message); });
                return true;
            case "status":
                CloudSync.describeLines().forEach(reply);
                CloudSync.linkUrl("command").ifPresent(url -> reply.accept(messages.text("cloud.link", url)));
                return true;
            case "link":
                if (!CloudSync.isRunning()) reply.accept(messages.getString("cloud.off-help"));
                else if (CloudSync.isLinked()) reply.accept(messages.getString("cloud.already-linked"));
                else reply.accept(CloudSync.linkUrl("command").map(url -> messages.text("cloud.link-expiry", url))
                            .orElse(messages.getString("cloud.registering-help")));
                return true;
            case "settings":
                try { CloudSync.describeSettings().forEach(reply); }
                catch (IllegalArgumentException invalid) { com.github.gerolndnr.connectionguard.core.cloud.PluginErrorReports.record(invalid, com.github.gerolndnr.connectionguard.core.cloud.PluginErrorReports.Context.COMMAND); reply.accept(messages.getString("cloud.local-error")); }
                return true;
            case "reset-settings":
                try { reply.accept(messages.translate(CloudSync.resetSettingsLocally())); }
                catch (IllegalStateException notReady) { com.github.gerolndnr.connectionguard.core.cloud.PluginErrorReports.record(notReady, com.github.gerolndnr.connectionguard.core.cloud.PluginErrorReports.Context.COMMAND); reply.accept(messages.getString("cloud.not-ready")); }
                catch (IllegalArgumentException invalid) { com.github.gerolndnr.connectionguard.core.cloud.PluginErrorReports.record(invalid, com.github.gerolndnr.connectionguard.core.cloud.PluginErrorReports.Context.COMMAND); reply.accept(messages.getString("cloud.local-error")); }
                return true;
            case "enable":
            case "disable":
                try { reply.accept(messages.translate(CloudSync.setDisabledByCommand(action.equals("disable")))); }
                catch (IOException | IllegalStateException failure) { com.github.gerolndnr.connectionguard.core.cloud.PluginErrorReports.record(failure, com.github.gerolndnr.connectionguard.core.cloud.PluginErrorReports.Context.COMMAND); reply.accept(messages.getString("cloud.change-error")); }
                return true;
            default:
                reply.accept(messages.getString("cloud.usage"));
                return true;
        }
    }
}
