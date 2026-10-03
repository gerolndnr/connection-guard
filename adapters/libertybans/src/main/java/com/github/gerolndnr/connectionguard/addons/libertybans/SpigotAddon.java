// SPDX-License-Identifier: AGPL-3.0-or-later
package com.github.gerolndnr.connectionguard.addons.libertybans;
import com.github.gerolndnr.connectionguard.api.v1.*;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
public final class SpigotAddon extends JavaPlugin {
    private AdmissionRegistration registration;
    @Override public void onEnable() {
        // Native plugin lookup occurs only in the platform lifecycle, never in lookup workers.
        Plugin nativePlugin=getServer().getPluginManager().getPlugin("LibertyBans");
        boolean selected=nativePlugin!=null && nativePlugin.getClass().getName().equals("space.arim.libertybans.env.spigot.plugin.SpigotPlugin") && "1.1.4".equals(nativePlugin.getDescription().getVersion());
        registration=ConnectionGuardApi.registerAdmissionHook(LibertyBansReader.ID,new LibertyBansReader(selected?nativePlugin.getClass().getClassLoader():null,()->isEnabled() && selected && nativePlugin.isEnabled()));
        getLogger().info("Read-only admission hook registered; select libertybans explicitly and reload Connection Guard after addon startup.");
    }
    @Override public void onDisable() { if(registration!=null) registration.close(); }
}
