/**
 * BSD 3-Clause License
 *
 * Copyright (c) 2025, Riccardo Balbo
 */
package org.ngengine.nostr4j.rtc;

import static org.junit.Assert.*;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.ngengine.nostr4j.keypair.NostrKeyPair;
import org.ngengine.nostr4j.rtc.signal.*;
import org.ngengine.nostr4j.rtc.routing.InternalRoutingChannels;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.*;
import org.ngengine.platform.*;
import org.ngengine.platform.transport.*;

/** Deterministic interleavings for native callbacks already running when their transport is replaced. */
public class TestNostrRTCSocketCallbackRaces {
    private TestNostrRTCSocketAttempts fixture;
    private NostrRTCSocket socket;
    private Object platform;
    @Before public void setup() throws Exception {
        fixture = new TestNostrRTCSocketAttempts();fixture.setup();
        socket = (NostrRTCSocket) get(fixture,"socket");platform=get(fixture,"platform");
    }
    @After public void cleanup() throws Exception {fixture.cleanup();}

    @Test(timeout=5000L) public void connectedCallbackAlreadyPastIdentityCheckCannotCancelReplacementDeadline() throws Exception {
        NGEUtils.awaitNoThrow(socket.listen());RTCTransportListener old=listener(0);
        Thread callback = new Thread(old::onRTCConnected,"old-connected");Object replacementDeadline;
        synchronized(socket) {
            callback.start();awaitBlocked(callback);
            socket.prepareRtcTransportAttempt();NGEUtils.awaitNoThrow(socket.listen());
            replacementDeadline=get(socket,"rtcConnectDeadlineTask");
        }
        callback.join(2000L);assertFalse(callback.isAlive());
        assertSame("An already-entered old connected callback cancelled the replacement deadline",replacementDeadline,get(socket,"rtcConnectDeadlineTask"));
        assertEquals(NostrRTCSocket.TransportPath.NONE,socket.getActiveTransportPath());
    }
    @Test(timeout=5000L) public void disconnectedCallbackAlreadyPastIdentityCheckCannotDowngradeConnectedReplacement() throws Exception {
        NGEUtils.awaitNoThrow(socket.listen());RTCTransportListener old=listener(0);old.onRTCConnected();
        Thread callback = new Thread(() -> old.onRTCDisconnected("old-disconnect"),"old-disconnected");
        synchronized(socket) {
            callback.start();awaitBlocked(callback);
            socket.prepareRtcTransportAttempt();NGEUtils.awaitNoThrow(socket.listen());listener(1).onRTCConnected();
        }
        callback.join(2000L);assertFalse(callback.isAlive());
        assertTrue(socket.isRTCConnected());
        assertEquals("An already-entered old disconnected callback changed the replacement path",NostrRTCSocket.TransportPath.RTC,socket.getActiveTransportPath());
    }
    @Test(timeout=5000L) public void oldChannelClosedCallbackPausedAfterIdentityCheckCannotClearReplacementChannel() throws Exception {
        NGEUtils.awaitNoThrow(socket.listen());RTCTransportListener old=listener(0);
        NostrRTCChannel logical=socket.createChannel("game");GateChannel oldChannel=new GateChannel(true);
        Thread callback=new Thread(() -> old.onRTCChannelClosed(oldChannel),"old-channel-close");
        callback.start();assertTrue(oldChannel.entered.await(2,TimeUnit.SECONDS));
        try {
            socket.prepareRtcTransportAttempt();NGEUtils.awaitNoThrow(socket.listen());
            listener(1).onRTCChannelReady(new GateChannel(false));assertTrue(logical.isConnected());
        } finally {oldChannel.release.countDown();}
        callback.join(2000L);assertFalse(callback.isAlive());
        assertTrue("An already-entered old close callback cleared the replacement logical channel",logical.isConnected());
    }
    @Test(timeout=5000L)
    public void preparingReplacementDetachesOldNativeChannelBeforeIgnoringItsCloseCallback() throws Exception {
        NGEUtils.awaitNoThrow(socket.listen());
        NostrRTCChannel logical = socket.createChannel("game");
        RTCDataChannel oldChannel = new GateChannel(false);
        listener(0).onRTCChannelReady(oldChannel);
        assertTrue("Fixture requires an attached non-default native channel", logical.isConnected());
        Object oldTransport = ((List<?>) get(platform, "created")).get(0);
        Field closeChannel = oldTransport.getClass().getDeclaredField("closeChannel");
        closeChannel.setAccessible(true);
        closeChannel.set(oldTransport, oldChannel);

        socket.prepareRtcTransportAttempt();
        assertFalse("A replacement must not keep a closed native handle and skip resurrection", logical.isConnected());
        assertNull(get(logical, "channel"));

        NGEUtils.awaitNoThrow(socket.listen());
        RTCDataChannel replacementChannel = new GateChannel(false);
        listener(1).onRTCChannelReady(replacementChannel);
        assertTrue(logical.isConnected());
        assertSame(replacementChannel, get(logical, "channel"));
    }

    @Test(timeout=5000L)
    public void obsoleteDelayedOfferFailsWithoutChangingReplacement() throws Exception {
        set(platform, "deferListen", true);
        AsyncTask<NostrRTCOfferSignal> obsolete = socket.listen();
        Object oldTransport = transport(0);
        socket.prepareRtcTransportAttempt();
        set(platform, "deferListen", false);
        NGEUtils.awaitNoThrow(socket.listen());
        Object deadline = get(socket, "rtcConnectDeadlineTask");
        resolve(oldTransport, "listenResolve", "obsolete-offer");
        assertTrue("Superseded offer completion must not reach room signaling", obsolete.isFailed());
        assertSame(deadline, get(socket, "rtcConnectDeadlineTask"));
        assertTrue(socket.isPendingConnection());
    }

    @Test(timeout=5000L)
    public void obsoleteDelayedAnswerFailsWithoutChangingReplacement() throws Exception {
        set(platform, "deferConnect", true);
        NostrRTCLocalPeer local = (NostrRTCLocalPeer) get(fixture, "local");
        NostrKeyPair keys = (NostrKeyPair) get(fixture, "keys");
        NostrRTCPeer remote = (NostrRTCPeer) get(fixture, "remote");
        AsyncTask<NostrRTCAnswerSignal> obsolete = socket.connect(new NostrRTCOfferSignal(local.getSigner(), keys, remote, "offer"));
        Object oldTransport = transport(0);
        socket.prepareRtcTransportAttempt();
        NGEUtils.awaitNoThrow(socket.listen());
        Object deadline = get(socket, "rtcConnectDeadlineTask");
        resolve(oldTransport, "connectResolve", "obsolete-answer");
        assertTrue("Superseded answer completion must not reach room signaling", obsolete.isFailed());
        assertSame(deadline, get(socket, "rtcConnectDeadlineTask"));
        assertTrue(socket.isPendingConnection());
    }

    @Test(timeout=5000L)
    public void delayedOfferAfterCloseIsRejected() throws Exception {
        set(platform, "deferListen", true);
        AsyncTask<NostrRTCOfferSignal> obsolete = socket.listen();
        Object oldTransport = transport(0);
        socket.close();
        resolve(oldTransport, "listenResolve", "closed-offer");
        assertTrue(obsolete.isFailed());
        assertTrue(socket.isClosed());
    }

    @Test(timeout=5000L)
    public void currentDelayedOfferStillCompletes() throws Exception {
        set(platform, "deferListen", true);
        AsyncTask<NostrRTCOfferSignal> current = socket.listen();
        resolve(transport(0), "listenResolve", "current-offer");
        assertEquals("current-offer", current.await().getOfferString());
    }

    @Test(timeout=5000L)
    public void obsoleteInternalChannelReadyCannotCreateChannelAfterClose() throws Exception {
        checkObsoleteInternalCreation(true);
    }

    @Test(timeout=5000L)
    public void obsoleteInternalChannelReadyCannotCreateChannelInReplacement() throws Exception {
        checkObsoleteInternalCreation(false);
    }

    private void checkObsoleteInternalCreation(boolean close) throws Exception {
        NGEUtils.awaitNoThrow(socket.listen());
        RTCTransportListener old = listener(0);
        GateChannel internal = new GateChannel(true, InternalRoutingChannels.CONTROL);
        Thread callback = new Thread(() -> old.onRTCChannelReady(internal), "obsolete-internal-ready");
        callback.start();
        assertTrue(internal.entered.await(2, TimeUnit.SECONDS));
        try {
            if (close) socket.close();
            else { socket.prepareRtcTransportAttempt(); NGEUtils.awaitNoThrow(socket.listen()); }
            assertTrue(((Map<?, ?>) get(socket, "channels")).isEmpty());
        } finally { internal.release.countDown(); }
        callback.join(2000L);
        assertFalse(callback.isAlive());
        assertTrue("A superseded callback must not populate internal logical channels", ((Map<?, ?>) get(socket, "channels")).isEmpty());
    }

    @Test(timeout=5000L)
    public void staleResurrectionClaimCannotMarkReplacementChannelBusy() throws Exception {
        assertTrue("Fixture local peer must deterministically own channel initiation",
            socket.getLocalPeer().getPubkey().asHex().compareTo(socket.getRemotePeer().getPubkey().asHex()) < 0);
        NGEUtils.awaitNoThrow(socket.listen());
        NostrRTCChannel logical = socket.createChannel("game");
        Object oldTransport = transport(0);
        set(oldTransport, "connected", true);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        set(oldTransport, "connectedEntered", entered);
        set(oldTransport, "connectedRelease", release);
        Thread oldResurrection = new Thread(() -> socket.getChannel("game"), "obsolete-resurrection");
        oldResurrection.start();
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        try { socket.prepareRtcTransportAttempt(); NGEUtils.awaitNoThrow(socket.listen()); }
        finally { release.countDown(); }
        oldResurrection.join(2000L);
        assertFalse(oldResurrection.isAlive());
        assertFalse("An obsolete transport must not claim replacement channel resurrection", logical.isResurrecting());
    }

    /** Defensive socket-API interleaving; room neighbor updates normally serialize these toggles. */
    @Test(timeout=5000L)
    public void oldPhysicalDisableClosesOnlyItsCapturedNativeChannel() throws Exception {
        NGEUtils.awaitNoThrow(socket.listen());
        NostrRTCChannel logical = socket.createChannel("game");
        GateChannel oldChannel = new GateChannel(false);
        listener(0).onRTCChannelReady(oldChannel);
        Object oldTransport = transport(0);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        set(oldTransport, "closeEntered", entered);
        set(oldTransport, "closeRelease", release);
        Thread disable = new Thread(() -> socket.setPhysicalLinkEnabled(false), "old-disable");
        disable.start();
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        GateChannel current = new GateChannel(false);
        try {
            socket.setPhysicalLinkEnabled(true);
            NGEUtils.awaitNoThrow(socket.listen());
            listener(1).onRTCChannelReady(current);
        } finally { release.countDown(); }
        disable.join(2000L);
        assertFalse(disable.isAlive());
        assertSame("Old disable must not detach the re-enabled native channel", current, get(logical, "channel"));
        assertEquals(0, current.closes);
    }

    @Test(timeout=5000L)
    public void obsoleteChannelErrorPausedInLookupCannotNotifyReplacement() throws Exception {
        checkObsoleteChannelNotification(true);
    }

    @Test(timeout=5000L)
    public void obsoleteBufferedAmountEventPausedInLookupCannotNotifyReplacement() throws Exception {
        checkObsoleteChannelNotification(false);
    }

    private void checkObsoleteChannelNotification(boolean error) throws Exception {
        NGEUtils.awaitNoThrow(socket.listen());
        NostrRTCChannel logical = socket.createChannel("game");
        java.util.concurrent.atomic.AtomicInteger notifications = new java.util.concurrent.atomic.AtomicInteger();
        logical.addListener(new org.ngengine.nostr4j.rtc.listeners.NostrRTCChannelListener() {
            @Override public void onRTCSocketMessage(NostrRTCChannel channel, ByteBuffer payload, boolean turn) {}
            @Override public void onRTCChannelError(NostrRTCChannel channel, Throwable failure) { notifications.incrementAndGet(); }
            @Override public void onRTCChannelClosed(NostrRTCChannel channel) {}
            @Override public void onRTCBufferedAmountLow(NostrRTCChannel channel) { notifications.incrementAndGet(); }
        });
        RTCTransportListener old = listener(0);
        GateChannel obsolete = new GateChannel(true);
        Thread callback = new Thread(() -> {
            if (error) old.onRTCChannelError(obsolete, new IllegalStateException("expected obsolete channel error"));
            else old.onRTCBufferedAmountLow(obsolete);
        }, "obsolete-channel-notification");
        callback.start();
        assertTrue(obsolete.entered.await(2, TimeUnit.SECONDS));
        try { socket.prepareRtcTransportAttempt(); NGEUtils.awaitNoThrow(socket.listen()); }
        finally { obsolete.release.countDown(); }
        callback.join(2000L);
        assertFalse(callback.isAlive());
        assertEquals("A stale native lookup must not notify replacement channel listeners", 0, notifications.get());
    }

    @Test(timeout=5000L)
    public void publishedInternalChannelRegistrationSurvivesTransportRetry() throws Exception {
        checkPublishedRegistration(false);
    }

    @Test(timeout=5000L)
    public void publishedInternalChannelRegistrationStopsAfterSocketClose() throws Exception {
        checkPublishedRegistration(true);
    }

    private void checkPublishedRegistration(boolean close) throws Exception {
        NGEUtils.awaitNoThrow(socket.listen());
        java.util.concurrent.atomic.AtomicInteger registrations = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger delivered = new java.util.concurrent.atomic.AtomicInteger();
        socket.addInternalListener(new RegistrationListener() {
            @Override public void onRTCChannel(NostrRTCChannel channel) {
                if (close) socket.close();
                else { socket.prepareRtcTransportAttempt(); NGEUtils.awaitNoThrow(socket.listen()); }
            }
        });
        socket.addInternalListener(new RegistrationListener() {
            @Override public void onRTCChannel(NostrRTCChannel channel) {
                registrations.incrementAndGet();
                channel.addListener(new org.ngengine.nostr4j.rtc.listeners.NostrRTCChannelListener() {
                    @Override public void onRTCSocketMessage(NostrRTCChannel incoming, ByteBuffer payload, boolean turn) {
                        if (payload.remaining() == 1 && payload.get() == 7) delivered.incrementAndGet();
                    }
                    @Override public void onRTCChannelError(NostrRTCChannel incoming, Throwable failure) {}
                    @Override public void onRTCChannelClosed(NostrRTCChannel incoming) {}
                    @Override public void onRTCBufferedAmountLow(NostrRTCChannel incoming) {}
                });
            }
        });
        listener(0).onRTCChannelReady(new GateChannel(false, InternalRoutingChannels.CONTROL));
        if (close) {
            assertEquals(0, registrations.get());
            assertTrue(((Map<?, ?>) get(socket, "channels")).isEmpty());
            return;
        }
        assertEquals("Logical registration must finish once even if the transport retries during notification", 1, registrations.get());
        NostrRTCChannel logical = socket.getChannel(InternalRoutingChannels.CONTROL);
        assertNotNull(logical);
        ByteBuffer frame = ByteBuffer.allocate(13);
        frame.putLong(42L).putShort((short) 0).putShort((short) 1).put((byte) 7).flip();
        listener(1).onRTCBinaryMessage(new GateChannel(false, InternalRoutingChannels.CONTROL), frame);
        assertEquals("The persistent internal channel must retain its registered message listener", 1, delivered.get());
        assertEquals(1, registrations.get());
    }

    private static class RegistrationListener implements org.ngengine.nostr4j.rtc.listeners.NostrRTCSocketListener {
        @Override public void onRTCSocketRouteUpdate(NostrRTCSocket socket, java.util.Collection<RTCTransportIceCandidate> candidates, String turn) {}
        @Override public void onRTCSocketClose(NostrRTCSocket socket) {}
        @Override public void onRTCChannelReady(NostrRTCChannel channel) {}
        @Override public void onRTCChannel(NostrRTCChannel channel) {}
    }

    private Object transport(int index) throws Exception { return ((List<?>) get(platform, "created")).get(index); }
    private static void set(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); field.set(target, value);
    }
    @SuppressWarnings("unchecked")
    private static void resolve(Object target, String name, String value) throws Exception { ((Consumer<String>) get(target, name)).accept(value); }

    private RTCTransportListener listener(int index) throws Exception {
        Object transport=((List<?>)get(platform,"created")).get(index);return (RTCTransportListener)get(transport,"listener");
    }
    private static Object get(Object target,String name) throws Exception {Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(target);}
    private static void awaitBlocked(Thread thread) throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
        while(thread.getState()!=Thread.State.BLOCKED && System.nanoTime()<deadline) Thread.sleep(1);
        assertEquals("Callback must have passed its identity precheck and reached the socket monitor",Thread.State.BLOCKED,thread.getState());
    }
    private static final class GateChannel extends RTCDataChannel {
        final CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);final boolean gate; final String name; int closes;
        GateChannel(boolean gate){this(gate, "game");}
        GateChannel(boolean gate, String name){super(name,"protocol",true,true,0,null);this.gate=gate;this.name=name;}
        @Override public String getName(){if(gate){entered.countDown();try{release.await(2,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}}return name;}
        @Override public AsyncTask<RTCDataChannel> ready(){return AsyncTask.completed(this);}
        @Override public AsyncTask<Void> write(ByteBuffer b){return AsyncTask.completed(null);}
        @Override public AsyncTask<Number> getMaxMessageSize(){return AsyncTask.completed(65536);}
        @Override public AsyncTask<Number> getAvailableAmount(){return AsyncTask.completed(65536);}
        @Override public AsyncTask<Number> getBufferedAmount(){return AsyncTask.completed(0);}
        @Override public AsyncTask<Void> setBufferedAmountLowThreshold(int threshold){return AsyncTask.completed(null);}
        @Override public AsyncTask<Void> close(){closes++;return AsyncTask.completed(null);}
    }
}
