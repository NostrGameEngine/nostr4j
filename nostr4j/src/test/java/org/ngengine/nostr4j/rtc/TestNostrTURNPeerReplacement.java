/**
 * BSD 3-Clause License
 *
 * Copyright (c) 2026, Riccardo Balbo
 */
package org.ngengine.nostr4j.rtc;

import static org.junit.Assert.*;

import java.lang.reflect.*;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.function.LongSupplier;
import org.junit.Test;
import org.ngengine.nostr4j.event.SignedNostrEvent;
import org.ngengine.nostr4j.keypair.NostrKeyPair;
import org.ngengine.nostr4j.rtc.listeners.NostrTURNChannelListener;
import org.ngengine.nostr4j.rtc.signal.NostrRTCLocalPeer;
import org.ngengine.nostr4j.rtc.turn.NostrTURNCodec;
import org.ngengine.nostr4j.rtc.turn.NostrTURNDisconnectEvent;

/**
 * Actual room queue + ensureTurn owners, reused qualified linked relay; no policy simulation.
 */
public final class TestNostrTURNPeerReplacement {

    @Test(timeout = 15000L)
    public void signedPeerReplacementPreservesSharedQueue() throws Exception {
        check(true);
    }

    @Test(timeout = 15000L)
    public void signedPeerReplacementPreservesDistinctSendQueue() throws Exception {
        check(false);
    }

    private static void check(boolean shared) throws Exception {
        AtomicLong clock = new AtomicLong(1_000_000L);
        Class<?> type = Class.forName(TestNostrTURNAckRetry.class.getName() + "$RoomTurnFixture");
        Constructor<?> ctor = type.getDeclaredConstructor(LongSupplier.class, boolean.class, boolean.class);
        ctor.setAccessible(true);
        try (AutoCloseable fixture = (AutoCloseable) ctor.newInstance((LongSupplier) clock::get, true, shared)) {
            Object back = get(fixture, "back");
            ((AtomicBoolean) get(back, "holdDeliveryAcks")).set(true);
            call(fixture, "send", new Class<?>[] { ByteBuffer.class }, ByteBuffer.wrap(new byte[] { 42 }));
            waitFor(() -> ((List<?>) get(back, "heldAcks")).size() == 1);
            call(fixture, "send", new Class<?>[] { ByteBuffer.class }, ByteBuffer.wrap(new byte[] { 43 }));
            BlockingPacketQueue<?> queue = (BlockingPacketQueue<?>) get(fixture, "queue");
            Object head = ((Queue<?>) get(queue, "queue")).peek();
            NostrTURNChannel sender = (NostrTURNChannel) get(fixture, "sender");
            NostrRTCChannel logical = (NostrRTCChannel) get(fixture, "aliceLogical");
            AtomicReference<String> receivedReason = new AtomicReference<>();
            sender.addListener(
                new NostrTURNChannelListener() {
                    public void onTurnChannelReady(NostrTURNChannel c) {}

                    public void onTurnChannelMessage(NostrTURNChannel c, ByteBuffer p) {}

                    public void onTurnChannelError(NostrTURNChannel c, Throwable e) {}

                    public void onTurnChannelClosed(NostrTURNChannel c, String reason) {
                        receivedReason.set(reason);
                    }
                }
            );
            NostrRTCLocalPeer bob = (NostrRTCLocalPeer) get(fixture, "bob");
            NostrRTCSocket bobSocket = (NostrRTCSocket) get(fixture, "bobSocket");
            ByteBuffer frame = NostrTURNDisconnectEvent
                .createDisconnect(
                    bob,
                    bobSocket.getRemotePeer(),
                    (NostrKeyPair) get(fixture, "keys"),
                    "primary",
                    sender.getRoutingVsocketId(),
                    "rtc-p2p-established",
                    false
                )
                .encodeToFrame(Collections.emptyList())
                .await();
            SignedNostrEvent header = NostrTURNCodec.decodeHeader(frame.asReadOnlyBuffer());
            assertEquals("disconnect", header.getFirstTagFirstValue("t"));
            sender.onBinaryMessage(frame);
            waitFor(() -> receivedReason.get() != null);
            waitFor(() -> get(queue, "executionQueue") == null);
            Throwable failure = (Throwable) ((AtomicReference<?>) get(fixture, "lastFailure")).get();
            System.out.println(
                "SIGNED_DISCONNECT_PROOF shared=" +
                shared +
                " reason=" +
                receivedReason.get() +
                " logicalClosed=" +
                logical.isClosed() +
                " failures=" +
                ((AtomicInteger) get(fixture, "failures")).get() +
                " queueSize=" +
                queue.size() +
                " sendRetained=" +
                (get(logical, "turnSend") == sender)
            );
            if (failure != null) failure.printStackTrace(System.out);
            assertEquals("rtc-p2p-established", receivedReason.get());
            assertFalse(logical.isClosed());
            assertEquals(
                "non-error physical replacement must retain application head",
                0,
                ((AtomicInteger) get(fixture, "failures")).get()
            );
            assertSame(head, ((Queue<?>) get(queue, "queue")).peek());
            assertEquals(2, queue.size());
            assertNull("closed sending registration must be detached", get(logical, "turnSend"));
            Object packet = get(head, "packet");
            Object originalTime = get(head, "enqueuedAtMs");
            call(fixture, "reconnectOwnedPair", new Class<?>[0]);
            NostrTURNChannel next = (NostrTURNChannel) get(logical, "turnSend");
            assertNotSame(sender, next);
            // The old registration's late callback must not retire the new owned pair.
            for (Object l : (Iterable<?>) get(sender, "listeners")) ((NostrTURNChannelListener) l).onTurnChannelClosed(
                    sender,
                    "stale-close"
                );
            assertSame(next, get(logical, "turnSend"));
            queue.loop();
            waitFor(() -> ((List<?>) get(back, "heldAcks")).size() == 2);
            assertSame(head, ((Queue<?>) get(queue, "queue")).peek());
            assertSame(packet, ((List<?>) get(fixture, "sentPreparedPackets")).get(1));
            assertEquals(originalTime, ((List<?>) get(fixture, "sentEnqueuedTimes")).get(1));
            call(back, "releaseAck", new Class<?>[] { int.class }, 0);
            assertEquals(
                "old receipt cannot acknowledge a new physical attempt",
                0,
                ((AtomicInteger) get(fixture, "successes")).get()
            );
            assertEquals(2, queue.size());
            call(back, "releaseAck", new Class<?>[] { int.class }, 1);
            waitFor(() ->
                ((AtomicInteger) get(fixture, "successes")).get() == 1 && ((List<?>) get(back, "heldAcks")).size() == 3
            );
            call(back, "releaseAck", new Class<?>[] { int.class }, 2);
            waitFor(() -> ((AtomicInteger) get(fixture, "successes")).get() == 2);
            assertEquals(0, ((AtomicInteger) get(fixture, "failures")).get());
            assertEquals(
                "retry must not duplicate application delivery",
                2,
                ((AtomicInteger) get(fixture, "applicationDeliveries")).get()
            );
            assertEquals(0, queue.size());
        }
    }

    @Test(timeout = 15000L)
    public void receivedReplacementRetainsOriginalFiniteDeadline() throws Exception {
        AtomicLong clock = new AtomicLong(1_000_000L);
        try (AutoCloseable fixture = fixture(clock, true, false)) {
            Object back = get(fixture, "back");
            ((AtomicBoolean) get(back, "holdDeliveryAcks")).set(true);
            call(fixture, "send", new Class<?>[] { ByteBuffer.class }, ByteBuffer.wrap(new byte[] { 44 }));
            waitFor(() -> ((List<?>) get(back, "heldAcks")).size() == 1);
            BlockingPacketQueue<?> queue = (BlockingPacketQueue<?>) get(fixture, "queue");
            Object head = ((Queue<?>) get(queue, "queue")).peek();
            clock.addAndGet(20_000L);
            peerClose(fixture, false, "rtc-p2p-established");
            waitFor(() -> get(queue, "executionQueue") == null);
            assertEquals(0, ((AtomicInteger) get(fixture, "failures")).get());
            assertSame(head, ((Queue<?>) get(queue, "queue")).peek());
            clock.addAndGet(9_999L);
            queue.loop();
            assertEquals(0, ((AtomicInteger) get(fixture, "failures")).get());
            clock.incrementAndGet();
            queue.loop();
            waitFor(() -> ((AtomicInteger) get(fixture, "failures")).get() == 1);
            assertEquals(0, queue.size());
            assertEquals(0, ((AtomicInteger) get(fixture, "successes")).get());
        }
    }

    @Test(timeout = 15000L)
    public void errorPeerCloseRemainsTerminal() throws Exception {
        hardClose(true, true, false);
    }

    @Test(timeout = 15000L)
    public void standalonePeerCloseRemainsTerminal() throws Exception {
        hardClose(false, false, false);
    }

    @Test(timeout = 15000L)
    public void logicalRetirementDuringCloseCallbackRemainsTerminal() throws Exception {
        hardClose(true, false, true);
    }

    private static void hardClose(boolean owned, boolean error, boolean retireOnClose) throws Exception {
        AtomicLong clock = new AtomicLong(1_000_000L);
        try (AutoCloseable fixture = fixture(clock, owned, false)) {
            Object back = get(fixture, "back");
            ((AtomicBoolean) get(back, "holdDeliveryAcks")).set(true);
            call(fixture, "send", new Class<?>[] { ByteBuffer.class }, ByteBuffer.wrap(new byte[] { 45 }));
            waitFor(() -> ((List<?>) get(back, "heldAcks")).size() == 1);
            NostrTURNChannel sender = (NostrTURNChannel) get(fixture, "sender");
            NostrRTCChannel logical = (NostrRTCChannel) get(fixture, "aliceLogical");
            if (retireOnClose) sender.addListener(
                new NostrTURNChannelListener() {
                    public void onTurnChannelReady(NostrTURNChannel c) {}

                    public void onTurnChannelMessage(NostrTURNChannel c, ByteBuffer b) {}

                    public void onTurnChannelError(NostrTURNChannel c, Throwable e) {}

                    public void onTurnChannelClosed(NostrTURNChannel c, String reason) {
                        logical.close();
                    }
                }
            );
            peerClose(fixture, error, "actual-peer-close");
            BlockingPacketQueue<?> queue = (BlockingPacketQueue<?>) get(fixture, "queue");
            // Logical termination invalidates the in-flight attempt; original finite expiry must still reject it.
            if (retireOnClose) {
                clock.addAndGet(30_000L);
                queue.loop();
            }
            waitFor(() -> ((AtomicInteger) get(fixture, "failures")).get() == 1);
            Throwable failure = (Throwable) ((AtomicReference<?>) get(fixture, "lastFailure")).get();
            assertFalse(org.ngengine.nostr4j.rtc.delivery.DeliveryFailures.isRetryable(failure));
            assertEquals(0, ((AtomicInteger) get(fixture, "successes")).get());
            assertEquals(0, queue.size());
            call(back, "releaseAck", new Class<?>[] { int.class }, 0);
            queue.loop();
            assertEquals(1, ((AtomicInteger) get(fixture, "failures")).get());
        }
    }

    private static AutoCloseable fixture(AtomicLong clock, boolean owned, boolean shared) throws Exception {
        Class<?> type = Class.forName(TestNostrTURNAckRetry.class.getName() + "$RoomTurnFixture");
        Constructor<?> ctor = type.getDeclaredConstructor(LongSupplier.class, boolean.class, boolean.class);
        ctor.setAccessible(true);
        return (AutoCloseable) ctor.newInstance((LongSupplier) clock::get, owned, shared);
    }

    private static void peerClose(Object fixture, boolean error, String reason) throws Exception {
        NostrTURNChannel sender = (NostrTURNChannel) get(fixture, "sender");
        NostrRTCSocket bobSocket = (NostrRTCSocket) get(fixture, "bobSocket");
        ByteBuffer frame = NostrTURNDisconnectEvent
            .createDisconnect(
                (NostrRTCLocalPeer) get(fixture, "bob"),
                bobSocket.getRemotePeer(),
                (NostrKeyPair) get(fixture, "keys"),
                "primary",
                sender.getRoutingVsocketId(),
                reason,
                error
            )
            .encodeToFrame(Collections.emptyList())
            .await();
        sender.onBinaryMessage(frame);
    }

    interface Check {
        boolean ready() throws Exception;
    }

    static void waitFor(Check c) throws Exception {
        long end = System.nanoTime() + 3_000_000_000L;
        while (System.nanoTime() < end) {
            if (c.ready()) return;
            Thread.sleep(10);
        }
        throw new AssertionError("condition timeout");
    }

    static Object get(Object o, String name) throws Exception {
        Field f = o.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(o);
    }

    static Object call(Object o, String name, Class<?>[] types, Object... args) throws Exception {
        Method m = o.getClass().getDeclaredMethod(name, types);
        m.setAccessible(true);
        try {
            return m.invoke(o, args);
        } catch (InvocationTargetException e) {
            throw (Exception) e.getCause();
        }
    }
}
