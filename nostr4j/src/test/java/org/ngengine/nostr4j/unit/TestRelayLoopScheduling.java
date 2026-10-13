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

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.ngengine.nostr4j.NostrRelay;
import org.ngengine.nostr4j.NostrRelayLifecycleManager;
import org.ngengine.nostr4j.NostrRelayWatchdog;
import org.ngengine.nostr4j.event.SignedNostrEvent;
import org.ngengine.nostr4j.event.UnsignedNostrEvent;
import org.ngengine.nostr4j.proto.NostrMessage;
import org.ngengine.nostr4j.proto.NostrMessageAck;
import org.ngengine.nostr4j.proto.impl.NostrNoticeMessage;
import org.ngengine.nostr4j.signer.NostrKeyPairSigner;
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
    public void simultaneousPublicationsOfOneEventKeepEveryAcknowledgement() throws Exception {
        NostrKeyPairSigner signer = NostrKeyPairSigner.generate();
        try {
            for (boolean accepted : new boolean[] { true, false }) {
                NostrRelay relay = new NostrRelay(TEST_RELAY_URL);
                relay.connect().await();
                AtomicInteger attempts = new AtomicInteger();
                relay.addComponent(
                    new NostrRelayLifecycleManager() {
                        @Override
                        public boolean onRelaySend(NostrRelay ignored, NostrMessage message) {
                            attempts.incrementAndGet();
                            return true;
                        }
                    }
                );
                SignedNostrEvent event = signer
                    .sign(
                        new UnsignedNostrEvent()
                            .withKind(30078)
                            .withTag("d", "duplicate-ack-" + accepted)
                            .withContent("Shared publication")
                    )
                    .await();
                AsyncTask<NostrMessageAck> first = relay.sendMessage(event);
                AsyncTask<NostrMessageAck> second = relay.sendMessage(event);
                awaitCondition(() -> attempts.get() == 2, 2000, "Both callers must reach the relay send queue");
                RecordingWebsocketTransport transport = testPlatform.getLastTransport();
                Assert.assertEquals(
                    "Concurrent callers share one wire publication",
                    1,
                    transport.sentMessages.size()
                );
                String response = "[\"OK\",\"" + event.getId() + "\"," + accepted + ",\"test acknowledgement\"]";
                for (WebsocketTransportListener listener : transport.listeners) listener.onConnectionMessage(response);
                awaitCondition(
                    () -> first.isDone() && second.isDone(),
                    2000,
                    "Publishing the same signed event twice must not orphan the first ACK promise"
                );
                Assert.assertEquals(
                    accepted,
                    first.await().getStatus() == NostrMessageAck.Status.SUCCESS
                );
                Assert.assertEquals(
                    accepted,
                    second.await().getStatus() == NostrMessageAck.Status.SUCCESS
                );
                relay.disconnect("test-cleanup").await();
            }
        } finally {
            signer.getKeyPair().destroy();
        }
    }

    @Test
    public void missingAcknowledgementTimesOutEveryConcurrentCaller() throws Exception {
        NostrKeyPairSigner signer = NostrKeyPairSigner.generate();
        NostrRelay relay = new NostrRelay(TEST_RELAY_URL);
        try {
            relay.connect().await();
            relay.setAckTimeout(0, TimeUnit.SECONDS);
            SignedNostrEvent event = signer
                .sign(new UnsignedNostrEvent().withKind(1).withContent("Timeout fixture"))
                .await();
            AsyncTask<NostrMessageAck> first = relay.sendMessage(event);
            AsyncTask<NostrMessageAck> second = relay.sendMessage(event);
            awaitCondition(() -> first.isDone() && second.isDone(), 4000, "An expired shared ACK must settle every caller");
            Assert.assertEquals(NostrMessageAck.Status.FAILURE, first.await().getStatus());
            Assert.assertEquals(
                NostrMessageAck.Status.FAILURE,
                second.await().getStatus()
            );
        } finally {
            relay.disconnect("test-cleanup").await();
            signer.getKeyPair().destroy();
        }
    }

    @Test
    public void exhaustedTransportRetriesFailEverySharedPublication() throws Exception {
        NostrKeyPairSigner signer = NostrKeyPairSigner.generate();
        NostrRelay relay = new NostrRelay(TEST_RELAY_URL);
        try {
            relay.connect().await();
            RecordingWebsocketTransport transport = testPlatform.getLastTransport();
            transport.withholdWrites = true;
            AtomicInteger attempts = new AtomicInteger();
            relay.addComponent(
                new NostrRelayLifecycleManager() {
                    @Override
                    public boolean onRelaySend(NostrRelay ignored, NostrMessage message) {
                        attempts.incrementAndGet();
                        return true;
                    }
                }
            );
            SignedNostrEvent event = signer
                .sign(new UnsignedNostrEvent().withKind(1).withContent("Retry fixture"))
                .await();
            AsyncTask<NostrMessageAck> first = relay.sendMessage(event);
            AsyncTask<NostrMessageAck> second = relay.sendMessage(event);
            awaitCondition(
                () -> attempts.get() == 2 && !transport.failedWrites.isEmpty(),
                2000,
                "Shared send was not registered"
            );
            long deadline = System.currentTimeMillis() + 3000;
            while (!(first.isDone() && second.isDone()) && System.currentTimeMillis() < deadline) {
                for (Consumer<Throwable> reject : new ArrayList<>(transport.failedWrites)) if (
                    transport.failedWrites.remove(reject)
                ) reject.accept(new IOException("Synthetic transport failure"));
                Thread.sleep(20);
            }
            assertTrue("Both callers must settle when transport retries are exhausted", first.isDone() && second.isDone());
            Assert.assertEquals(NostrMessageAck.Status.FAILURE, first.await().getStatus());
            Assert.assertEquals(
                NostrMessageAck.Status.FAILURE,
                second.await().getStatus()
            );
        } finally {
            relay.disconnect("test-cleanup").await();
            signer.getKeyPair().destroy();
        }
    }

    @Test
    public void walletShutdownDisconnectsOwnedRelaysAndPreservesSharedPool() throws Exception {
        org.ngengine.wallets.nip47.NWCUri uri = new org.ngengine.wallets.nip47.NWCUri(
            new org.ngengine.nostr4j.keypair.NostrKeyPair().getPublicKey(),
            java.util.Collections.emptyList(),
            "1".repeat(64),
            null
        );
        for (boolean ownsPool : new boolean[] { true, false }) {
            org.ngengine.nostr4j.NostrPool shared = ownsPool ? null : new org.ngengine.nostr4j.NostrPool();
            org.ngengine.wallets.nip47.NWCWallet wallet = new org.ngengine.wallets.nip47.NWCWallet(shared, uri);
            Field poolField = org.ngengine.wallets.nip47.NWCWallet.class.getDeclaredField("pool");
            poolField.setAccessible(true);
            org.ngengine.nostr4j.NostrPool pool = (org.ngengine.nostr4j.NostrPool) poolField.get(wallet);
            NostrRelay relay = new NostrRelay(TEST_RELAY_URL);
            pool.addRelay(relay).await();
            try {
                wallet.close();
                if (ownsPool) awaitCondition(
                    () -> relay.getStatus() == NostrRelay.Status.DISCONNECTED,
                    2000,
                    "owned relay survived wallet shutdown"
                ); else assertTrue(relay.getStatus() == NostrRelay.Status.CONNECTED);
            } finally {
                for (NostrRelay detached : pool.clean()) detached.disconnect("test-cleanup");
                wallet.close();
            }
        }
    }

    @Test
    public void signerShutdownDisconnectsItsOwnedRelayPool() throws Exception {
        org.ngengine.nostr4j.NostrPool pool = new org.ngengine.nostr4j.NostrPool();
        NostrRelay relay = new NostrRelay(TEST_RELAY_URL);
        pool.addRelay(relay).await();
        org.ngengine.nostr4j.signer.NostrNIP46Signer signer = new org.ngengine.nostr4j.signer.NostrNIP46Signer(
            null,
            new org.ngengine.nostr4j.keypair.NostrKeyPair()
        );
        setField(signer, "pool", pool);
        signer.close().await();
        awaitCondition(() -> relay.getStatus() == NostrRelay.Status.DISCONNECTED, 2000, "owned relay survived signer shutdown");
        assertTrue(pool.getRelays().isEmpty());
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

    @Test
    public void testConnectionVetoOrExceptionNeverFlushesQueuedMessages() throws Exception {
        for (boolean throwsError : new boolean[] { false, true }) {
            NostrRelay relay = new NostrRelay(TEST_RELAY_URL);
            relay.addComponent(
                new NostrRelayLifecycleManager() {
                    @Override
                    public boolean onRelayConnect(NostrRelay ignored) {
                        if (throwsError) throw new SecurityException("veto");
                        return false;
                    }
                }
            );
            relay.sendMessage(new NostrNoticeMessage("private queued payload"));
            AsyncTask<?> connected = relay.connect();
            awaitCondition(() -> relay.getStatus() == NostrRelay.Status.DISCONNECTED, 2000, "veto did not close relay");
            assertTrue(testPlatform.getLastTransport().sentMessages.isEmpty());
            Assert.assertThrows(Exception.class, () -> connected.await());
        }
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
        private volatile boolean withholdWrites;
        private final List<Consumer<Throwable>> failedWrites = new CopyOnWriteArrayList<>();

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
            if (withholdWrites) return AsyncTask.create((resolve, reject) -> failedWrites.add(reject));
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
        public void setMaxMessageSize(int maxMessageSize) {}

        @Override
        public int getMaxMessageSize() {
            return 1024 * 1024 * 10;
        }
    }
}
