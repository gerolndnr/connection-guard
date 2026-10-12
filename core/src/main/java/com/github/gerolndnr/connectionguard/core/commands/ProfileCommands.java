package com.github.gerolndnr.connectionguard.core.commands;

import com.github.gerolndnr.connectionguard.core.ConnectionGuard;
import com.github.gerolndnr.connectionguard.core.messages.MessageCatalog;
import java.util.*;
import java.util.function.*;

/** Read-only ready-made overlays. Selecting a preview never activates or uploads it. */
public final class ProfileCommands {
    private ProfileCommands() { }
    public static boolean handle(String[] args, Predicate<String> permission, Consumer<String> reply) {
        if(args.length==0||!args[0].equalsIgnoreCase("profile")) return false;
        MessageCatalog messages=ConnectionGuard.getMessages();
        if(!permission.test("connectionguard.command.profile")){reply.accept(messages.getString("ops.permission"));return true;}
        if(args.length==1||args.length==2&&args[1].equalsIgnoreCase("list")) {
            reply.accept(messages.getString("ops.profile-list"));reply.accept(ConnectionGuard.apiLookupStatus());return true;
        }
        if(args.length<3||args.length>4||!args[1].equalsIgnoreCase("show")){reply.accept(messages.getString("ops.profile-usage"));return true;}
        String name=args[2].toLowerCase(Locale.ROOT);int cap=1000;
        if(args.length==4) {
            try { if(!name.equals("budgeted-api")||!args[3].matches("[1-9][0-9]{0,9}")) throw new IllegalArgumentException();
                cap=Integer.parseInt(args[3]); }
            catch(IllegalArgumentException invalid){reply.accept(messages.getString("ops.profile-usage"));return true;}
        }
        List<String> output=new ArrayList<>();
        switch(name) {
            case "local-only": output.add("lookup:");output.add("  api-policy: LOCAL_ONLY");output.add(messages.getString("ops.profile-local"));break;
            case "budgeted-api": output.add("lookup:");output.add("  api-policy: CONFIGURED");output.add("  api-daily-budget: "+cap);output.add(messages.getString("ops.profile-budget"));break;
            case "observe": output.add("operation:");output.add("  mode: OBSERVE");output.add(messages.getString("ops.profile-observe"));break;
            default:reply.accept(messages.getString("ops.profile-usage"));return true;
        }
        reply.accept(messages.getString("ops.profile-overlay"));output.forEach(reply);
        if(name.equals("budgeted-api")&&ConnectionGuard.getActiveDraft()!=null) reply.accept("Configured sources: "+ConnectionGuard.getActiveDraft().keys);
        reply.accept(messages.getString("ops.profile-network"));return true;
    }
}
