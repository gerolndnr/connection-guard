// SPDX-License-Identifier: AGPL-3.0-or-later
package com.github.gerolndnr.connectionguard.addons.libertybans;
import com.github.gerolndnr.connectionguard.api.v1.*;
import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.*;
import com.velocitypowered.api.proxy.ProxyServer;
@Plugin(id="connection-guard-libertybans",name="Connection Guard LibertyBans",version="0.1.0-dev",dependencies={@Dependency(id="connection-guard"),@Dependency(id="libertybans",optional=true)})
public final class VelocityAddon {
    private final ProxyServer server;
    private volatile boolean live;
    private AdmissionRegistration registration;
    @Inject public VelocityAddon(ProxyServer server) { this.server=server; }
    @Subscribe public void initialize(ProxyInitializeEvent event) {
        PluginContainer container=server.getPluginManager().getPlugin("libertybans").orElse(null);
        Object nativePlugin=container==null?null:container.getInstance().orElse(null);
        boolean selected=nativePlugin!=null && nativePlugin.getClass().getName().equals("space.arim.libertybans.env.velocity.plugin.VelocityPlugin") && container.getDescription().getVersion().filter("1.1.4"::equals).isPresent();
        live=true;
        registration=ConnectionGuardApi.registerAdmissionHook(LibertyBansReader.ID,new LibertyBansReader(selected?nativePlugin.getClass().getClassLoader():null,()->live && selected));
    }
    @Subscribe public void shutdown(ProxyShutdownEvent event) { live=false; if(registration!=null) registration.close(); }
}
