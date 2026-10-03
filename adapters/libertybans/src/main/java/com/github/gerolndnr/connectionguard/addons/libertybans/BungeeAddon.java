// SPDX-License-Identifier: AGPL-3.0-or-later
package com.github.gerolndnr.connectionguard.addons.libertybans;
import com.github.gerolndnr.connectionguard.api.v1.*;
import net.md_5.bungee.api.plugin.Plugin;
public final class BungeeAddon extends Plugin {
    private volatile boolean live;
    private AdmissionRegistration registration;
    @Override public void onEnable() {
        Plugin nativePlugin=getProxy().getPluginManager().getPlugin("LibertyBans");
        boolean selected=nativePlugin!=null && nativePlugin.getClass().getName().equals("space.arim.libertybans.env.bungee.plugin.BungeePlugin") && "1.1.4".equals(nativePlugin.getDescription().getVersion());
        live=true;
        registration=ConnectionGuardApi.registerAdmissionHook(LibertyBansReader.ID,new LibertyBansReader(selected?nativePlugin.getClass().getClassLoader():null,()->live && selected));
        getLogger().info("Read-only admission hook registered; select libertybans explicitly and reload Connection Guard after addon startup.");
    }
    @Override public void onDisable() { live=false; if(registration!=null) registration.close(); }
}
