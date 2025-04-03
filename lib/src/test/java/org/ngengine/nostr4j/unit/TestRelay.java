package org.ngengine.nostr4j.unit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Before;
import org.junit.Test;
import org.ngengine.nostr4j.NostrRelay;
import org.ngengine.nostr4j.listeners.NostrRelayListener;

public class TestRelay {
     
    private static final String TEST_RELAY_URL = "wss://nostr.rblb.it";
     
    private NostrRelay relay;
    
    @Before
    public void setUp() {
        relay = new NostrRelay(TEST_RELAY_URL);
    }
    
    @Test
    public void testRelayConnection() throws Exception {
        final CountDownLatch connectionLatch = new CountDownLatch(1);
        final AtomicBoolean connected = new AtomicBoolean(false);
        
        relay.addListener(new NostrRelayListener() {
            @Override
            public void onRelayConnect(NostrRelay relay) {
                connected.set(true);
                connectionLatch.countDown();
            }
            
            @Override
            public void onRelayMessage(NostrRelay relay, List<Object> message) {
                // Not needed for this test
            }
        });
        
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
        
        relay.addListener(new NostrRelayListener() {
            @Override
            public void onRelayConnect(NostrRelay relay) {
                connectionEvents.incrementAndGet();
                connectLatch.countDown();
            }
            
            @Override
            public void onRelayMessage(NostrRelay relay, List<Object> message) {
                // Not needed for this test
            }
        });
        
        // Test initial connection
        relay.connect();
        boolean connected = connectLatch.await(10, TimeUnit.SECONDS);
        assertTrue("Failed to connect to relay", connected);
        assertEquals("Should receive exactly one connection event", 1, connectionEvents.get());
        
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
        
        relay.setKeepAliveTime(45, TimeUnit.SECONDS);
        assertEquals(45, relay.getKeepAliveTime(TimeUnit.SECONDS));
    }
    
    @Test
    public void testAutoReconnectSettings() {
        assertTrue("Auto reconnect should be enabled by default", relay.isAutoReconnect());
        
        relay.setAutoReconnect(false);
        assertFalse("Auto reconnect should be disabled", relay.isAutoReconnect());
        
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
            relays[i].addListener(new NostrRelayListener() {
                @Override
                public void onRelayConnect(NostrRelay relay) {
                    latches[index].countDown();
                }
                
                @Override
                public void onRelayMessage(NostrRelay relay, List<Object> message) {
                    // Not needed for this test
                }
            });
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
        NostrRelayListener listener = new NostrRelayListener() {
            @Override
            public void onRelayConnect(NostrRelay relay) {}
            
            @Override
            public void onRelayMessage(NostrRelay relay, List<Object> message) {}
        };
        
        relay.addListener(listener);
        relay.removeListener(listener);
        
        // No assertion needed - just verifying no exceptions are thrown
    }
}
