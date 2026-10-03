// SPDX-License-Identifier: MIT
package com.github.gerolndnr.connectionguard.addons.limbo;

import com.github.gerolndnr.connectionguard.core.challenge.ChallengeSession;
import com.github.gerolndnr.connectionguard.core.challenge.ChallengeSettings;
import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import net.elytrium.limboapi.api.Limbo;
import net.elytrium.limboapi.api.LimboFactory;
import net.elytrium.limboapi.api.LimboSessionHandler;
import net.elytrium.limboapi.api.chunk.Dimension;
import net.elytrium.limboapi.api.chunk.VirtualWorld;
import net.elytrium.limboapi.api.event.LoginLimboRegisterEvent;
import net.elytrium.limboapi.api.material.Block;
import net.elytrium.limboapi.api.player.GameMode;
import net.elytrium.limboapi.api.player.LimboPlayer;
import net.kyori.adventure.text.Component;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** Public MIT API transport only: no native private-state reflection, cache mutation or fake completion. */
final class NativeLimboTransport implements VelocityChallengeAddon.Transport {
    private final VelocityChallengeAddon gate;
    private final ProxyServer server;
    private final Limbo limbo;
    private final LimboFactory factory;
    private volatile boolean live=true;
    NativeLimboTransport(VelocityChallengeAddon gate, Object nativePlugin, ProxyServer server, ChallengeSettings settings) {
        this.gate=gate; this.server=server;
        factory=(LimboFactory)nativePlugin;
        VirtualWorld world=factory.createVirtualWorld(Dimension.OVERWORLD, 0.5, 65, 0.5, 0, 0);
        world.setBlock(0, 64, 0, factory.createSimpleBlock(Block.STONE)); world.fillSkyLight(15);
        limbo=factory.createLimbo(world).setName("Connection Guard verification").setGameMode(GameMode.ADVENTURE)
                .setReadTimeout((settings.timeoutSeconds+5)*1000).setShouldRejoin(false).setShouldRespawn(false)
                .setShouldUpdateTags(false).setReducedDebugInfo(true).setViewDistance(2).setSimulationDistance(2);
        server.getEventManager().register(gate, this);
    }
    @Subscribe(order=PostOrder.LAST) public void register(LoginLimboRegisterEvent event) {
        if (!gate.required()) return;
        Player player=event.getPlayer();
        // This is pre-backend queue registration, not a post-login or completion callback.
        event.addOnJoinCallback(() -> {
            if (!gate.required() && live) { factory.passLoginLimbo(player); return; }
            ChallengeSession session=live ? gate.begin(player) : null;
            if (session==null) { VelocityChallengeAddon.refuse(player); return; }
            try { limbo.spawnPlayer(player, new Handler(player, session)); }
            catch (RuntimeException | LinkageError failure) { gate.forget(player, session); VelocityChallengeAddon.refuse(player); }
        });
    }
    private final class Handler implements LimboSessionHandler {
        private final Player player;
        private final ChallengeSession session;
        private LimboPlayer nativePlayer;
        private ScheduledFuture<?> expiry;
        Handler(Player player, ChallengeSession session) { this.player=player; this.session=session; }
        @Override public void onSpawn(Limbo server, LimboPlayer nativePlayer) {
            this.nativePlayer=nativePlayer;
            try {
                String digits=session.displayText(player);
                if (!live || nativePlayer.getProxyPlayer()!=player || digits==null) { fail(); return; }
                nativePlayer.disableFalling();
                BufferedImage image=new BufferedImage(128, 128, BufferedImage.TYPE_INT_RGB);
                Graphics2D graphics=image.createGraphics();
                try {
                    graphics.setColor(Color.WHITE); graphics.fillRect(0, 0, 128, 128);
                    graphics.setColor(Color.BLACK); graphics.setFont(new Font(Font.MONOSPACED, Font.BOLD, 24));
                    graphics.drawString(digits, 20, 70);
                } finally { graphics.dispose(); }
                nativePlayer.sendImage(image, true);
                player.sendMessage(Component.text("Connection Guard: type the six digits shown on the map in chat. Passing continues the ordinary access checks."));
                long remaining=session.remainingMillis(player);
                if (remaining==0) { fail(); return; }
                // Deadline remains armed while a pass proceeds through normal LoginEvent checks.
                expiry=nativePlayer.getScheduledExecutor().scheduleAtFixedRate(this::check, 100, 100, TimeUnit.MILLISECONDS);
            } catch (RuntimeException | LinkageError failure) { fail(); }
        }
        @Override public void onChat(String message) {
            if (nativePlayer==null || nativePlayer.getProxyPlayer()!=player || !live) { fail(); return; }
            if (!gate.required()) { check(); return; }
            ChallengeSession.Answer answer=session.answer(player, message);
            if (answer==ChallengeSession.Answer.ACCEPTED) {
                // Only actual matching onChat input advances this owned live session.
                if (!gate.passed(player)) { fail(); return; }
                try { nativePlayer.disconnect(); }
                catch (RuntimeException | LinkageError failure) { fail(); }
            } else if (session.state()==ChallengeSession.State.PENDING) {
                player.sendMessage(Component.text("Incorrect verification response. Try the six digits on the map again."));
            } else fail();
        }
        @Override public void onDisconnect() {
            if (!session.isPassed(player)) {
                if (expiry!=null) expiry.cancel(false);
                gate.forget(player, session);
            }
        }
        private void check() {
            // OBSERVE suppresses this addon's enforcement too; it never manufactures a pass.
            if (!gate.required() && player.isActive()) {
                boolean owned=gate.forget(player, session);
                if (expiry!=null) expiry.cancel(false);
                if (owned) nativePlayer.disconnect();
                return;
            }
            ChallengeSession.State state=session.state();
            if (state!=ChallengeSession.State.PENDING && state!=ChallengeSession.State.PASSED) fail();
        }
        private void fail() {
            boolean owned=gate.forget(player, session);
            if (expiry!=null) expiry.cancel(false);
            // ServerConnectedEvent/disconnect already consumed the receipt: no delayed kick of an admitted player.
            if (owned && player.isActive()) VelocityChallengeAddon.refuse(player);
        }
    }
    @Override public void close() {
        live=false; server.getEventManager().unregisterListener(gate, this); limbo.dispose();
    }
}
