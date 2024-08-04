package com.github.gerolndnr.connectionguard.velocity.listener;

import com.velocitypowered.api.event.Continuation;
import com.velocitypowered.api.event.ResultedEvent;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PreLoginEvent;

public class ConnectionGuardVelocityListener {
    @Subscribe
    public void onPreLogin(PreLoginEvent preLoginEvent, Continuation continuation) {

    }
}
