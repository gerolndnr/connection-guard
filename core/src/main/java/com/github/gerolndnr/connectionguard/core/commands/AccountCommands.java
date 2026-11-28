package com.github.gerolndnr.connectionguard.core.commands;

import com.github.gerolndnr.connectionguard.core.ConnectionGuard;
import com.github.gerolndnr.connectionguard.core.account.AccountIntelligence;
import com.github.gerolndnr.connectionguard.core.account.AccountRecord;
import com.github.gerolndnr.connectionguard.core.lookup.LookupException;
import com.github.gerolndnr.connectionguard.core.messages.MessageCatalog;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Predicate;

/** /cg account status | /cg account &lt;name|UUID&gt;: read-only; a record informs staff and never changes access. */
public final class AccountCommands {
    public static final String PERMISSION = "connectionguard.command.account";
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC);
    private AccountCommands() { }

    public static boolean handle(String[] args, Predicate<String> permission, Consumer<String> reply) {
        if (args.length == 0 || !args[0].equalsIgnoreCase("account")) return false;
        MessageCatalog messages = ConnectionGuard.getMessages();
        if (!permission.test(PERMISSION)) { reply.accept(messages.getString("ops.permission")); return true; }
        if (args.length != 2) { reply.accept(messages.getString("ops.account-usage")); return true; }
        if (args[1].equalsIgnoreCase("status")) { reply.accept(AccountIntelligence.describe()); return true; }
        if (!AccountIntelligence.enabled()) { reply.accept(messages.getString("ops.account-off")); return true; }
        Optional<UUID> target = parse(args[1]);
        if (!target.isPresent()) target = AccountIntelligence.recent(args[1]);
        if (!target.isPresent()) { reply.accept(messages.getString("ops.account-unknown")); return true; }
        UUID uuid = target.get();
        if (uuid.version() != 4) { reply.accept(messages.getString("ops.account-uncheckable")); return true; }
        AccountIntelligence.lookup(uuid).whenComplete((record, error) -> {
            if (error != null) { reply.accept(messages.text("ops.account-unavailable", LookupException.reason(error))); return; }
            for (String line : lines(messages, record)) reply.accept(line);
        });
        return true;
    }

    static List<String> lines(MessageCatalog messages, AccountRecord record) {
        List<String> out = new ArrayList<>();
        if (record.bansInForce == 0) out.add(messages.text("ops.account-clean", record.bansTotal));
        else {
            out.add(messages.text("ops.account-summary", record.bansInForce, record.listsInForce,
                    record.categoriesInForce.isEmpty() ? "-" : String.join(", ", record.categoriesInForce)));
            if (record.activeBans.isEmpty()) out.add(messages.getString("ops.account-withheld"));
            for (AccountRecord.Ban ban : record.activeBans)
                out.add(messages.text("ops.account-ban", ban.list, ban.categories.isEmpty() ? "-" : String.join(", ", ban.categories),
                        ban.createdAt == null ? "?" : DAY.format(Instant.ofEpochMilli(ban.createdAt)),
                        ban.expiresAt == null ? "-" : DAY.format(Instant.ofEpochMilli(ban.expiresAt)),
                        ban.reason == null ? "-" : ban.reason));
            out.add(messages.getString("ops.account-not-proof"));
        }
        if (record.profileUrl != null) out.add(messages.text("ops.account-profile", record.profileUrl));
        return out;
    }

    private static Optional<UUID> parse(String text) {
        try {
            if (text.matches("[0-9a-fA-F]{32}"))
                text = text.substring(0, 8) + "-" + text.substring(8, 12) + "-" + text.substring(12, 16) + "-" + text.substring(16, 20) + "-" + text.substring(20);
            return text.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}") ? Optional.of(UUID.fromString(text)) : Optional.empty();
        } catch (IllegalArgumentException invalid) { return Optional.empty(); }
    }
}
