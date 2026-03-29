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

import static org.junit.Assert.assertTrue;

import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.ngengine.nostr4j.NostrRelay;
import org.ngengine.nostr4j.NostrRelayLifecycleManager;
import org.ngengine.nostr4j.NostrRelayWatchdog;
import org.ngengine.nostr4j.proto.impl.NostrNoticeMessage;
import org.ngengine.platform.AsyncTask;
import org.ngengine.platform.NGEPlatform;
import org.ngengine.platform.jvm.JVMAsyncPlatform;
import org.ngengine.platform.transport.WebsocketTransport;
import org.ngengine.platform.transport.WebsocketTransportListener;

public class TestRelayLoopScheduling {

    private static final String TEST_RELAY_URL = "wss://loop-scheduling.test";

    private NGEPlatform previousPlatform;
    private TestPlatform testPlatform;

    @Before
    public void setUp() throws Exception {
        previousPlatform = getInstalledPlatform();
        testPlatform = new TestPlatform();
        installPlatform(testPlatform);
    }

    @After
    public void tearDown() throws Exception {
        if (previousPlatform != null) {
            installPlatform(previousPlatform);
        }
    }

    @Test
    public void testLifecycleManagerSchedulesDisconnectWithoutRelayLoop() throws Exception {
        NostrRelay relay = new NostrRelay(TEST_RELAY_URL);
        NostrRelayLifecycleManager lifecycle = new NostrRelayLifecycleManager();
        setField(lifecycle, "keepAliveTime", Long.valueOf(0L));
        relay.addComponent(lifecycle);

        relay.connect().await();
        awaitCondition(
            () -> relay.getStatus() == NostrRelay.Status.DISCONNECTED,
            2_500,
            "lifecycle timeout did not disconnect"
        );
    }

    @Test
    public void testWatchdogSchedulesChecksWithoutRelayLoop() throws Exception {
        NostrRelay relay = new NostrRelay(TEST_RELAY_URL);
        ProbeWatchdog watchdog = new ProbeWatchdog();
        setField(watchdog, "checkInterval", Duration.ofMillis(50));
        relay.addComponent(watchdog);

        relay.connect().await();
        awaitCondition(() -> relay.getStatus() == NostrRelay.Status.CONNECTED, 1_000, "relay did not connect");
        awaitCondition(() -> watchdog.invocations.get() > 0, 500, "watchdog did not run");
        assertTrue("watchdog should run at least once", watchdog.invocations.get() > 0);
    }

    @Test
    public void testQueuedMessagesDrainAfterConnectWithoutPollingLoop() throws Exception {
        NostrRelay relay = new NostrRelay(TEST_RELAY_URL);
        AsyncTask<?> send = relay.sendMessage(new NostrNoticeMessage("queued"));

        relay.connect().await();
        send.await();

        awaitCondition(() -> testPlatform.getLastTransport().getSentMessages().size() == 1, 500, "queued message was not sent");
    }

    @Test
    public void testConnectTimeoutResetsRelayWithoutPollingLoop() throws Exception {
        testPlatform.setAutoOpen(false);
        NostrRelay relay = new NostrRelay(TEST_RELAY_URL);
        setField(relay, "statusTimeout", Duration.ofMillis(50));

        relay.setAutoReconnect(false);
        relay.connect();

        awaitCondition(() -> relay.getStatus() == NostrRelay.Status.DISCONNECTED, 500, "relay did not time out");
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

    private static void setField(Object target, String name, Object value) throws Exception {
        Class<?> type = target.getClass();
        while (type != null) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                field.set(target, value);
                return;
            } catch (NoSuchFieldException ignored) {
                type = type.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }

    private static void awaitCondition(BooleanSupplier condition, long timeoutMs, String message) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError(message);
    }

    private static final class ProbeWatchdog extends NostrRelayWatchdog {

        private final AtomicInteger invocations = new AtomicInteger();

        @Override
        protected void runWatchdog(NostrRelay relay) {
            invocations.incrementAndGet();
        }
    }

    private static final class TestPlatform extends JVMAsyncPlatform {

        private volatile boolean autoOpen = true;
        private volatile RecordingWebsocketTransport lastTransport;

        public void setAutoOpen(boolean autoOpen) {
            this.autoOpen = autoOpen;
        }

        public RecordingWebsocketTransport getLastTransport() {
            return lastTransport;
        }

        @Override
        public WebsocketTransport newTransport() {
            lastTransport = new RecordingWebsocketTransport(this);
            return lastTransport;
        }
    }

    private static final class RecordingWebsocketTransport implements WebsocketTransport {

        private final TestPlatform platform;
        private final List<WebsocketTransportListener> listeners = new CopyOnWriteArrayList<WebsocketTransportListener>();
        private final List<String> sentMessages = new ArrayList<String>();
        private volatile boolean connected;

        private RecordingWebsocketTransport(TestPlatform platform) {
            this.platform = platform;
        }

        @Override
        public AsyncTask<Void> close(String reason) {
            connected = false;
            for (WebsocketTransportListener listener : listeners) {
                listener.onConnectionClosedByClient(reason);
            }
            return AsyncTask.completed(null);
        }

        @Override
        public AsyncTask<Void> connect(String url) {
            connected = platform.autoOpen;
            if (platform.autoOpen) {
                for (WebsocketTransportListener listener : listeners) {
                    listener.onConnectionOpen();
                }
            }
            return AsyncTask.completed(null);
        }

        @Override
        public AsyncTask<Void> send(String message) {
            sentMessages.add(message);
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
            return connected;
        }

        public List<String> getSentMessages() {
            return sentMessages;
        }

        @Override
        public void setMaxMessageSize(int maxMessageSize) {
            
        }

        @Override
        public int getMaxMessageSize() {
            return 1024*1024*10;
        }
    }
}
