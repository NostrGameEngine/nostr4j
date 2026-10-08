/**
 * BSD 3-Clause License
 *
 * Copyright (c) 2025, Riccardo Balbo
 */
package org.ngengine.nostr4j.rtc;

import static org.junit.Assert.*;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.ngengine.nostr4j.RTCSettings;
import org.ngengine.nostr4j.keypair.NostrKeyPair;
import org.ngengine.nostr4j.rtc.listeners.NostrRTCSocketListener;
import org.ngengine.nostr4j.rtc.signal.NostrRTCAnswerSignal;
import org.ngengine.nostr4j.rtc.signal.NostrRTCLocalPeer;
import org.ngengine.nostr4j.rtc.signal.NostrRTCPeer;
import org.ngengine.nostr4j.rtc.signal.NostrRTCRouteSignal;
import org.ngengine.nostr4j.signer.NostrKeyPairSigner;
import org.ngengine.platform.AsyncExecutor;
import org.ngengine.platform.AsyncTask;
import org.ngengine.platform.NGEPlatform;
import org.ngengine.platform.NGEUtils;
import org.ngengine.platform.jvm.JVMAsyncPlatform;
import org.ngengine.platform.transport.RTCDataChannel;
import org.ngengine.platform.transport.RTCTransport;
import org.ngengine.platform.transport.RTCTransportIceCandidate;
import org.ngengine.platform.transport.RTCTransportListener;

/**
 * Offline lifecycle regression: cancelled callbacks intentionally run after a newer attempt starts.
 */
public class TestNostrRTCSocketAttempts {

    private CapturedExecutor executor;
    private CapturedPlatform platform;
    private NGEPlatform previousPlatform;
    private NostrRTCSocket socket;
    private NostrKeyPair keys;
    private NostrRTCLocalPeer local;
    private NostrRTCPeer remote;

    @Before
    public void setup() throws Exception {
        executor = new CapturedExecutor();
        platform = new CapturedPlatform();
        Field installed = NGEPlatform.class.getDeclaredField("platform");
        installed.setAccessible(true);
        previousPlatform = (NGEPlatform) installed.get(null);
        installed.set(null, platform);
        keys = new NostrKeyPair();
        RTCSettings settings = RTCSettings
            .getDefault("deadline-app", "deadline-proto")
            .withStunServers(Collections.emptyList());
        NostrKeyPairSigner first = NostrKeyPairSigner.generate();
        NostrKeyPairSigner second = NostrKeyPairSigner.generate();
        if (
            NGEUtils
                .awaitNoThrow(first.getPublicKey())
                .asHex()
                .compareTo(NGEUtils.awaitNoThrow(second.getPublicKey()).asHex()) >
            0
        ) {
            NostrKeyPairSigner swap = first;
            first = second;
            second = swap;
        }
        local = new NostrRTCLocalPeer(settings, first, "local", keys, "ws://memory.invalid");
        remote =
            new NostrRTCPeer(
                NGEUtils.awaitNoThrow(second.getPublicKey()),
                "deadline-app",
                "deadline-proto",
                "remote",
                keys.getPublicKey(),
                "ws://memory.invalid"
            );
        socket = new NostrRTCSocket(executor, remote, keys, local, settings, null);
    }

    @After
    public void cleanup() throws Exception {
        try {
            if (socket != null) socket.close();
            if (executor != null) executor.close();
            if (keys != null) keys.close();
        } finally {
            Field installed = NGEPlatform.class.getDeclaredField("platform");
            installed.setAccessible(true);
            installed.set(null, previousPlatform);
        }
    }

    @Test
    public void aSupersededRoomOwnerCannotCompleteAfterAnotherOwnerEnablesTheSocket() {
        java.util.concurrent.atomic.AtomicBoolean owner = new java.util.concurrent.atomic.AtomicBoolean(true);
        assertTrue(socket.enablePhysicalAttempt(owner::get));
        NGEUtils.awaitNoThrow(socket.listen(owner::get));
        CapturedTransport obsolete = platform.created.get(0);
        owner.set(false);
        assertTrue(socket.enablePhysicalAttempt(() -> true));
        obsolete.listener.onRTCConnected();
        assertFalse("The native listener belongs to the old room attempt", socket.isRTCConnected());
    }

    @Test(timeout = 5000L)
    public void olderCleanupCannotConsumeANewerReservedGeneration() throws Exception {
        NGEUtils.awaitNoThrow(socket.listen());
        CapturedTransport old = platform.created.get(0);
        old.closeEntered = new java.util.concurrent.CountDownLatch(1);
        old.closeRelease = new java.util.concurrent.CountDownLatch(1);
        NostrRTCSocket.PreparedRtcAttempt obsolete = socket.reserveRtcTransportAttempt(() -> true);
        assertNotNull(obsolete);
        assertTrue("The reservation remains pending while old native callbacks drain", socket.isPendingConnection());
        java.util.concurrent.atomic.AtomicReference<Throwable> failure = new java.util.concurrent.atomic.AtomicReference<>();
        Thread cleanup = new Thread(
            () -> {
                try {
                    obsolete.cleanup();
                } catch (Throwable error) {
                    failure.set(error);
                }
            },
            "obsolete-rtc-cleanup"
        );
        cleanup.setDaemon(true);
        cleanup.start();
        try {
            assertTrue(old.closeEntered.await(2, TimeUnit.SECONDS));
            NostrRTCSocket.PreparedRtcAttempt current = socket.reserveRtcTransportAttempt(() -> true);
            assertNotNull(current);
            current.cleanup();
            assertTrue("Reservation must proceed without waiting for the old provider's close", current.isCurrent());
            old.closeRelease.countDown();
            cleanup.join(1000L);
            assertFalse(cleanup.isAlive());
            assertNull(failure.get());
            assertFalse(obsolete.isCurrent());
            assertTrue(socket.listen(obsolete).isFailed());
            assertEquals("Obsolete work must not call the native factory", 1, platform.created.size());
            NGEUtils.awaitNoThrow(socket.listen(current));
            assertEquals(2, platform.created.size());
            assertTrue(socket.listen(current).isFailed());
            current.cleanup();
            assertEquals("Captured cleanup is owned once", 1, old.closes);
            assertEquals(0, platform.created.get(1).closes);
        } finally {
            old.closeRelease.countDown();
            cleanup.join(1000L);
        }
    }

    @Test
    public void retiringAPreparedAttemptCannotStartAnotherNativeTransport() {
        NGEUtils.awaitNoThrow(socket.listen());
        NostrRTCSocket.PreparedRtcAttempt attempt = socket.reserveRtcTransportAttempt(() -> true);
        assertNotNull(attempt);
        socket.close();
        attempt.cleanup();
        assertFalse(attempt.isCurrent());
        assertTrue(socket.listen(attempt).isFailed());
        assertEquals("Retirement must not allocate a replacement", 1, platform.created.size());
    }

    @Test
    public void freshIncomingReservationKeepsIceThatArrivedBeforeTheOffer() {
        RTCTransportIceCandidate ice = new RTCTransportIceCandidate("incoming-candidate", "0");
        socket.mergeRemoteRTCIceCandidate(new NostrRTCRouteSignal(local.getSigner(), keys, remote, List.of(ice), null));
        NostrRTCSocket.PreparedRtcAttempt attempt = socket.reserveRtcTransportAttempt(() -> true, false);
        assertNotNull(attempt);
        attempt.cleanup();
        NGEUtils.awaitNoThrow(
            socket.connect(
                new org.ngengine.nostr4j.rtc.signal.NostrRTCOfferSignal(local.getSigner(), keys, remote, "incoming-offer"),
                attempt
            )
        );
        assertEquals(List.of(ice), platform.created.get(0).remoteCandidates);
    }

    @Test
    public void staleDeadlineCannotClearReplacementAttemptDeadline() throws Exception {
        NGEUtils.awaitNoThrow(socket.listen());
        CapturedTimer old = executor.lastDeadline();
        socket.prepareRtcTransportAttempt();
        NGEUtils.awaitNoThrow(socket.listen());
        assertTrue("The old deadline wrapper was cancelled", old.handle.isFailed());
        Object currentDeadline = field("rtcConnectDeadlineTask");
        old.callback.call(); // model cancellation racing a callback already dequeued
        assertSame(
            "A cancelled RTC deadline erased the replacement attempt's live deadline",
            currentDeadline,
            field("rtcConnectDeadlineTask")
        );
        assertTrue("A stale callback must not end the replacement attempt", socket.isPendingConnection());
        assertFalse("A stale callback must not activate fallback early", socket.isTurnFallbackAllowed());
    }

    @Test
    public void staleDeadlineCannotClearNewerAnswerPhaseOnSameTransport() throws Exception {
        NGEUtils.awaitNoThrow(socket.listen());
        CapturedTimer old = executor.lastDeadline();
        socket.connect(new NostrRTCAnswerSignal(local.getSigner(), keys, remote, "answer"));
        Object currentDeadline = field("rtcConnectDeadlineTask");
        old.callback.call();
        assertSame(
            "Listen-phase timeout erased the later answer-phase deadline",
            currentDeadline,
            field("rtcConnectDeadlineTask")
        );
        assertTrue(socket.isPendingConnection());
    }

    @Test
    public void currentDeadlineStillEnablesFallbackWithoutChangingOriginalWindows() throws Exception {
        NGEUtils.awaitNoThrow(socket.listen());
        CapturedTimer deadline = executor.lastDeadline();
        assertEquals("Native P2P attempt duration is unchanged", Duration.ofSeconds(30), platform.lastNativeTimeout);
        assertEquals("The source's existing give-up duration is unchanged", 120000L, deadline.delayMillis);
        assertFalse(socket.isTurnFallbackAllowed());
        deadline.callback.call();
        assertTrue(socket.isTurnFallbackAllowed());
        assertFalse(socket.isPendingConnection());
        assertNull(field("rtcConnectDeadlineTask"));
    }

    @Test
    public void cancelledDeadlineAfterCloseDoesNotEnableFallback() throws Exception {
        NGEUtils.awaitNoThrow(socket.listen());
        CapturedTimer deadline = executor.lastDeadline();
        socket.close();
        deadline.callback.call();
        assertTrue(socket.isClosed());
        assertFalse(socket.isTurnFallbackAllowed());
        assertNull(field("rtcConnectDeadlineTask"));
    }

    @Test
    public void obsoleteTransportConnectedEventCannotCompleteReplacementAttempt() throws Exception {
        NGEUtils.awaitNoThrow(socket.listen());
        CapturedTransport old = platform.created.get(0);
        socket.prepareRtcTransportAttempt();
        NGEUtils.awaitNoThrow(socket.listen());
        Object currentDeadline = field("rtcConnectDeadlineTask");
        old.listener.onRTCConnected();
        assertFalse("Old transport marked the replacement attempt connected", socket.isRTCConnected());
        assertSame(currentDeadline, field("rtcConnectDeadlineTask"));
    }

    @Test
    public void obsoleteTransportDisconnectedEventCannotBreakConnectedReplacement() throws Exception {
        NGEUtils.awaitNoThrow(socket.listen());
        CapturedTransport old = platform.created.get(0);
        socket.prepareRtcTransportAttempt();
        NGEUtils.awaitNoThrow(socket.listen());
        platform.created.get(1).listener.onRTCConnected();
        old.listener.onRTCDisconnected("late-old-close");
        assertTrue("Old transport disconnected the replacement transport", socket.isRTCConnected());
        assertFalse(socket.isTurnFallbackAllowed());
    }

    @Test
    public void currentConnectedAndDisconnectedCallbacksStillWork() throws Exception {
        NGEUtils.awaitNoThrow(socket.listen());
        CapturedTransport current = platform.created.get(0);
        current.listener.onRTCConnected();
        assertTrue(socket.isRTCConnected());
        assertFalse(socket.isPendingConnection());
        assertNull(field("rtcConnectDeadlineTask"));
        current.listener.onRTCDisconnected("current-disconnect");
        assertFalse(socket.isRTCConnected());
        assertTrue(socket.isTurnFallbackAllowed());
    }

    @Test
    public void repeatedCloseAndAllLateLifecycleCallbacksKeepSocketClosed() throws Exception {
        NGEUtils.awaitNoThrow(socket.listen());
        CapturedTransport old = platform.created.get(0);
        CapturedTimer timeout = executor.lastDeadline();
        socket.close();
        socket.close();
        old.listener.onRTCConnected();
        old.listener.onRTCDisconnected("late-close");
        timeout.callback.call();
        assertTrue(socket.isClosed());
        assertFalse(socket.isRTCConnected());
        assertFalse(socket.isTurnFallbackAllowed());
        assertNull(field("rtcConnectDeadlineTask"));
        assertEquals(1, old.closes);
    }

    @Test
    public void physicalDisableRejectsLateDeadlineAndLifecycleCallbacks() throws Exception {
        NGEUtils.awaitNoThrow(socket.listen());
        CapturedTransport old = platform.created.get(0);
        CapturedTimer timeout = executor.lastDeadline();
        socket.setPhysicalLinkEnabled(false);
        old.listener.onRTCConnected();
        old.listener.onRTCDisconnected("late-disable");
        timeout.callback.call();
        assertFalse(socket.isPhysicalLinkEnabled());
        assertFalse(socket.isRTCConnected());
        assertFalse(socket.isPendingConnection());
        assertFalse(socket.isTurnFallbackAllowed());
        assertNull(field("rtcConnectDeadlineTask"));
    }

    @Test
    public void retryStartedByTimeoutListenerKeepsItsDeadlineAndDoesNotGetStaleFallback() throws Exception {
        socket.addListener(
            new NostrRTCSocketListener() {
                public void onRTCSocketRouteUpdate(NostrRTCSocket s, Collection<RTCTransportIceCandidate> c, String turn) {}

                public void onRTCSocketClose(NostrRTCSocket s) {}

                public void onRTCChannelReady(NostrRTCChannel c) {}

                public void onRTCChannel(NostrRTCChannel c) {}

                public void onRTCSocketTransportDegraded(NostrRTCSocket s, NostrRTCSocket.TransportPath path, String reason) {
                    s.prepareRtcTransportAttempt();
                    NGEUtils.awaitNoThrow(s.listen());
                }
            }
        );
        NGEUtils.awaitNoThrow(socket.listen());
        CapturedTimer expired = executor.lastDeadline();
        expired.callback.call();
        assertEquals(2, platform.created.size());
        assertTrue(socket.isPendingConnection());
        assertSame(executor.lastDeadline().handle, field("rtcConnectDeadlineTask"));
        assertFalse(
            "The superseded timeout must not enable fallback for a listener-started retry",
            socket.isTurnFallbackAllowed()
        );
    }

    @Test
    public void immediateCurrentConnectionDoesNotRearmItsCancelledDeadline() throws Exception {
        platform.connectOnAttach = true;
        NGEUtils.awaitNoThrow(socket.listen());
        assertTrue(socket.isRTCConnected());
        assertFalse(socket.isPendingConnection());
        assertNull(field("rtcConnectDeadlineTask"));
    }

    @Test
    public void preparingReplacementIgnoresSynchronousDisconnectFromOldTransportClose() throws Exception {
        NGEUtils.awaitNoThrow(socket.listen());
        CapturedTransport old = platform.created.get(0);
        old.disconnectOnClose = true;
        socket.prepareRtcTransportAttempt();
        assertFalse(socket.isRTCConnected());
        assertFalse(
            "Closing an obsolete transport must not enable fallback for its replacement",
            socket.isTurnFallbackAllowed()
        );
        assertNull(field("rtcConnectDeadlineTask"));
        assertEquals(1, old.closes);
    }

    private Object field(String name) throws Exception {
        Field field = NostrRTCSocket.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(socket);
    }

    @Test
    public void synchronousNativeFactoryFailureEndsTheRoomAttemptExactlyOnce() throws Exception {
        RTCSettings settings = RTCSettings
            .getDefault("deadline-app", "deadline-proto")
            .withSignalingRelays(Collections.emptyList())
            .withStunServers(Collections.emptyList());
        try (NostrRTCRoom room = new NostrRTCRoom(settings, local, keys, new org.ngengine.nostr4j.NostrPool(), null)) {
            java.lang.reflect.Method ensure = NostrRTCRoom.class.getDeclaredMethod("ensureLogicalSocket", NostrRTCPeer.class);
            ensure.setAccessible(true);
            NostrRTCSocket logical = (NostrRTCSocket) ensure.invoke(room, remote);
            Field owner = NostrRTCRoom.class.getDeclaredField("physicalConnections");
            owner.setAccessible(true);
            PhysicalConnectionManager manager = (PhysicalConnectionManager) owner.get(room);
            PhysicalConnectionManager.Attempt attempt = manager.fill().get(0);
            java.lang.reflect.Method prepare =
                NostrRTCRoom.class.getDeclaredMethod("preparePhysicalConnection", PhysicalConnectionManager.Attempt.class);
            prepare.setAccessible(true);
            prepare.invoke(room, attempt);
            platform.failTransportCreation = true;
            java.lang.reflect.Method offer =
                NostrRTCRoom.class.getDeclaredMethod(
                        "createPhysicalOffer",
                        PhysicalConnectionManager.Attempt.class,
                        NostrRTCSocket.class
                    );
            offer.setAccessible(true);
            offer.invoke(room, attempt, logical);
            assertFalse(manager.active(attempt));
            assertEquals(1, manager.diagnostics().get(0).getFailureCount());
            manager.fail(attempt, "late-failure");
            assertEquals(1, manager.diagnostics().get(0).getFailureCount());
        }
    }

    @Test
    public void remoteIceBeforeDescriptionIsBufferedBoundedAndFlushedOnce() throws Exception {
        List<RTCTransportIceCandidate> candidates = new ArrayList<>();
        for (int i = 0; i < 200; i++) candidates.add(new RTCTransportIceCandidate("test-candidate-" + i, "0"));
        NostrRTCRouteSignal route = new NostrRTCRouteSignal(local.getSigner(), keys, remote, candidates, null);
        socket.mergeRemoteRTCIceCandidate(route);
        NGEUtils.awaitNoThrow(socket.listen());
        assertEquals(128, platform.created.get(0).remoteCandidates.size());
        socket.prepareRtcTransportAttempt();
        NGEUtils.awaitNoThrow(socket.listen());
        assertTrue(platform.created.get(1).remoteCandidates.isEmpty());
        socket.mergeRemoteRTCIceCandidate(route, () -> false);
        assertTrue("Obsolete ICE must not reach a replacement", platform.created.get(1).remoteCandidates.isEmpty());
    }

    @Test
    public void transportRetriesKeepOnlyOneCurrentChannelMaintenanceLoop() throws Exception {
        NGEUtils.awaitNoThrow(socket.listen());
        CapturedTransport first = platform.created.get(0);
        first.listener.onRTCConnected();
        long initial = executor.timers.stream().filter(t -> t.delayMillis == 100L).count();
        first.listener.onRTCConnected();
        assertEquals(initial, executor.timers.stream().filter(t -> t.delayMillis == 100L).count());
        CapturedTimer previous = executor.timers.stream().filter(t -> t.delayMillis == 100L).reduce((a, b) -> b).orElseThrow();
        socket.prepareRtcTransportAttempt();
        NGEUtils.awaitNoThrow(socket.listen());
        platform.created.get(1).listener.onRTCConnected();
        long current = executor.timers.stream().filter(t -> t.delayMillis == 100L).count();
        previous.callback.call();
        assertEquals(
            "Old maintenance must not perpetuate after retry",
            current,
            executor.timers.stream().filter(t -> t.delayMillis == 100L).count()
        );
    }

    private static final class CapturedTimer {

        private final Callable<?> callback;
        private final long delayMillis;
        private final AsyncTask<?> handle;

        private CapturedTimer(Callable<?> callback, long delayMillis, AsyncTask<?> handle) {
            this.callback = callback;
            this.delayMillis = delayMillis;
            this.handle = handle;
        }
    }

    private static final class CapturedExecutor implements AsyncExecutor {

        private final List<CapturedTimer> timers = new ArrayList<CapturedTimer>();

        @Override
        public <T> AsyncTask<T> runLater(Callable<T> callback, long delay, TimeUnit unit) {
            AsyncTask<T> task = AsyncTask.create((resolve, reject) -> {});
            timers.add(new CapturedTimer(callback, unit.toMillis(delay), task));
            return task;
        }

        @Override
        public <T> AsyncTask<T> run(Callable<T> callback) {
            try {
                return AsyncTask.completed(callback.call());
            } catch (Exception error) {
                return AsyncTask.failed(error);
            }
        }

        private CapturedTimer lastDeadline() {
            return timers.stream().filter(timer -> timer.delayMillis == 120000L).reduce((first, last) -> last).orElseThrow();
        }

        @Override
        public void close() {}
    }

    private static final class CapturedPlatform extends JVMAsyncPlatform {

        private final List<CapturedTransport> created = new ArrayList<CapturedTransport>();
        private Duration lastNativeTimeout;
        private boolean connectOnAttach;
        private boolean deferListen;
        private boolean deferConnect;
        private boolean failTransportCreation;

        @Override
        public RTCTransport newRTCTransport(Duration timeout, String session, Collection<String> stun) {
            if (failTransportCreation) throw new IllegalStateException("Injected native factory failure");
            lastNativeTimeout = timeout;
            CapturedTransport transport = new CapturedTransport();
            transport.connectOnAttach = connectOnAttach;
            transport.deferListen = deferListen;
            transport.deferConnect = deferConnect;
            created.add(transport);
            return transport;
        }
    }

    private static final class CapturedTransport implements RTCTransport {

        private RTCTransportListener listener;
        private int closes;
        private final List<RTCTransportIceCandidate> remoteCandidates = new ArrayList<>();
        private boolean connectOnAttach;
        private boolean disconnectOnClose;
        private RTCDataChannel closeChannel;
        private boolean deferListen;
        private boolean deferConnect;
        private boolean connected;
        private java.util.function.Consumer<String> listenResolve;
        private java.util.function.Consumer<String> connectResolve;
        private java.util.concurrent.CountDownLatch closeEntered;
        private java.util.concurrent.CountDownLatch closeRelease;
        private java.util.concurrent.CountDownLatch connectedEntered;
        private java.util.concurrent.CountDownLatch connectedRelease;

        @Override
        public void start(Duration timeout, AsyncExecutor executor, String id, Collection<String> stun) {}

        @Override
        public void addListener(RTCTransportListener listener) {
            this.listener = listener;
            if (connectOnAttach) listener.onRTCConnected();
        }

        @Override
        public void removeListener(RTCTransportListener listener) {
            if (this.listener == listener) this.listener = null;
        }

        @Override
        public void close() {
            closes++;
            gate(closeEntered, closeRelease);
            if (closeChannel != null) listener.onRTCChannelClosed(closeChannel);
            if (disconnectOnClose) listener.onRTCDisconnected("synchronous-old-close");
        }

        @Override
        public String getName() {
            return "captured-rtc";
        }

        @Override
        public boolean isConnected() {
            gate(connectedEntered, connectedRelease);
            return connected;
        }

        @Override
        public AsyncTask<String> listen() {
            return deferListen ? AsyncTask.create((resolve, reject) -> listenResolve = resolve) : AsyncTask.completed("offer");
        }

        @Override
        public AsyncTask<String> connect(String value) {
            return deferConnect ? AsyncTask.create((resolve, reject) -> connectResolve = resolve) : AsyncTask.completed(null);
        }

        private void gate(java.util.concurrent.CountDownLatch entered, java.util.concurrent.CountDownLatch release) {
            if (entered == null) return;
            entered.countDown();
            try {
                release.await(2, TimeUnit.SECONDS);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            }
        }

        @Override
        public RTCDataChannel getDataChannel(String name) {
            return null;
        }

        @Override
        public void addRemoteIceCandidates(Collection<RTCTransportIceCandidate> candidates) {
            remoteCandidates.addAll(candidates);
        }

        @Override
        public AsyncTask<RTCDataChannel> createDataChannel(
            String name,
            String protocol,
            boolean ordered,
            boolean reliable,
            int retransmits,
            Duration lifetime
        ) {
            return AsyncTask.failed(new IllegalStateException("No data channels in the lifecycle fixture"));
        }
    }
}
