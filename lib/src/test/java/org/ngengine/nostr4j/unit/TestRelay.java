/**
 * BSD 3-Clause License
 * 
 * Copyright (c) 2025, Riccardo Balbo
 * 
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 * 
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 * 
 * 3. Neither the name of the copyright holder nor the names of its
 *    contributors may be used to endorse or promote products derived from
 *    this software without specific prior written permission.
 * 
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
 * FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
 * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
 * OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package org.ngengine.nostr4j.unit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Before;
import org.junit.Test;
import org.ngengine.nostr4j.NostrRelay;
import org.ngengine.nostr4j.NostrRelayLifecycleManager;
import org.ngengine.nostr4j.listeners.NostrRelayComponent;
import org.ngengine.nostr4j.transport.NostrMessage;

public class TestRelay {

    private static final String TEST_RELAY_URL = "wss://nostr.rblb.it";

    private NostrRelay relay;

    @Before
    public void setUp() {
        relay = new NostrRelay(TEST_RELAY_URL);
        relay.addComponent(new NostrRelayLifecycleManager());
    }

    @Test
    public void testRelayConnection() throws Exception {
        final CountDownLatch connectionLatch = new CountDownLatch(1);
        final AtomicBoolean connected = new AtomicBoolean(false);

        relay.addComponent(
            new NostrRelayComponent() {
                @Override
                public boolean onRelayConnect(NostrRelay relay) {
                    connected.set(true);
                    connectionLatch.countDown();
                    return true;
                }

                @Override
                public boolean onRelayMessage(
                    NostrRelay relay,
                    List<Object> message
                ) {
                    return true;
                }

                @Override
                public boolean onRelayConnectRequest(NostrRelay relay) {
                    return true;
                }

                @Override
                public boolean onRelayError(NostrRelay relay, Throwable error) {
                    return true;
                }

                @Override
                public boolean onRelayLoop(
                    NostrRelay relay,
                    Instant nowInstant
                ) {
                    return true;
                }

                @Override
                public boolean onRelayDisconnect(
                    NostrRelay relay,
                    String reason,
                    boolean byClient
                ) {
                    return true;
                }

                @Override
                public boolean onRelaySend(
                    NostrRelay relay,
                    NostrMessage message
                ) {
                    return true;
                }

                @Override
                public boolean onRelayDisconnectRequest(
                    NostrRelay relay,
                    String reason
                ) {
                    return true;
                }
            }
        );

        relay.connect();

        // Wait for connection or timeout after 10 seconds
        boolean success = connectionLatch.await(10, TimeUnit.SECONDS);
        relay.disconnect("Test completed");

        assertTrue("Relay connection timed out", success);
        assertTrue("Relay should have connected", connected.get());
    }

    @Test
    public void testConnectionStateChanges() throws Exception {
        final CountDownLatch connectLatch = new CountDownLatch(1);
        final AtomicInteger connectionEvents = new AtomicInteger(0);

        relay.addComponent(
            new NostrRelayComponent() {
                @Override
                public boolean onRelayConnect(NostrRelay relay) {
                    connectionEvents.incrementAndGet();
                    connectLatch.countDown();
                    return true;
                }

                @Override
                public boolean onRelayMessage(
                    NostrRelay relay,
                    List<Object> message
                ) {
                    return true;
                }

                @Override
                public boolean onRelayConnectRequest(NostrRelay relay) {
                    return true;
                }

                @Override
                public boolean onRelayError(NostrRelay relay, Throwable error) {
                    return true;
                }

                @Override
                public boolean onRelayLoop(
                    NostrRelay relay,
                    Instant nowInstant
                ) {
                    return true;
                }

                @Override
                public boolean onRelayDisconnect(
                    NostrRelay relay,
                    String reason,
                    boolean byClient
                ) {
                    return true;
                }

                @Override
                public boolean onRelaySend(
                    NostrRelay relay,
                    NostrMessage message
                ) {
                    return true;
                }

                @Override
                public boolean onRelayDisconnectRequest(
                    NostrRelay relay,
                    String reason
                ) {
                    return true;
                }
            }
        );

        // Test initial connection
        relay.connect();
        boolean connected = connectLatch.await(10, TimeUnit.SECONDS);
        assertTrue("Failed to connect to relay", connected);
        assertEquals(
            "Should receive exactly one connection event",
            1,
            connectionEvents.get()
        );

        // Test disconnect
        relay.disconnect("Test completed");

        // Verify state changes
        assertFalse("Relay should be disconnected", relay.isConnected());
    }

    @Test
    public void testTimeoutSettings() {
        // Test timeout settings
        relay.setAckTimeout(30, TimeUnit.SECONDS);
        assertEquals(30, relay.getAckTimeout(TimeUnit.SECONDS));

        relay.setAckTimeout(1, TimeUnit.MINUTES);
        assertEquals(60, relay.getAckTimeout(TimeUnit.SECONDS));
        assertEquals(1, relay.getAckTimeout(TimeUnit.MINUTES));

        NostrRelayLifecycleManager lf = relay.getComponent(
            NostrRelayLifecycleManager.class
        );
        lf.setKeepAliveTime(45, TimeUnit.SECONDS);
        assertEquals(45, lf.getKeepAliveTime(TimeUnit.SECONDS));
    }

    @Test
    public void testAutoReconnectSettings() {
        assertTrue(
            "Auto reconnect should be enabled by default",
            relay.isAutoReconnect()
        );

        relay.setAutoReconnect(false);
        assertFalse(
            "Auto reconnect should be disabled",
            relay.isAutoReconnect()
        );

        relay.setAutoReconnect(true);
        assertTrue("Auto reconnect should be enabled", relay.isAutoReconnect());
    }

    @Test
    public void testConcurrentConnections() throws Exception {
        // Create multiple relays to the same endpoint
        final int RELAY_COUNT = 5;
        NostrRelay[] relays = new NostrRelay[RELAY_COUNT];
        CountDownLatch[] latches = new CountDownLatch[RELAY_COUNT];

        for (int i = 0; i < RELAY_COUNT; i++) {
            relays[i] = new NostrRelay(TEST_RELAY_URL);
            latches[i] = new CountDownLatch(1);

            final int index = i;
            relays[i].addComponent(
                    new NostrRelayComponent() {
                        @Override
                        public boolean onRelayConnect(NostrRelay relay) {
                            latches[index].countDown();
                            return true;
                        }

                        @Override
                        public boolean onRelayMessage(
                            NostrRelay relay,
                            List<Object> message
                        ) {
                            return true;
                        }

                        @Override
                        public boolean onRelayConnectRequest(NostrRelay relay) {
                            return true;
                        }

                        @Override
                        public boolean onRelayError(
                            NostrRelay relay,
                            Throwable error
                        ) {
                            return true;
                        }

                        @Override
                        public boolean onRelayLoop(
                            NostrRelay relay,
                            Instant nowInstant
                        ) {
                            return true;
                        }

                        @Override
                        public boolean onRelayDisconnect(
                            NostrRelay relay,
                            String reason,
                            boolean byClient
                        ) {
                            return true;
                        }

                        @Override
                        public boolean onRelaySend(
                            NostrRelay relay,
                            NostrMessage message
                        ) {
                            return true;
                        }

                        @Override
                        public boolean onRelayDisconnectRequest(
                            NostrRelay relay,
                            String reason
                        ) {
                            return true;
                        }
                    }
                );
        }

        // Connect all relays concurrently
        for (NostrRelay r : relays) {
            r.connect();
        }

        // Wait for all connections
        boolean allConnected = true;
        for (int i = 0; i < RELAY_COUNT; i++) {
            allConnected &= latches[i].await(10, TimeUnit.SECONDS);
        }

        // Clean up
        for (NostrRelay r : relays) {
            r.disconnect("Test completed");
        }

        assertTrue("Not all relays connected successfully", allConnected);
    }

    // @Test
    // public void testEdgeCases() {
    //     // Test URL handling - Note: This doesn't test actual connection
    //     NostrRelay invalidRelay = new NostrRelay("invalid://url");
    //     assertEquals("invalid://url", invalidRelay.getUrl());

    //     // Test with extreme timeout values
    //     relay.setAckTimeout(1, TimeUnit.MILLISECONDS); // Very small timeout
    //     assertEquals(1, relay.getAckTimeout(TimeUnit.MILLISECONDS));

    //     relay.setAckTimeout(Integer.MAX_VALUE/1000, TimeUnit.SECONDS); // Very large timeout
    //     assertTrue("Should handle large timeout values",
    //         relay.getAckTimeout(TimeUnit.MILLISECONDS) > 0);
    // }

    @Test
    public void testListenerManagement() {
        NostrRelayComponent listener = new NostrRelayComponent() {
            @Override
            public boolean onRelayConnect(NostrRelay relay) {
                return true;
            }

            @Override
            public boolean onRelayMessage(
                NostrRelay relay,
                List<Object> message
            ) {
                return true;
            }

            @Override
            public boolean onRelayConnectRequest(NostrRelay relay) {
                return true;
            }

            @Override
            public boolean onRelayError(NostrRelay relay, Throwable error) {
                return true;
            }

            @Override
            public boolean onRelayLoop(NostrRelay relay, Instant nowInstant) {
                return true;
            }

            @Override
            public boolean onRelayDisconnect(
                NostrRelay relay,
                String reason,
                boolean byClient
            ) {
                return true;
            }

            @Override
            public boolean onRelaySend(NostrRelay relay, NostrMessage message) {
                return true;
            }

            @Override
            public boolean onRelayDisconnectRequest(
                NostrRelay relay,
                String reason
            ) {
                return true;
            }
        };

        relay.addComponent(listener);
        relay.removeComponent(listener);
        // No assertion needed - just verifying no exceptions are thrown
    }
}
