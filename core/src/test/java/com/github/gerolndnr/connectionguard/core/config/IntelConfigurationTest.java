package com.github.gerolndnr.connectionguard.core.config;

import com.github.gerolndnr.connectionguard.core.local.IntelSettings;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.yaml.snakeyaml.Yaml;
import java.nio.file.*;
import java.util.*;
import java.util.logging.*;
import static org.junit.jupiter.api.Assertions.*;

class IntelConfigurationTest {
    @TempDir Path directory;
    private static Object value(Map<String,Object> map,String path){Object current=map;for(String key:path.split("\\."))current=current instanceof Map?((Map<?,?>)current).get(key):null;return current;}
    @Test void newTemplateEnabledWithDailyUpdate72hAndRelayAllow(){Map<String,Object> yaml=new Yaml().load(getClass().getResourceAsStream("/config.yml"));IntelSettings s=new IntelSettings(k->value(yaml,k));assertTrue(s.enabled);assertEquals(24,s.updateHours);assertEquals(72,s.maxAgeHours);assertEquals(IntelSettings.Relay.ALLOW,s.relay);}
    @Test void absentOldSettingsRemainOffAndRejectInvalidRelayAndBounds(){IntelSettings s=new IntelSettings(k->null);assertFalse(s.enabled);assertEquals(IntelSettings.Relay.ALLOW,s.relay);Map<String,Object> v=new HashMap<>();v.put("provider.local.connectionguard-intel.relay","vpn");assertThrows(IllegalArgumentException.class,()->new IntelSettings(v::get));v.clear();v.put("provider.local.connectionguard-intel.max-age-hours",0);assertThrows(IllegalArgumentException.class,()->new IntelSettings(v::get));}
    @Test void noticeIsOnceOnlyAndNeverRewritesAnOldConfig()throws Exception{Path file=directory.resolve("config.yml");byte[] before="provider: {}\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);Files.write(file,before);List<String> lines=new ArrayList<>();Logger log=Logger.getAnonymousLogger();log.setUseParentHandlers(false);log.addHandler(new Handler(){public void publish(LogRecord r){lines.add(r.getMessage());}public void close(){}public void flush(){}});IntelProviderNotice.show(directory,true,log);IntelProviderNotice.show(directory,true,log);assertEquals(1,lines.size());assertTrue(lines.get(0).contains("provider.local.connectionguard-intel.enabled: true"));assertTrue(lines.get(0).contains("without player IPs"));assertArrayEquals(before,Files.readAllBytes(file));}
    @Test void freshInstallationConsumesNoticeMarkerQuietly()throws Exception{Logger log=Logger.getAnonymousLogger();List<String> lines=new ArrayList<>();log.setUseParentHandlers(false);log.addHandler(new Handler(){public void publish(LogRecord r){lines.add(r.getMessage());}public void close(){}public void flush(){}});IntelProviderNotice.show(directory,false,log);IntelProviderNotice.show(directory,true,log);assertTrue(lines.isEmpty());}

    @Test void startupDoesNotReadOrVerifySavedIntelButReloadStillRejectsCorruptData()throws Exception{
        Path data=directory.toRealPath(),cache=data.resolve("local-data/connectionguard-intel");Files.createDirectories(cache);
        String generation=String.join("",Collections.nCopies(64,"0"));Path bundle=cache.resolve(generation);Files.createDirectories(bundle);
        Files.write(cache.resolve("current.json"),("{\"generation\":\""+generation+"\",\"fetchedAt\":"+System.currentTimeMillis()+"}").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Files.write(bundle.resolve("manifest.json"),"not-a-manifest".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Map<String,Object> v=new HashMap<>();v.put("provider.local.connectionguard-intel.enabled",true);v.put("provider.local.connectionguard-intel.update-hours",0);v.put("provider.geo.service","Disabled");
        ProviderConfiguration boot=ProviderConfiguration.forStartup(v::get,Collections.emptyList(),data,null);
        assertTrue(boot.intelBootstrapPending);assertEquals(com.github.gerolndnr.connectionguard.core.lookup.FailureReason.NO_EVIDENCE,boot.intelSnapshot.readiness(System.currentTimeMillis()));
        assertThrows(IllegalArgumentException.class,boot::refreshLocal);
        assertThrows(IllegalArgumentException.class,()->new ProviderConfiguration(v::get,Collections.emptyList(),data));
    }
    @Test void disabledIntelDoesNotScheduleBootstrapEvenAtStartup()throws Exception{
        ProviderConfiguration boot=ProviderConfiguration.forStartup(k->null,Collections.emptyList(),directory.toRealPath(),null);
        assertFalse(boot.intelBootstrapPending);assertNull(boot.intelStore);
    }
}
