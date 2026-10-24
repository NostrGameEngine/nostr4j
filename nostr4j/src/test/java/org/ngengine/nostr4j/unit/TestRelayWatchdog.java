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
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.ngengine.nostr4j.NostrPool;
import org.ngengine.nostr4j.NostrRelay;
import org.ngengine.nostr4j.NostrRelayWatchdog;
import org.ngengine.nostr4j.event.SignedNostrEvent;
import org.ngengine.nostr4j.event.UnsignedNostrEvent;
import org.ngengine.nostr4j.pool.fetchpolicy.NostrWaitForEventFetchPolicy;
import org.ngengine.nostr4j.signer.NostrKeyPairSigner;
import org.ngengine.platform.AsyncExecutor;
import org.ngengine.platform.AsyncTask;
import org.ngengine.platform.NGEPlatform;
import org.ngengine.platform.jvm.JVMAsyncPlatform;
import org.ngengine.platform.transport.WebsocketTransport;
import org.ngengine.platform.transport.WebsocketTransportListener;

public class TestRelayWatchdog {

    private NGEPlatform previousPlatform;
    private TestPlatform platform;
    private NostrPool owner;
    private NostrRelay relay;
    private RecordingTransport transport;

    @Before
    public void setUp() throws Exception {
        Field field = NGEPlatform.class.getDeclaredField("platform");
        field.setAccessible(true);
        previousPlatform = (NGEPlatform) field.get(null);
        platform = new TestPlatform();
        field.set(null, platform);
        owner = new NostrPool();
        relay = new NostrRelay("wss://watchdog.test");
        owner.addRelay(relay).await();
        transport = platform.transport;
    }

    @After
    public void tearDown() throws Exception {
        try {
            if (owner != null) owner.clean();
            if (relay != null) relay.disconnect("test cleanup").await();
        } finally {
            Field field = NGEPlatform.class.getDeclaredField("platform");
            field.setAccessible(true);
            field.set(null, previousPlatform);
        }
    }

    @Test
    public void silentConnectionIsReconnectedAfterProbeTimeout() throws Exception {
        startProbe();
        platform.expireProbe();
        awaitCondition(
            () -> transport.closes.get() == 1 && transport.connects.get() == 2 && relay.isConnected(),
            "A silent connection must be replaced after the watchdog timeout"
        );
    }

    @Test
    public void emptyEoseCompletesProbeWithoutReconnecting() throws Exception {
        startProbe();
        transport.receive(platform.toJSON(List.of("EOSE", transport.subscriptionId)));
        assertProbeClosed();
        assertEquals(0, transport.closes.get());
        assertTrue(relay.isConnected());
    }

    @Test
    public void eventCompletesProbeWithoutReconnecting() throws Exception {
        NostrKeyPairSigner signer = NostrKeyPairSigner.generate();
        try {
            SignedNostrEvent event = signer.sign(new UnsignedNostrEvent().withKind(0).withContent("{}")).await();
            startProbe();
            transport.receive(platform.toJSON(List.of("EVENT", transport.subscriptionId, event.toMap())));
            assertProbeClosed();
            assertEquals(0, transport.closes.get());
            assertTrue(relay.isConnected());
        } finally {
            signer.getKeyPair().destroy();
        }
    }

    @Test
    public void closedResponseDoesNotReconnectAResponsiveRelay() throws Exception {
        startProbe();
        transport.receive(platform.toJSON(List.of("CLOSED", transport.subscriptionId, "auth-required: test")));
        assertProbeClosed();
        assertEquals(0, transport.closes.get());
        assertTrue(relay.isConnected());
    }

    @Test
    public void expiredProbeDoesNotReplaceANewerConnection() throws Exception {
        startProbe();
        transport.connected = false;
        for (WebsocketTransportListener listener : transport.listeners) listener.onConnectionClosedByServer("network loss");
        awaitCondition(() -> transport.connects.get() == 2 && relay.isConnected(), "Relay did not reconnect");
        platform.expireProbe();
        assertProbeClosed();
        // Let the relay queue process recovery after the fetch completion.
        Thread.sleep(100);
        assertEquals(0, transport.closes.get());
        assertEquals(2, transport.connects.get());
        assertTrue(relay.isConnected());
    }

    private void startProbe() throws Exception {
        new ProbeWatchdog().check(relay);
        assertTrue("Watchdog did not send its request", transport.requestSent.await(3, TimeUnit.SECONDS));
        awaitCondition(() -> platform.timeout != null, "Probe timeout was not scheduled");
    }

    private void assertProbeClosed() throws Exception {
        assertTrue("Watchdog did not close its probe subscription", transport.probeClosed.await(3, TimeUnit.SECONDS));
    }

    private static void awaitCondition(BooleanSupplier condition, String message) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) return;
            Thread.sleep(10);
        }
        assertTrue(message, condition.getAsBoolean());
    }

    private static final class ProbeWatchdog extends NostrRelayWatchdog {

        void check(NostrRelay relay) {
            runWatchdog(relay);
        }
    }

    private static final class TestPlatform extends JVMAsyncPlatform {

        private RecordingTransport transport;
        private volatile Runnable timeout;

        @Override
        public WebsocketTransport newTransport() {
            transport = new RecordingTransport(this);
            return transport;
        }

        @Override
        public AsyncExecutor newAsyncExecutor(Object hint) {
            AsyncExecutor executor = super.newAsyncExecutor(hint);
            if (hint != NostrWaitForEventFetchPolicy.class) return executor;
            return new AsyncExecutor() {
                @Override
                public <T> AsyncTask<T> run(Callable<T> callable) {
                    return executor.run(callable);
                }

                @Override
                public <T> AsyncTask<T> runLater(Callable<T> callable, long delay, TimeUnit unit) {
                    assertEquals(TimeUnit.MINUTES.toMillis(5), unit.toMillis(delay));
                    return AsyncTask.create((resolve, reject) -> {
                        timeout =
                            () -> {
                                try {
                                    resolve.accept(callable.call());
                                } catch (Throwable error) {
                                    reject.accept(error);
                                }
                            };
                    });
                }

                @Override
                public void close() {
                    executor.close();
                }
            };
        }

        void expireProbe() {
            timeout.run();
        }
    }

    private static final class RecordingTransport implements WebsocketTransport {

        private final TestPlatform platform;
        private final List<WebsocketTransportListener> listeners = new CopyOnWriteArrayList<>();
        private final AtomicInteger connects = new AtomicInteger();
        private final AtomicInteger closes = new AtomicInteger();
        private final CountDownLatch requestSent = new CountDownLatch(1);
        private final CountDownLatch probeClosed = new CountDownLatch(1);
        private volatile String subscriptionId;
        private volatile boolean connected;

        RecordingTransport(TestPlatform platform) {
            this.platform = platform;
        }

        @Override
        public AsyncTask<Void> connect(String url) {
            connected = true;
            connects.incrementAndGet();
            for (WebsocketTransportListener listener : listeners) listener.onConnectionOpen();
            return AsyncTask.completed(null);
        }

        @Override
        public AsyncTask<Void> close(String reason) {
            connected = false;
            closes.incrementAndGet();
            for (WebsocketTransportListener listener : listeners) listener.onConnectionClosedByClient(reason);
            return AsyncTask.completed(null);
        }

        @Override
        public AsyncTask<Void> send(String message) {
            List<?> data = platform.fromJSON(message, List.class);
            if ("REQ".equals(data.get(0))) {
                subscriptionId = (String) data.get(1);
                requestSent.countDown();
            } else if ("CLOSE".equals(data.get(0))) {
                probeClosed.countDown();
            }
            return AsyncTask.completed(null);
        }

        void receive(String message) {
            for (WebsocketTransportListener listener : listeners) listener.onConnectionMessage(message);
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
            return connected;
        }

        @Override
        public void setMaxMessageSize(int maxMessageSize) {}

        @Override
        public int getMaxMessageSize() {
            return 1024 * 1024;
        }
    }
}
