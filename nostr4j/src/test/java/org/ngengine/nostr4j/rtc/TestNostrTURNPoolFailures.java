/**
 * BSD 3-Clause License
 *
 * Copyright (c) 2025, Riccardo Balbo
 */
package org.ngengine.nostr4j.rtc;

import static org.junit.Assert.assertEquals;

import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.ngengine.nostr4j.keypair.NostrKeyPair;
import org.ngengine.nostr4j.rtc.signal.NostrRTCLocalPeer;
import org.ngengine.nostr4j.rtc.signal.NostrRTCPeer;
import org.ngengine.nostr4j.signer.NostrKeyPairSigner;
import org.ngengine.platform.AsyncTask;
import org.ngengine.platform.NGEPlatform;
import org.ngengine.platform.jvm.JVMAsyncPlatform;
import org.ngengine.platform.transport.WebsocketTransport;
import org.ngengine.platform.transport.WebsocketTransportListener;

public class TestNostrTURNPoolFailures {

    private static final String APP_ID = "turn-failure-app";
    private static final String PROTOCOL_ID = "turn-failure-proto";
    private static final String TURN_URL = "ws://turn-timeout.invalid/turn";

    private NGEPlatform previousPlatform;
    private ImmediateFailurePlatform failurePlatform;

    @Before
    public void setUp() throws Exception {
        previousPlatform = getInstalledPlatform();
        failurePlatform = new ImmediateFailurePlatform();
        installPlatform(failurePlatform);
    }

    @After
    public void tearDown() throws Exception {
        if (previousPlatform != null) {
            installPlatform(previousPlatform);
        }
    }

    @Test
    public void testFailedConnectDoesNotGetClosedByCleanupLoop() throws Exception {
        NostrTURNPool pool = new NostrTURNPool(24);
        try {
            NostrKeyPair room = new NostrKeyPair();
            NostrRTCLocalPeer local = new NostrRTCLocalPeer(
                NostrKeyPairSigner.generate(),
                Collections.emptyList(),
                APP_ID,
                PROTOCOL_ID,
                "turn-failure-local",
                room,
                TURN_URL
            );
            NostrRTCLocalPeer remoteLocal = new NostrRTCLocalPeer(
                NostrKeyPairSigner.generate(),
                Collections.emptyList(),
                APP_ID,
                PROTOCOL_ID,
                "turn-failure-remote",
                room,
                TURN_URL
            );
            NostrRTCPeer remote = new NostrRTCPeer(
                remoteLocal.getPubkey(),
                APP_ID,
                PROTOCOL_ID,
                remoteLocal.getSessionId(),
                room.getPublicKey(),
                TURN_URL
            );

            pool.connect(local, remote, TURN_URL, room, "primary", null);

            Thread.sleep(260L);

            assertEquals(0, failurePlatform.getCleanupCloseCount());
        } finally {
            pool.close();
        }
    }

    private static NGEPlatform getInstalledPlatform() throws Exception {
        Field field = NGEPlatform.class.getDeclaredField("platform");
        field.setAccessible(true);
        return (NGEPlatform) field.get(null);
    }

    private static void installPlatform(NGEPlatform platform) throws Exception {
        Field field = NGEPlatform.class.getDeclaredField("platform");
        field.setAccessible(true);
        field.set(null, platform);
    }

    private static final class ImmediateFailurePlatform extends JVMAsyncPlatform {

        private final CopyOnWriteArrayList<ImmediateFailureTransport> transports =
            new CopyOnWriteArrayList<ImmediateFailureTransport>();

        @Override
        public WebsocketTransport newTransport() {
            ImmediateFailureTransport transport = new ImmediateFailureTransport();
            transports.add(transport);
            return transport;
        }

        int getCleanupCloseCount() {
            int total = 0;
            for (ImmediateFailureTransport transport : transports) {
                total += transport.getCleanupCloseCount();
            }
            return total;
        }
    }

    private static final class ImmediateFailureTransport implements WebsocketTransport {

        private static final String CLEANUP_REASON = "TURN pool cleanup: transport not connected or unused";
        private final CopyOnWriteArrayList<WebsocketTransportListener> listeners =
            new CopyOnWriteArrayList<WebsocketTransportListener>();
        private final AtomicInteger cleanupCloseCount = new AtomicInteger();
      @Override
        public void setMaxMessageSize(int maxMessageSize) {
            
        }

        @Override
        public int getMaxMessageSize() {
            return 1024*1024*10;
        }
        @Override
        public AsyncTask<Void> close(String reason) {
            if (CLEANUP_REASON.equals(reason)) {
                cleanupCloseCount.incrementAndGet();
            }
            for (WebsocketTransportListener listener : listeners) {
                listener.onConnectionClosedByClient(reason);
            }
            return AsyncTask.completed(null);
        }

        @Override
        public AsyncTask<Void> connect(String url) {
            return AsyncTask.failed(new RuntimeException("request timed out"));
        }

        @Override
        public AsyncTask<Void> send(String message) {
            return AsyncTask.completed(null);
        }

        @Override
        public AsyncTask<Void> sendBinary(ByteBuffer payload) {
            return AsyncTask.completed(null);
        }

        @Override
        public void addListener(WebsocketTransportListener listener) {
            listeners.add(listener);
        }

        @Override
        public void removeListener(WebsocketTransportListener listener) {
            listeners.remove(listener);
        }

        @Override
        public boolean isConnected() {
            return false;
        }

        int getCleanupCloseCount() {
            return cleanupCloseCount.get();
        }
    }
}
