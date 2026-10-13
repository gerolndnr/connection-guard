package com.github.gerolndnr.connectionguard.core.commands;

import com.github.gerolndnr.connectionguard.core.ConnectionGuard;
import com.github.gerolndnr.connectionguard.core.lookup.ApiLookupBudget;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ProfileCommandsTest {
    private List<String> command(String... args) {List<String> output=new ArrayList<>();assertTrue(ProfileCommands.handle(args,p->true,output::add));return output;}
    @Test void previewPreservesActiveModeSourcesBudgetAndLookupStats() {
        Object draft=ConnectionGuard.getActiveDraft();boolean observe=ConnectionGuard.getSettings().observe;
        ApiLookupBudget.View before=ConnectionGuard.apiBudgetStatus();String stats=ConnectionGuard.lookupStats();
        assertTrue(command("profile","show","local-only").toString().contains("api-policy: LOCAL_ONLY"));
        assertTrue(command("profile","show","budgeted-api","123").toString().contains("api-daily-budget: 123"));
        assertTrue(command("profile","show","observe").toString().contains("mode: OBSERVE"));
        assertSame(draft,ConnectionGuard.getActiveDraft());assertEquals(observe,ConnectionGuard.getSettings().observe);
        assertEquals(before.limit,ConnectionGuard.apiBudgetStatus().limit);assertEquals(before.reserved,ConnectionGuard.apiBudgetStatus().reserved);
        assertEquals(stats,ConnectionGuard.lookupStats());
    }
    @Test void permissionAndInvalidInputProduceNoOverlay() {
        List<String> denied=new ArrayList<>();assertTrue(ProfileCommands.handle(new String[]{"profile","show","local-only"},p->false,denied::add));
        assertEquals(Collections.singletonList(ConnectionGuard.getMessages().getString("ops.permission")),denied);
        for(String[] args:new String[][]{{"profile","show","../../private"},{"profile","show","budgeted-api","0"},
                {"profile","show","budgeted-api","2147483648"},{"profile","show","observe","1"},{"profile","activate","local-only"}}) {
            List<String> output=command(args);assertEquals(1,output.size());assertEquals(ConnectionGuard.getMessages().getString("ops.profile-usage"),output.get(0));
        }
    }
}
