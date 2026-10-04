package com.github.gerolndnr.connectionguard.core.commands;

import com.github.gerolndnr.connectionguard.core.cloud.CloudSync;
import java.io.IOException;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Predicate;

/** /cg cloud status|link|enable|disable. Changes are local only; nothing here waits on the network. */
public final class CloudCommands {
    private CloudCommands() { }
    public static boolean handle(String[] args, Predicate<String> permission, Consumer<String> reply) {
        if (args.length == 0 || !args[0].equalsIgnoreCase("cloud")) return false;
        if (!permission.test("connectionguard.command.cloud")) { reply.accept("You do not have permission for this command."); return true; }
        String action = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "status";
        switch (action) {
            case "status":
                CloudSync.describeLines().forEach(reply);
                CloudSync.linkUrl().ifPresent(url -> reply.accept("Link this server: " + url));
                return true;
            case "link":
                if (!CloudSync.isRunning()) reply.accept("Connection Guard Cloud is off. Turn it on with /cg cloud enable.");
                else if (CloudSync.isLinked()) reply.accept("This server is already linked. Manage it at https://app.connectionguard.net.");
                else reply.accept(CloudSync.linkUrl().map(url -> "Link this server: " + url + " (valid 24 hours)")
                            .orElse("Registering with the dashboard; the link appears within a minute. Run /cg cloud link again."));
                return true;
            case "settings":
                CloudSync.describeSettings().forEach(reply);
                return true;
            case "reset-settings":
                try { reply.accept(CloudSync.resetSettingsLocally()); }
                catch (IllegalStateException notReady) { reply.accept("Connection Guard is still starting; try again in a moment."); }
                return true;
            case "enable":
            case "disable":
                try { reply.accept(CloudSync.setDisabledByCommand(action.equals("disable"))); }
                catch (IOException | IllegalStateException failure) { reply.accept("Could not change the cloud setting; check file permissions in the plugin folder."); }
                return true;
            default:
                reply.accept("/cg cloud status | link | settings | reset-settings | enable | disable");
                return true;
        }
    }
}
