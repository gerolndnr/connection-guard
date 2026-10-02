package com.github.gerolndnr.connectionguard.core.luckperms;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.model.user.User;
import net.luckperms.api.query.QueryOptions;
import com.github.gerolndnr.connectionguard.core.ConnectionGuard;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledFuture;

public class CGLuckPermsHelper {
    public static CompletableFuture<Boolean> hasPermission(UUID uuid, String permission) {
        return hasPermission(uuid, permission, null);
    }
    public static CompletableFuture<Boolean> hasPermission(UUID uuid, String permission, Object platformSubject) {
        if (uuid == null) return CompletableFuture.completedFuture(false);
        try { return check(LuckPermsProvider.get(), uuid, permission, platformSubject); }
        catch (IllegalStateException | LinkageError unavailable) { return CompletableFuture.completedFuture(false); }
    }
    static CompletableFuture<Boolean> check(LuckPerms luckPerms, UUID uuid, String permission, Object platformSubject) {
        CompletableFuture<Boolean> result = new CompletableFuture<>();
        ScheduledFuture<?> timeout = ConnectionGuard.getLookupRuntime().schedule(() -> result.complete(false), 750);
        result.whenComplete((value, error) -> timeout.cancel(false));
        try {
            User loaded = luckPerms.getUserManager().getUser(uuid);
            CompletableFuture<User> user = loaded != null ? CompletableFuture.completedFuture(loaded)
                    : luckPerms.getUserManager().loadUser(uuid);
            user.whenComplete((resolved, error) -> {
                try {
                    if (error != null || resolved == null || result.isDone()) { result.complete(false); return; }
                    QueryOptions options = platformSubject != null
                            ? luckPerms.getContextManager().getQueryOptions(platformSubject)
                            : luckPerms.getContextManager().getQueryOptions(resolved)
                                .orElse(luckPerms.getContextManager().getStaticQueryOptions());
                    result.complete(resolved.getCachedData().getPermissionData(options).checkPermission(permission).asBoolean());
                } catch (RuntimeException | LinkageError failure) { result.complete(false); }
                finally { if (loaded == null && resolved != null) luckPerms.getUserManager().cleanupUser(resolved); }
            });
        } catch (RuntimeException | LinkageError failure) { result.complete(false); }
        return result;
    }
    public static boolean isAvailable() {
        try { return LuckPermsProvider.get() != null; }
        catch (IllegalStateException | LinkageError unavailable) { return false; }
    }
}
