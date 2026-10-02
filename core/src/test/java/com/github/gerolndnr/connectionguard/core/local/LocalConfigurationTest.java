package com.github.gerolndnr.connectionguard.core.local;

import com.github.gerolndnr.connectionguard.core.config.*;
import com.github.gerolndnr.connectionguard.core.commands.LocalDataCommands;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class LocalConfigurationTest {
    @TempDir Path directory;
    private Map<String, Object> source(String id, String type) {
        Map<String, Object> result = new HashMap<>(); result.put("id", id); result.put("type", type); result.put("source", "Synthetic fixture");
        result.put("license", "MIT"); result.put("notice", "Connection Guard contributors"); return result;
    }
    @Test void disabledLocalDefaultsDoNotReadFilesOrChangeLegacyProviders() {
        ProviderConfiguration draft = new ProviderConfiguration(path -> null, Collections.emptyList());
        assertNull(draft.localStore); assertTrue(draft.localSnapshots.isEmpty()); assertEquals(0, draft.localUpdateHours);
    }
    @Test void localOnlyHasNoApiTransportAndHostingAsnAreNotThresholdVotes() throws Exception {
        Map<String, Object> values = new HashMap<>(); values.put("provider.vpn.local.enabled", true); values.put("provider.geo.service", "Disabled");
        values.put("provider.local.sources", Arrays.asList(source("vpn", "VPN"), source("hosting", "HOSTING"), source("asn", "ASN")));
        ProviderConfiguration draft = new ProviderConfiguration(values::get, Collections.singletonList("local"), directory.toRealPath());
        assertNull(draft.geo); assertEquals(3, draft.providers.size()); assertEquals(1, draft.providers.stream().filter(provider -> provider.isVoting()).count());
        values.put("required-positive-flags", 2);
        assertThrows(IllegalArgumentException.class, () -> new ProviderConfiguration(values::get, Collections.singletonList("local"), directory));
    }
    @Test void invalidMissingDuplicateExcessiveSourcesAndSchedulersAreRejected() {
        Map<String, Object> values = new HashMap<>(); values.put("provider.vpn.local.enabled", true);
        assertThrows(IllegalArgumentException.class, () -> GuardSettings.read(values::get, Collections.singletonList("local")));
        values.put("provider.local.sources", Arrays.asList(source("same", "VPN"), source("same", "TOR")));
        assertThrows(IllegalArgumentException.class, () -> new LocalDataSettings(values::get));
        values.put("provider.local.sources", Collections.nCopies(9, source("vpn", "VPN")));
        assertThrows(IllegalArgumentException.class, () -> new LocalDataSettings(values::get));
        values.put("provider.local.sources", Collections.singletonList(source("vpn", "VPN"))); values.put("provider.local.update-hours", -1);
        assertThrows(IllegalArgumentException.class, () -> new LocalDataSettings(values::get));
        values.put("provider.local.update-hours", 169);
        assertThrows(IllegalArgumentException.class, () -> new LocalDataSettings(values::get));
        values.put("provider.local.update-hours", 0); values.put("provider.geo.service", "Local");
        assertThrows(IllegalArgumentException.class, () -> new LocalDataSettings(values::get));
        values.put("provider.geo.service", "Disabled");
        assertThrows(IllegalArgumentException.class, () -> new ProviderConfiguration(values::get, Collections.singletonList("local")));
    }
    @Test void localCommandsRequireTheirOwnPermissionBeforeAnyWork() {
        List<String> replies = new ArrayList<>();
        assertTrue(LocalDataCommands.handle(new String[]{"local", "update", "vpn"}, permission -> false, replies::add));
        assertEquals(Collections.singletonList("You do not have permission for this command."), replies);
    }
}
