package com.github.gerolndnr.connectionguard.core.config;

import com.github.gerolndnr.connectionguard.core.messages.MessageCatalog;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ReloadFailuresTest {
    @Test void validatorReasonsAreShownButInputCarryingExceptionsAreNot() {
        MessageCatalog messages = MessageCatalog.defaults("en");
        assertEquals("Reason: failure-policy.vpn must be OPEN, CLOSED or OBSERVE.",
                ReloadFailures.reason(new IllegalArgumentException("failure-policy.vpn must be OPEN, CLOSED or OBSERVE."), messages));
        assertEquals("Grund: x", ReloadFailures.reason(new IllegalStateException("x"), MessageCatalog.defaults("de")));
        assertNull(ReloadFailures.reason(new NumberFormatException("For input string: \"secret\""), messages));
        assertNull(ReloadFailures.reason(new IllegalArgumentException("line\nbreak"), messages));
        assertNull(ReloadFailures.reason(new IllegalArgumentException((String) null), messages));
        assertNull(ReloadFailures.reason(new java.io.IOException("parser said: key: value"), messages));
    }
}
