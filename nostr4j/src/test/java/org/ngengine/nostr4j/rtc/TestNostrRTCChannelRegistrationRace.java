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
package org.ngengine.nostr4j.rtc;

import static org.junit.Assert.*;

import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.ngengine.nostr4j.rtc.listeners.NostrRTCChannelListener;
import org.ngengine.nostr4j.rtc.listeners.NostrRTCSocketListener;
import org.ngengine.nostr4j.rtc.routing.InternalRoutingChannels;
import org.ngengine.platform.AsyncTask;
import org.ngengine.platform.NGEUtils;
import org.ngengine.platform.transport.RTCDataChannel;
import org.ngengine.platform.transport.RTCTransportIceCandidate;
import org.ngengine.platform.transport.RTCTransportListener;

/** Regression coverage for inbound callbacks racing logical channel listener registration. */
public class TestNostrRTCChannelRegistrationRace {
    @Test(timeout = 10000L)
    public void firstBinaryFrameCannotOvertakeLogicalChannelRegistration() throws Exception {
        TestNostrRTCSocketAttempts fixture = new TestNostrRTCSocketAttempts();
        fixture.setup();
        NostrRTCSocket socket = (NostrRTCSocket) get(fixture, "socket");
        CountDownLatch registrationEntered = new CountDownLatch(1);
        CountDownLatch releaseRegistration = new CountDownLatch(1);
        CountDownLatch binaryDone = new CountDownLatch(1);
        AtomicInteger delivered = new AtomicInteger();
        AtomicReference<Throwable> threadError = new AtomicReference<>();
        Thread ready = null;
        Thread binary = null;
        try {
            NGEUtils.awaitNoThrow(socket.listen());
            Object platform = get(fixture, "platform");
            Object transport = ((List<?>) get(platform, "created")).get(0);
            RTCTransportListener nativeListener = (RTCTransportListener) get(transport, "listener");
            socket.addInternalListener(new SocketListener() {
                @Override public void onRTCChannel(NostrRTCChannel channel) {
                    registrationEntered.countDown();
                    try {
                        if (!releaseRegistration.await(5, TimeUnit.SECONDS)) {
                            threadError.compareAndSet(null, new AssertionError("Registration gate timed out"));
                        }
                    } catch (InterruptedException error) {
                        Thread.currentThread().interrupt();
                        threadError.compareAndSet(null, error);
                    }
                }
            });
            // Model the room's listener-registration step, which must precede any binary delivery.
            socket.addInternalListener(new SocketListener() {
                @Override public void onRTCChannel(NostrRTCChannel channel) {
                    channel.addListener(new NostrRTCChannelListener() {
                        @Override public void onRTCSocketMessage(NostrRTCChannel c, ByteBuffer payload, boolean turn) {
                            if (payload.remaining() == 1 && payload.get() == 7) delivered.incrementAndGet();
                        }
                        @Override public void onRTCChannelError(NostrRTCChannel c, Throwable error) {}
                        @Override public void onRTCChannelClosed(NostrRTCChannel c) {}
                        @Override public void onRTCBufferedAmountLow(NostrRTCChannel c) {}
                    });
                }
            });
            RTCDataChannel nativeChannel = new MemoryChannel();
            ready = new Thread(() -> {
                try { nativeListener.onRTCChannelReady(nativeChannel); }
                catch (Throwable error) { threadError.compareAndSet(null, error); }
            }, "paused-logical-channel-registration");
            ready.start();
            assertTrue("Ready callback must reach registration", registrationEntered.await(2, TimeUnit.SECONDS));
            binary = new Thread(() -> {
                try { nativeListener.onRTCBinaryMessage(nativeChannel, frame()); }
                catch (Throwable error) { threadError.compareAndSet(null, error); }
                finally { binaryDone.countDown(); }
            }, "concurrent-first-binary-frame");
            binary.start();
            boolean completedBeforeRegistration = binaryDone.await(1, TimeUnit.SECONDS);
            System.out.println("Binary completed while registration was paused: " + completedBeforeRegistration);
            releaseRegistration.countDown();
            ready.join(2000L);
            binary.join(2000L);
            assertFalse("Ready callback deadlocked", ready.isAlive());
            assertFalse("Binary callback deadlocked", binary.isAlive());
            assertNull("Unexpected callback failure", threadError.get());
            // Retrying the exact authenticated packet also detects a drop hidden by deduplication.
            nativeListener.onRTCBinaryMessage(nativeChannel, frame());
            assertEquals("The first frame must be delivered once, even if it races listener registration", 1, delivered.get());
        } finally {
            releaseRegistration.countDown();
            if (ready != null) ready.join(2000L);
            if (binary != null) binary.join(2000L);
            fixture.cleanup();
        }
    }

    @Test(timeout = 10000L)
    public void queuedFrameOwnsItsBytesAfterNativeCallbackReturns() throws Exception {
        try (RegistrationHarness harness = new RegistrationHarness()) {
            ByteBuffer nativeBuffer = frame();
            harness.listener(0).onRTCBinaryMessage(harness.nativeChannel, nativeBuffer);
            assertEquals("Delivery must wait for registration", 0, harness.delivered.size());
            // A provider may reuse both the contents and cursor immediately after returning.
            nativeBuffer.put(12, (byte) 99);
            nativeBuffer.position(nativeBuffer.limit());
            harness.finishRegistration();
            assertEquals(java.util.Arrays.asList(7), harness.delivered);
        }
    }

    @Test(timeout = 10000L)
    public void supersededOwnerQueuedFrameIsDiscardedWithoutPoisoningDeduplication() throws Exception {
        try (RegistrationHarness harness = new RegistrationHarness()) {
            harness.listener(0).onRTCBinaryMessage(harness.nativeChannel, frame());
            harness.socket.prepareRtcTransportAttempt();
            NGEUtils.awaitNoThrow(harness.socket.listen());
            harness.finishRegistration();
            assertTrue("A superseded transport must not deliver its queued frame", harness.delivered.isEmpty());
            harness.listener(1).onRTCBinaryMessage(harness.nativeChannel, frame());
            assertEquals("The rejected old frame must not mark the replacement packet ID complete",
                java.util.Arrays.asList(7), harness.delivered);
        }
    }

    @Test(timeout = 10000L)
    public void socketCloseDiscardsFramesQueuedDuringRegistration() throws Exception {
        try (RegistrationHarness harness = new RegistrationHarness()) {
            harness.listener(0).onRTCBinaryMessage(harness.nativeChannel, frame());
            harness.socket.close();
            harness.finishRegistration();
            assertTrue(harness.delivered.isEmpty());
            assertTrue("Close must release pending registration state", harness.registrations().isEmpty());
        }
    }

    @Test(timeout = 10000L)
    public void registrationQueueIsBoundedByFrameCount() throws Exception {
        try (RegistrationHarness harness = new RegistrationHarness()) {
            int limit = (Integer) getStatic(NostrRTCSocket.class, "MAX_PENDING_CHANNEL_MESSAGES");
            for (int i = 0; i < limit + 16; i++) {
                ByteBuffer frame = frame();
                frame.putLong(0, 100L + i);
                harness.listener(0).onRTCBinaryMessage(harness.nativeChannel, frame);
            }
            Object pending = harness.pendingRegistration();
            assertEquals("Overflow must not grow the registration frame queue", limit,
                ((Queue<?>) get(pending, "messages")).size());
            assertTrue(((Number) get(pending, "pendingBytes")).longValue() <= NostrRTCChannel.MAX_REASSEMBLY_BYTES);
            harness.finishRegistration();
            assertEquals("Frames accepted before overflow must still be delivered", limit, harness.delivered.size());
            assertTrue(harness.registrations().isEmpty());
        }
    }

    @Test(timeout = 10000L)
    public void registrationQueueRejectsFrameLargerThanItsByteBudget() throws Exception {
        try (RegistrationHarness harness = new RegistrationHarness()) {
            int limit = (Integer) getStatic(NostrRTCSocket.class, "MAX_PENDING_CHANNEL_BYTES");
            ByteBuffer oversize = ByteBuffer.allocate(limit + 1);
            harness.listener(0).onRTCBinaryMessage(harness.nativeChannel, oversize);
            Object pending = harness.pendingRegistration();
            assertEquals("An oversized frame must not consume registration buffer space", 0,
                ((Queue<?>) get(pending, "messages")).size());
            assertEquals(0L, ((Number) get(pending, "pendingBytes")).longValue());
            harness.listener(0).onRTCBinaryMessage(harness.nativeChannel, frame());
            harness.finishRegistration();
            assertEquals("Rejecting oversize input must leave normal delivery usable",
                java.util.Arrays.asList(7), harness.delivered);
        }
    }

    private static final class RegistrationHarness implements AutoCloseable {
        private final TestNostrRTCSocketAttempts fixture = new TestNostrRTCSocketAttempts();
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final AtomicReference<Throwable> error = new AtomicReference<>();
        private final List<Integer> delivered = new CopyOnWriteArrayList<>();
        private final NostrRTCSocket socket;
        private final RTCDataChannel nativeChannel = new MemoryChannel();
        private final Object platform;
        private final Thread ready;

        private RegistrationHarness() throws Exception {
            fixture.setup();
            socket = (NostrRTCSocket) get(fixture, "socket");
            platform = get(fixture, "platform");
            NGEUtils.awaitNoThrow(socket.listen());
            socket.addInternalListener(new SocketListener() {
                @Override public void onRTCChannel(NostrRTCChannel channel) {
                    entered.countDown();
                    try {
                        if (!release.await(5, TimeUnit.SECONDS)) error.set(new AssertionError("Registration gate timed out"));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        error.set(e);
                    }
                }
            });
            socket.addInternalListener(new SocketListener() {
                @Override public void onRTCChannel(NostrRTCChannel channel) {
                    channel.addListener(new NostrRTCChannelListener() {
                        @Override public void onRTCSocketMessage(NostrRTCChannel c, ByteBuffer payload, boolean turn) {
                            if (payload.remaining() == 1) delivered.add((int) payload.get());
                        }
                        @Override public void onRTCChannelError(NostrRTCChannel c, Throwable failure) {}
                        @Override public void onRTCChannelClosed(NostrRTCChannel c) {}
                        @Override public void onRTCBufferedAmountLow(NostrRTCChannel c) {}
                    });
                }
            });
            RTCTransportListener initial = listener(0);
            ready = new Thread(() -> {
                try { initial.onRTCChannelReady(nativeChannel); }
                catch (Throwable failure) { error.compareAndSet(null, failure); }
            }, "paused-registration-fixture");
            ready.start();
            assertTrue("Registration fixture must reach its gate", entered.await(2, TimeUnit.SECONDS));
        }

        private RTCTransportListener listener(int index) throws Exception {
            return (RTCTransportListener) get(((List<?>) get(platform, "created")).get(index), "listener");
        }
        private Map<?, ?> registrations() throws Exception {
            return (Map<?, ?>) get(socket, "channelRegistrations");
        }
        private Object pendingRegistration() throws Exception {
            assertEquals(1, registrations().size());
            return registrations().values().iterator().next();
        }
        private void finishRegistration() throws Exception {
            release.countDown();
            ready.join(2000L);
            assertFalse("Registration must finish without deadlock", ready.isAlive());
            assertNull("Callback must not fail", error.get());
        }
        @Override public void close() throws Exception {
            try { finishRegistration(); }
            finally { fixture.cleanup(); }
        }
    }

    private static Object getStatic(Class<?> type, String name) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(null);
    }

    private static ByteBuffer frame() {
        ByteBuffer frame = ByteBuffer.allocate(13);
        frame.putLong(42L).putShort((short) 0).putShort((short) 1).put((byte) 7).flip();
        return frame;
    }
    private static Object get(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }
    private static class SocketListener implements NostrRTCSocketListener {
        @Override public void onRTCSocketRouteUpdate(NostrRTCSocket socket, Collection<RTCTransportIceCandidate> candidates, String turn) {}
        @Override public void onRTCSocketClose(NostrRTCSocket socket) {}
        @Override public void onRTCChannelReady(NostrRTCChannel channel) {}
        @Override public void onRTCChannel(NostrRTCChannel channel) {}
    }
    private static final class MemoryChannel extends RTCDataChannel {
        MemoryChannel() { super(InternalRoutingChannels.CONTROL, "protocol", true, true, 0, null); }
        @Override public AsyncTask<RTCDataChannel> ready() { return AsyncTask.completed(this); }
        @Override public AsyncTask<Void> write(ByteBuffer payload) { return AsyncTask.completed(null); }
        @Override public AsyncTask<Number> getMaxMessageSize() { return AsyncTask.completed(65536); }
        @Override public AsyncTask<Number> getAvailableAmount() { return AsyncTask.completed(65536); }
        @Override public AsyncTask<Number> getBufferedAmount() { return AsyncTask.completed(0); }
        @Override public AsyncTask<Void> setBufferedAmountLowThreshold(int threshold) { return AsyncTask.completed(null); }
        @Override public AsyncTask<Void> close() { return AsyncTask.completed(null); }
    }
}
