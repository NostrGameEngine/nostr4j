/**
 * BSD 3-Clause License
 *
 * Copyright (c) 2025, Riccardo Balbo
 */
package org.ngengine.nostr4j.rtc;

import static org.junit.Assert.*;

import java.time.Duration;
import java.time.Instant;
import org.junit.Test;
import org.ngengine.nostr4j.RTCSettings;
import org.ngengine.nostr4j.utils.ExponentialBackoff;

public class TestRTCResilienceSettings {

    @Test
    public void defaultsAndAllCopiesPreserveRetryConfiguration() {
        RTCSettings defaults = RTCSettings.getDefault("app", "proto");
        assertEquals(Duration.ofMillis(250), defaults.getConnectionRetryInitialDelay());
        assertEquals(Duration.ofSeconds(2), defaults.getConnectionRetryMaxDelay());
        assertEquals(4, defaults.getMaxConcurrentConnectionAttempts());
        RTCSettings configured = defaults
            .withConnectionRetryMaxDelay(Duration.ofSeconds(3))
            .withConnectionRetryInitialDelay(Duration.ofMillis(500))
            .withConnectionRetryMultiplier(3f)
            .withConnectionRetryJitter(0.2f)
            .withMaxConcurrentConnectionAttempts(2)
            .withConnectionMinimumLifetime(Duration.ofSeconds(10));
        RTCSettings copied = configured
            .withApplicationId("other")
            .withApplicationId("app")
            .withProtocolId("other")
            .withProtocolId("proto")
            .withMaxDirectPeers(64)
            .withMaxDirectPeers(16)
            .withPeerExpiration(configured.getPeerExpiration())
            .withP2pAttemptTimeout(configured.getP2pAttemptTimeout())
            .withQueuedSendTimeout(configured.getQueuedSendTimeout())
            .withRoomLoopInterval(configured.getRoomLoopInterval())
            .withSignalingLoopInterval(configured.getSignalingLoopInterval())
            .withSignalingAnnounceExpiration(configured.getSignalingAnnounceExpiration())
            .withDelayedCandidatesInterval(configured.getDelayedCandidatesInterval())
            .withStunServers(configured.getStunServers())
            .withSignalingRelays(configured.getSignalingRelays());
        assertEquals(configured, copied);
        assertEquals(configured.hashCode(), copied.hashCode());
        assertNotEquals(defaults, configured);
        assertEquals(configured, configured.clone());
    }

    @Test
    public void invalidRetryConfigurationIsRejected() {
        RTCSettings s = RTCSettings.getDefault("app", "proto");
        invalid(() -> s.withConnectionRetryInitialDelay(Duration.ZERO));
        invalid(() -> s.withConnectionRetryInitialDelay(Duration.ofNanos(1)));
        invalid(() -> s.withConnectionRetryMaxDelay(Duration.ofMillis(10)));
        invalid(() -> s.withConnectionRetryMaxDelay(Duration.ofDays(2)));
        invalid(() -> s.withConnectionRetryMultiplier(Float.NaN));
        invalid(() -> s.withConnectionRetryMultiplier(Float.POSITIVE_INFINITY));
        invalid(() -> s.withConnectionRetryMultiplier(1f));
        invalid(() -> s.withConnectionRetryJitter(-1f));
        invalid(() -> s.withConnectionRetryJitter(0.51f));
        invalid(() -> s.withConnectionRetryJitter(Float.NaN));
        invalid(() -> s.withMaxConcurrentConnectionAttempts(0));
        invalid(() -> s.withMaxConcurrentConnectionAttempts(65));
        invalid(() -> s.withConnectionMinimumLifetime(Duration.ZERO));
    }

    @Test
    public void terminalBackoffJitterNeverExceedsCapAndCooldownResets() {
        ExponentialBackoff b = new ExponentialBackoff(Duration.ofMillis(250), Duration.ofSeconds(2), Duration.ofSeconds(1), 2f);
        Instant now = Instant.EPOCH;
        for (int i = 0; i < 15; i++) {
            b.registerAttempt(now, 0.1f, 1d);
            assertTrue(b.getDelay(now).toMillis() <= 2000);
            assertTrue(b.getDelay(now).toMillis() >= 250);
            now = now.plusSeconds(3);
        }
        b.registerSuccess(now);
        now = now.plusSeconds(2);
        b.registerAttempt(now, 0.1f, 0d);
        assertTrue(b.getDelay(now).toMillis() >= 224 && b.getDelay(now).toMillis() <= 225);
    }

    private void invalid(Runnable action) {
        try {
            action.run();
            fail("Expected validation");
        } catch (IllegalArgumentException expected) {}
    }
}
