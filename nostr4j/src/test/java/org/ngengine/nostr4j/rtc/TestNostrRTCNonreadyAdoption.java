/**
 * BSD 3-Clause License
 *
 * Copyright (c) 2026, Riccardo Balbo
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

package org.ngengine.nostr4j.rtc;

import static org.junit.Assert.*;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import org.junit.Test;
import org.ngengine.platform.AsyncExecutor;
import org.ngengine.platform.AsyncTask;
import org.ngengine.platform.transport.RTCDataChannel;
import org.ngengine.platform.transport.RTCTransport;
import org.ngengine.platform.transport.RTCTransportIceCandidate;
import org.ngengine.platform.transport.RTCTransportListener;

/**
 * Actual socket lookup and owner-bound callbacks with the established paired, authenticated TURN fixture.
 */
public class TestNostrRTCNonreadyAdoption {

    @Test(timeout = 10000L)
    public void nonreadyLookupKeepsTurnReceiptAndOriginalQueueEntryUntilReadyCallback() throws Exception {
        try (TurnFixture fixture = new TurnFixture()) {
            PendingChannel nativeChannel = new PendingChannel(fixture.receiver);
            Provider provider = fixture.install(nativeChannel, () -> true);
            fixture.holdReceipts();
            fixture.send((byte) 1);
            waitUntil(() -> fixture.heldReceipts() == 1 && fixture.deliveries() == 1);
            Object head = fixture.head();
            Object packet = field(head, "packet");
            long enqueued = ((Long) field(head, "enqueuedAtMs")).longValue();
            long generation = fixture.logical.getWriteGeneration();
            fixture.clock.addAndGet(500L);

            assertSame(fixture.logical, fixture.socket.createChannel("primary"));
            assertFalse("A published, unopened handle must not be adopted", fixture.logical.isConnected());
            assertSame(fixture.sender, field(fixture.logical, "turnSend"));
            assertFalse("Lookup must not close the live TURN sender", fixture.sender.isClosed());
            assertFalse("Lookup must not close the paired receiver", fixture.companion.isClosed());
            assertEquals(generation, fixture.logical.getWriteGeneration());
            assertSame(head, fixture.head());
            assertSame(packet, field(fixture.head(), "packet"));
            assertEquals(enqueued, ((Long) field(fixture.head(), "enqueuedAtMs")).longValue());
            assertEquals(0, fixture.successes());
            assertEquals(0, fixture.failures());
            assertEquals(0, nativeChannel.writes.get());

            fixture.releaseReceipt(0);
            waitUntil(() -> fixture.successes() == 1);
            assertEquals(0, fixture.failures());
            nativeChannel.open();
            provider.listener.onRTCChannelReady(nativeChannel);
            assertTrue(fixture.logical.isUsingNativeChannel(nativeChannel));
            assertTrue(fixture.sender.isClosed());
            assertTrue(fixture.companion.isClosed());
            assertEquals(generation + 1L, fixture.logical.getWriteGeneration());
            fixture.send((byte) 2);
            waitUntil(() -> fixture.successes() == 2 && fixture.deliveries() == 2);
            assertEquals(1, nativeChannel.writes.get());
            assertEquals(0, fixture.failures());
        }
    }

    @Test(timeout = 10000L)
    public void alreadyReadyLookupStillAdoptsNativeWithoutWaitingForAnotherCallback() throws Exception {
        try (TurnFixture fixture = new TurnFixture()) {
            PendingChannel nativeChannel = new PendingChannel(fixture.receiver);
            fixture.install(nativeChannel, () -> true);
            nativeChannel.open();
            long generation = fixture.logical.getWriteGeneration();
            assertSame(fixture.logical, fixture.socket.createChannel("primary"));
            assertTrue(fixture.logical.isUsingNativeChannel(nativeChannel));
            assertTrue(fixture.sender.isClosed());
            assertEquals(generation + 1L, fixture.logical.getWriteGeneration());
            fixture.send((byte) 3);
            waitUntil(() -> fixture.successes() == 1 && fixture.deliveries() == 1);
            assertEquals(1, nativeChannel.writes.get());
            assertEquals(0, fixture.failures());
        }
    }

    @Test(timeout = 10000L)
    public void failedReadinessLeavesTheCurrentTurnDeliveryUntouched() throws Exception {
        try (TurnFixture fixture = new TurnFixture()) {
            PendingChannel nativeChannel = new PendingChannel(fixture.receiver);
            fixture.install(nativeChannel, () -> true);
            nativeChannel.fail();
            long generation = fixture.logical.getWriteGeneration();
            fixture.socket.createChannel("primary");
            assertFalse(fixture.logical.isConnected());
            assertFalse(fixture.sender.isClosed());
            assertEquals(generation, fixture.logical.getWriteGeneration());
            fixture.send((byte) 4);
            waitUntil(() -> fixture.successes() == 1 && fixture.deliveries() == 1);
            assertEquals(0, nativeChannel.writes.get());
            assertEquals(0, fixture.failures());
        }
    }

    @Test(timeout = 10000L)
    public void replacedAndInactiveOwnerReadyCallbacksCannotAdoptOldHandles() throws Exception {
        try (TurnFixture fixture = new TurnFixture()) {
            PendingChannel oldChannel = new PendingChannel(fixture.receiver);
            Provider oldProvider = fixture.install(oldChannel, () -> true);
            fixture.socket.createChannel("primary");
            long generation = fixture.logical.getWriteGeneration();
            AtomicBoolean active = new AtomicBoolean(false);
            PendingChannel replacement = new PendingChannel(fixture.receiver);
            Provider currentProvider = fixture.install(replacement, active::get);
            oldChannel.open();
            oldProvider.listener.onRTCChannelReady(oldChannel);
            replacement.open();
            currentProvider.listener.onRTCChannelReady(replacement);
            fixture.socket.createChannel("primary");
            assertFalse(fixture.logical.isConnected());
            assertFalse(fixture.sender.isClosed());
            assertEquals(generation, fixture.logical.getWriteGeneration());
            active.set(true);
            currentProvider.listener.onRTCChannelReady(replacement);
            assertTrue(fixture.logical.isUsingNativeChannel(replacement));
            assertEquals(generation + 1L, fixture.logical.getWriteGeneration());
            oldProvider.listener.onRTCChannelReady(oldChannel);
            assertTrue(fixture.logical.isUsingNativeChannel(replacement));
            fixture.send((byte) 5);
            waitUntil(() -> fixture.successes() == 1 && fixture.deliveries() == 1);
            assertEquals(0, oldChannel.writes.get());
            assertEquals(1, replacement.writes.get());
        }
    }

    /**
     * Reuse the real crypto/receipt/pool/queue setup without changing the existing fixture's source.
     */
    private static final class TurnFixture implements AutoCloseable {

        private final AtomicLong clock = new AtomicLong(1000L);
        private final Object delegate;
        private final NostrRTCSocket socket;
        private final NostrRTCChannel logical;
        private final NostrRTCChannel receiver;
        private final NostrTURNChannel sender;
        private final NostrTURNChannel companion;
        private final Object back;

        private TurnFixture() throws Exception {
            Class<?> type = Class.forName(TestNostrTURNAckRetry.class.getName() + "$RoomTurnFixture");
            Constructor<?> constructor = type.getDeclaredConstructor(LongSupplier.class, boolean.class, boolean.class);
            constructor.setAccessible(true);
            delegate = constructor.newInstance((LongSupplier) clock::get, Boolean.TRUE, Boolean.FALSE);
            socket = (NostrRTCSocket) field(delegate, "aliceSocket");
            logical = (NostrRTCChannel) field(delegate, "aliceLogical");
            receiver = (NostrRTCChannel) field(delegate, "bobLogical");
            sender = (NostrTURNChannel) field(delegate, "sender");
            companion = (NostrTURNChannel) field(delegate, "companion");
            back = field(delegate, "back");
            socket.setForceTURN(false);
        }

        private Provider install(PendingChannel channel, BooleanSupplier active) throws Exception {
            Provider provider = new Provider(channel);
            Class<?> listenerType = Class.forName(NostrRTCSocket.class.getName() + "$BoundRTCListener");
            Constructor<?> constructor = listenerType.getDeclaredConstructor(
                NostrRTCSocket.class,
                RTCTransport.class,
                boolean.class,
                BooleanSupplier.class
            );
            constructor.setAccessible(true);
            provider.listener = (RTCTransportListener) constructor.newInstance(socket, provider, Boolean.TRUE, active);
            synchronized (socket) {
                setField(socket, "transport", provider);
                setField(socket, "currentRtcListener", provider.listener);
            }
            return provider;
        }

        private void holdReceipts() throws Exception {
            ((AtomicBoolean) field(back, "holdDeliveryAcks")).set(true);
        }

        private int heldReceipts() throws Exception {
            return ((List<?>) field(back, "heldAcks")).size();
        }

        private int successes() throws Exception {
            return ((AtomicInteger) field(delegate, "successes")).get();
        }

        private int failures() throws Exception {
            return ((AtomicInteger) field(delegate, "failures")).get();
        }

        private int deliveries() throws Exception {
            return ((AtomicInteger) field(delegate, "applicationDeliveries")).get();
        }

        private Object head() throws Exception {
            return ((Queue<?>) field(field(delegate, "queue"), "queue")).peek();
        }

        private void send(byte value) throws Exception {
            invoke(delegate, "send", new Class<?>[] { ByteBuffer.class }, ByteBuffer.wrap(new byte[] { value }));
        }

        private void releaseReceipt(int index) throws Exception {
            invoke(back, "releaseAck", new Class<?>[] { int.class }, Integer.valueOf(index));
        }

        @Override
        public void close() throws Exception {
            ((AutoCloseable) delegate).close();
        }
    }

    private static final class PendingChannel extends RTCDataChannel {

        private final NostrRTCChannel receiver;
        private final AtomicReference<Consumer<RTCDataChannel>> resolve = new AtomicReference<Consumer<RTCDataChannel>>();
        private final AtomicReference<Consumer<Throwable>> reject = new AtomicReference<Consumer<Throwable>>();
        private final AsyncTask<RTCDataChannel> readiness = AsyncTask.create((res, rej) -> {
            resolve.set(res);
            reject.set(rej);
        });
        private final AtomicInteger writes = new AtomicInteger();

        private PendingChannel(NostrRTCChannel receiver) {
            super("primary", "turn-ack-retry-proto", true, true, 0, null);
            this.receiver = receiver;
        }

        private void open() throws Exception {
            waitUntil(() -> resolve.get() != null);
            resolve.get().accept(this);
            waitUntil(readiness::isSuccess);
        }

        private void fail() throws Exception {
            waitUntil(() -> reject.get() != null);
            reject.get().accept(new IllegalStateException("test readiness failure"));
            waitUntil(readiness::isFailed);
        }

        @Override
        public AsyncTask<RTCDataChannel> ready() {
            return readiness;
        }

        @Override
        public AsyncTask<Void> write(ByteBuffer data) {
            if (!readiness.isSuccess()) return AsyncTask.failed(new IllegalStateException("write before ready"));
            writes.incrementAndGet();
            receiver.onRTCSocketMessage(data.asReadOnlyBuffer());
            return AsyncTask.completed(null);
        }

        @Override
        public AsyncTask<Number> getMaxMessageSize() {
            return AsyncTask.completed(Integer.valueOf(262144));
        }

        @Override
        public AsyncTask<Number> getAvailableAmount() {
            return AsyncTask.completed(Integer.valueOf(262144));
        }

        @Override
        public AsyncTask<Number> getBufferedAmount() {
            return AsyncTask.completed(Integer.valueOf(0));
        }

        @Override
        public AsyncTask<Void> setBufferedAmountLowThreshold(int threshold) {
            return AsyncTask.completed(null);
        }

        @Override
        public AsyncTask<Void> close() {
            return AsyncTask.completed(null);
        }
    }

    private static final class Provider implements RTCTransport {

        private final PendingChannel channel;
        private RTCTransportListener listener;

        private Provider(PendingChannel channel) {
            this.channel = channel;
        }

        @Override
        public RTCDataChannel getDataChannel(String name) {
            return "primary".equals(name) ? channel : null;
        }

        @Override
        public AsyncTask<RTCDataChannel> createDataChannel(
            String name,
            String protocol,
            boolean ordered,
            boolean reliable,
            int maxRetransmits,
            Duration maxPacketLifeTime
        ) {
            return channel.ready();
        }

        @Override
        public void close() {}

        @Override
        public boolean isConnected() {
            return true;
        }

        @Override
        public void start(Duration timeout, AsyncExecutor executor, String id, Collection<String> stun) {}

        @Override
        public AsyncTask<String> connect(String description) {
            return AsyncTask.completed(description);
        }

        @Override
        public void addRemoteIceCandidates(Collection<RTCTransportIceCandidate> candidates) {}

        @Override
        public void addListener(RTCTransportListener next) {
            listener = next;
        }

        @Override
        public void removeListener(RTCTransportListener previous) {
            if (listener == previous) listener = null;
        }

        @Override
        public String getName() {
            return "nonready-test";
        }

        @Override
        public AsyncTask<String> listen() {
            return AsyncTask.completed("");
        }
    }

    private static Object field(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static Object invoke(Object target, String name, Class<?>[] types, Object... arguments) throws Exception {
        Method method = target.getClass().getDeclaredMethod(name, types);
        method.setAccessible(true);
        return method.invoke(target, arguments);
    }

    @FunctionalInterface
    private interface Check {
        boolean ok() throws Exception;
    }

    private static void waitUntil(Check check) throws Exception {
        long deadline = System.nanoTime() + 3000000000L;
        while (System.nanoTime() < deadline) {
            if (check.ok()) return;
            Thread.sleep(5L);
        }
        throw new AssertionError("fixture condition did not complete");
    }
}
