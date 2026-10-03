package org.ngengine.nostr4j.rtc;

import static org.junit.Assert.*;

import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import org.ngengine.nostr4j.RTCSettings;
import org.ngengine.nostr4j.keypair.NostrKeyPair;
import org.ngengine.nostr4j.rtc.signal.NostrRTCLocalPeer;
import org.ngengine.nostr4j.rtc.signal.NostrRTCPeer;
import org.ngengine.nostr4j.signer.NostrKeyPairSigner;
import org.ngengine.platform.AsyncExecutor;
import org.ngengine.platform.AsyncTask;
import org.ngengine.platform.NGEPlatform;
import org.ngengine.platform.NGEUtils;
import org.ngengine.platform.jvm.JVMAsyncPlatform;
import org.ngengine.platform.transport.WebsocketTransport;
import org.ngengine.platform.transport.WebsocketTransportListener;

/** Receive-only TURN must subscribe before an application sends its first request. */
public class TestNostrRTCForcedTurnBootstrap {

    @Test
    public void forcedConnectedSocketBootstrapsReceiveWithoutWriting() throws Exception {
        check(true, false, true);
    }

    @Test
    public void ordinaryConnectedSocketDoesNotBootstrapTurn() throws Exception {
        check(false, false, false);
    }

    @Test
    public void ordinaryFallbackStillBootstrapsReceiveWithoutWriting() throws Exception {
        check(false, true, true);
    }

    private void check(boolean force, boolean fallback, boolean expected) throws Exception {
        Field platformField = NGEPlatform.class.getDeclaredField("platform");
        platformField.setAccessible(true);
        Object previous = platformField.get(null);
        CountingPlatform platform = new CountingPlatform();
        platformField.set(null, platform);
        RTCSettings settings = RTCSettings.getDefault("receive-bootstrap", "protocol").withStunServers(Collections.emptyList());
        try (NostrKeyPair keys = new NostrKeyPair(); NostrTURNPool pool = new NostrTURNPool()) {
            NostrRTCLocalPeer local = new NostrRTCLocalPeer(
                settings,
                NostrKeyPairSigner.generate(),
                "local",
                keys,
                "ws://test.invalid/turn"
            );
            NostrRTCPeer remote = new NostrRTCPeer(
                NGEUtils.awaitNoThrow(NostrKeyPairSigner.generate().getPublicKey()),
                "receive-bootstrap",
                "protocol",
                "remote",
                keys.getPublicKey(),
                "ws://test.invalid/turn"
            );
            AsyncExecutor executor = platform.newAsyncExecutor("receive-bootstrap");
            NostrRTCSocket socket = new NostrRTCSocket(executor, remote, keys, local, settings, pool);
            try {
                set(socket, "connected", true);
                set(socket, "turnFallbackAllowed", fallback);
                socket.setForceTURN(force);
                NostrRTCChannel channel = socket.createChannel("receive-only");
                channel.setChannel(null);
                assertEquals("bootstrap occurs before any application write", expected, platform.created.get() > 0);
                socket.close();
                assertTrue(channel.isClosed());
            } finally {
                socket.close();
                executor.close();
            }
        } finally {
            platformField.set(null, previous);
        }
    }

    private static void set(Object value, String name, boolean enabled) throws Exception {
        Field field = value.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.setBoolean(value, enabled);
    }

    private static final class CountingPlatform extends JVMAsyncPlatform {

        final AtomicInteger created = new AtomicInteger();

        @Override
        public WebsocketTransport newTransport() {
            created.incrementAndGet();
            return new WebsocketTransport() {
                @Override
                public void setMaxMessageSize(int size) {}

                @Override
                public int getMaxMessageSize() {
                    return 1024 * 1024;
                }

                @Override
                public AsyncTask<Void> close(String reason) {
                    return AsyncTask.completed(null);
                }

                @Override
                public AsyncTask<Void> connect(String url) {
                    return AsyncTask.failed(new IllegalStateException("test-only unavailable transport"));
                }

                @Override
                public AsyncTask<Void> send(String text) {
                    return AsyncTask.completed(null);
                }

                @Override
                public AsyncTask<Void> sendBinary(ByteBuffer bytes) {
                    return AsyncTask.completed(null);
                }

                @Override
                public void addListener(WebsocketTransportListener listener) {}

                @Override
                public void removeListener(WebsocketTransportListener listener) {}

                @Override
                public boolean isConnected() {
                    return false;
                }
            };
        }
    }
}
