/**
 * BSD 3-Clause License
 *
 * Copyright (c) 2025, Riccardo Balbo
 */
package org.ngengine.nostr4j.rtc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.ngengine.nostr4j.RTCSettings;
import org.ngengine.nostr4j.keypair.NostrKeyPair;
import org.ngengine.nostr4j.rtc.NostrTURNPool.TURNTransport;
import org.ngengine.nostr4j.rtc.listeners.NostrTURNChannelListener;
import org.ngengine.nostr4j.rtc.signal.NostrRTCLocalPeer;
import org.ngengine.nostr4j.rtc.signal.NostrRTCPeer;
import org.ngengine.nostr4j.signer.NostrKeyPairSigner;
import org.ngengine.platform.AsyncExecutor;
import org.ngengine.platform.AsyncTask;
import org.ngengine.platform.NGEPlatform;
import org.ngengine.platform.jvm.JVMAsyncPlatform;
import org.ngengine.platform.transport.WebsocketTransport;
import org.ngengine.platform.transport.WebsocketTransportListener;

/** Exercises the real pool with synchronous callbacks and manually dequeued, cancelled timers. */
public class TestNostrTURNTransportLifecycle {

    private static final String TURN_URL = "ws://turn-lifecycle.invalid";
    private NGEPlatform previousPlatform;
    private LifecyclePlatform platform;
    private NostrTURNPool pool;
    private NostrKeyPair room;
    private NostrKeyPair localKeys;
    private NostrKeyPair remoteKeys;
    private NostrRTCLocalPeer local;
    private NostrRTCPeer remote;

    @Before
    public void setUp() throws Exception {
        previousPlatform = (NGEPlatform) field(NGEPlatform.class, "platform").get(null);
        platform = new LifecyclePlatform();
        field(NGEPlatform.class, "platform").set(null, platform);
        room = new NostrKeyPair();
        localKeys = new NostrKeyPair();
        remoteKeys = new NostrKeyPair();
        RTCSettings settings = RTCSettings
            .getDefault("lifecycle-app", "lifecycle-proto")
            .withStunServers(Collections.emptyList());
        local = new NostrRTCLocalPeer(settings, new NostrKeyPairSigner(localKeys), "local", room, TURN_URL);
        NostrRTCLocalPeer remoteLocal = new NostrRTCLocalPeer(
            settings,
            new NostrKeyPairSigner(remoteKeys),
            "remote",
            room,
            TURN_URL
        );
        remote =
            new NostrRTCPeer(
                remoteLocal.getPubkey(),
                "lifecycle-app",
                "lifecycle-proto",
                "remote",
                room.getPublicKey(),
                TURN_URL
            );
        pool = new NostrTURNPool(24);
        pool.setFailedResurrectionBackoff(2L, TimeUnit.HOURS);
    }

    @After
    public void tearDown() throws Exception {
        try {
            if (pool != null) pool.close();
            if (localKeys != null) localKeys.close();
            if (remoteKeys != null) remoteKeys.close();
            if (room != null) room.close();
        } finally {
            field(NGEPlatform.class, "platform").set(null, previousPlatform);
        }
    }

    @Test
    public void reentrantConnectionSharesPublishedAttemptWithoutAllocatingAnotherTransport() throws Exception {
        platform.mode = Mode.OPEN;
        AtomicReference<NostrTURNChannel> second = new AtomicReference<NostrTURNChannel>();
        platform.onNewTransport = () -> second.set(connect());
        NostrTURNChannel first = connect();
        assertEquals(1, platform.transports.size());
        assertSame(activeTransport(first), activeTransport(second.get()));
        assertEquals(2, activeTransport(first).getUsers().size());
        assertFalse(first.isResurrecting());
        assertFalse(second.get().isResurrecting());
    }

    @Test
    public void repeatedSynchronousFailuresCloseEveryAttemptOnceAndRespectBackoff() throws Exception {
        platform.mode = Mode.FAIL;
        NostrTURNChannel channel = connect();
        assertFalse(channel.isResurrecting());
        platform.scheduler.fireNext(100L);
        assertEquals("failure backoff must prevent another immediate allocation", 1, platform.transports.size());

        for (int attempt = 1; attempt < 10; attempt++) {
            allowRetry(channel);
            platform.scheduler.fireNext(100L);
            assertEquals(attempt + 1, platform.transports.size());
            assertFalse(channel.isResurrecting());
            assertNull(activeTransport(channel));
            assertPoolEmpty();
        }
        for (ControlledTransport transport : platform.transports) {
            assertEquals(1, transport.closeCount);
            assertTrue(transport.listeners.isEmpty());
            transport.fireLateOpen();
            transport.fireLateClose();
            assertEquals(1, transport.closeCount);
        }
        assertNull(activeTransport(channel));
        assertPoolEmpty();
    }

    @Test
    public void timeoutClosesOnceAndLateCallbacksCannotReplaceReconnectedTransport() throws Exception {
        NostrTURNChannel channel = connect();
        ControlledTransport old = platform.transports.get(0);
        CapturedCall<?> timeout = platform.scheduler.next(5000L);
        timeout.fire();
        assertEquals(1, old.closeCount);
        assertFalse(channel.isResurrecting());
        assertPoolEmpty();

        platform.mode = Mode.OPEN;
        allowRetry(channel);
        platform.scheduler.fireNext(100L);
        TURNTransport replacement = activeTransport(channel);
        ControlledTransport current = platform.transports.get(1);
        assertTrue(replacement.isConnected());
        old.fireLateOpen();
        old.fail();
        old.fireLateClose();
        old.fireLateError();
        timeout.fire();
        assertSame(replacement, activeTransport(channel));
        assertTrue(replacement.isConnected());
        assertEquals(0, current.closeCount);
        assertEquals(1, old.closeCount);
        assertEquals(1, map("transports").size());
        assertEquals(0, map("connectingTransports").size());
    }

    @Test
    public void failedUrlBackoffPreventsOtherChannelsFromAllocatingMoreTransports() throws Exception {
        platform.mode = Mode.FAIL;
        NostrTURNChannel first = connect();
        NostrTURNChannel second = connect();
        assertEquals(1, platform.transports.size());
        assertEquals(1, platform.transports.get(0).closeCount);
        assertFalse(first.isResurrecting());
        assertFalse(second.isResurrecting());
        assertNull(activeTransport(second));
        assertPoolEmpty();
    }

    @Test
    public void zeroBackoffStillAllowsImmediateRetryWithoutRetainingFailedTransport() throws Exception {
        platform.mode = Mode.FAIL;
        pool.setFailedResurrectionBackoffMs(0L);
        connect();
        connect();
        assertEquals(2, platform.transports.size());
        for (ControlledTransport transport : platform.transports) assertEquals(1, transport.closeCount);
        assertPoolEmpty();
    }

    @Test
    public void cancelledConnectTimerCannotCloseSuccessfulTransport() throws Exception {
        platform.mode = Mode.OPEN;
        NostrTURNChannel channel = connect();
        TURNTransport current = activeTransport(channel);
        CapturedCall<?> timeout = platform.scheduler.next(5000L);
        assertTrue("successful connect cancels its timer", timeout.task.isFailed());
        timeout.fire();
        assertSame(current, activeTransport(channel));
        assertTrue(current.isConnected());
        assertEquals(0, platform.transports.get(0).closeCount);
        pool.close();
        pool.close();
        platform.scheduler.fireNext(100L);
        assertEquals(1, platform.transports.get(0).closeCount);
        assertEquals(1, platform.scheduler.closeCount);
        assertEquals(1, platform.transports.size());
    }

    @Test
    public void disconnectedTransportReplacementClosesOldOwner() throws Exception {
        platform.mode = Mode.OPEN;
        NostrTURNChannel first = connect();
        ControlledTransport old = platform.transports.get(0);
        old.connected = false;
        platform.mode = Mode.HANG;
        NostrTURNChannel second = connect();
        assertEquals(2, platform.transports.size());
        assertEquals(1, old.closeCount);
        assertNull(activeTransport(first));
        TURNTransport pending = activeTransport(second);
        old.fireLateClose();
        old.fireLateOpen();
        assertSame(pending, activeTransport(second));
        assertEquals(1, map("transports").size());
        assertEquals(1, map("connectingTransports").size());
    }

    @Test
    public void disconnectedUsedTransportCleanupClosesBeforeReconnecting() throws Exception {
        platform.mode = Mode.OPEN;
        NostrTURNChannel channel = connect();
        ControlledTransport old = platform.transports.get(0);
        old.connected = false;
        platform.mode = Mode.HANG;
        platform.scheduler.fireNext(100L);
        assertEquals(1, old.closeCount);
        assertEquals(2, platform.transports.size());
        assertSame(platform.transports.get(1), activeTransport(channel).getTransport());
    }

    @Test
    public void unusedDisconnectedTransportCleanupStillClosesUnderlyingTransport() throws Exception {
        platform.mode = Mode.OPEN;
        NostrTURNChannel channel = connect();
        ControlledTransport old = platform.transports.get(0);
        channel.close("unused");
        old.connected = false;
        platform.scheduler.fireNext(100L);
        assertEquals(1, old.closeCount);
        assertEquals(1, platform.transports.size());
        assertPoolEmpty();
    }

    @Test
    public void unusedPendingConnectCleanupIgnoresLateOpenAndTimeout() throws Exception {
        NostrTURNChannel channel = connect();
        ControlledTransport old = platform.transports.get(0);
        CapturedCall<?> timeout = platform.scheduler.next(5000L);
        channel.close("closed-before-open");
        platform.scheduler.fireNext(100L);
        old.fireLateOpen();
        timeout.fire();
        assertEquals(1, old.closeCount);
        assertNull(activeTransport(channel));
        assertFalse(channel.isResurrecting());
        assertPoolEmpty();
    }

    @Test
    public void oldPendingFailureCannotDetachManuallyReplacedChannel() throws Exception {
        NostrTURNChannel channel = connect();
        ControlledTransport old = platform.transports.get(0);
        ControlledTransport rawReplacement = new ControlledTransport(Mode.OPEN);
        rawReplacement.connected = true;
        TURNTransport replacement = new TURNTransport(rawReplacement);
        try {
            channel.setTransport(replacement);
            old.fail();
            old.fireLateOpen();
            old.fireLateClose();
            old.fireLateError();
            assertSame(replacement, activeTransport(channel));
            assertTrue(replacement.getUsers().contains(channel));
            assertEquals(0, rawReplacement.closeCount);
            assertEquals(1, old.closeCount);
            assertFalse(channel.isResurrecting());
            assertPoolEmpty();
        } finally {
            replacement.close("test-cleanup");
        }
    }

    @Test
    public void synchronousConnectThrowClosesAllocatedTransportAndReleasesResurrection() throws Exception {
        platform.mode = Mode.THROW;
        NostrTURNChannel channel = connect();
        assertEquals(1, platform.transports.get(0).closeCount);
        assertNull(activeTransport(channel));
        assertFalse(channel.isResurrecting());
        assertPoolEmpty();
    }

    @Test
    public void transportCreationThrowRemovesPublishedPromiseAndReleasesResurrection() throws Exception {
        platform.failCreation = true;
        NostrTURNChannel channel = connect();
        assertEquals(0, platform.transports.size());
        assertNull(activeTransport(channel));
        assertFalse(channel.isResurrecting());
        assertPoolEmpty();
    }

    @Test
    public void listenerRegistrationThrowStillClosesAllocatedTransport() throws Exception {
        platform.mode = Mode.LISTENER_THROW;
        NostrTURNChannel channel = connect();
        assertEquals(1, platform.transports.get(0).closeCount);
        assertTrue(platform.transports.get(0).listeners.isEmpty());
        assertNull(activeTransport(channel));
        assertFalse(channel.isResurrecting());
        assertPoolEmpty();
    }

    @Test
    public void poolCloseOfPendingConnectClosesOnceAndRejectsLateCallbacks() throws Exception {
        NostrTURNChannel channel = connect();
        ControlledTransport old = platform.transports.get(0);
        CapturedCall<?> timeout = platform.scheduler.next(5000L);
        pool.close();
        old.fireLateOpen();
        old.fail();
        timeout.fire();
        pool.close();
        assertEquals(1, old.closeCount);
        assertNull(activeTransport(channel));
        assertFalse(channel.isResurrecting());
        assertPoolEmpty();
    }

    @Test
    public void closeBeforePendingDelegateAllocationRetiresTheLateAttempt() throws Exception {
        assertRetiredConnectCleanup(Mode.HANG, false);
    }

    @Test
    public void closeBeforeSynchronousOpenRetiresTheLateAttempt() throws Exception {
        assertRetiredConnectCleanup(Mode.OPEN, false);
    }

    @Test
    public void closeBeforeDelegateAllocationCleansUpEvenWhenConnectThrows() throws Exception {
        assertRetiredConnectCleanup(Mode.HANG, true);
    }

    private void assertRetiredConnectCleanup(Mode mode, boolean throwAfterAllocation) throws Exception {
        ControlledTransport delegate = new ControlledTransport(mode);
        delegate.connectEntered = new CountDownLatch(1);
        delegate.allowConnectAllocation = new CountDownLatch(1);
        delegate.closeSettlesConnect = true;
        delegate.throwAfterAllocation = throwAfterAllocation;
        TURNTransport wrapper = new TURNTransport(delegate);
        AtomicInteger failures = new AtomicInteger();
        wrapper.setPendingConnectFailure(error -> failures.incrementAndGet());
        AtomicReference<AsyncTask<Void>> result = new AtomicReference<AsyncTask<Void>>();
        AtomicReference<Throwable> thrown = new AtomicReference<Throwable>();
        Thread connector = new Thread(
            () -> {
                try {
                    result.set(wrapper.connect(TURN_URL));
                } catch (Throwable error) {
                    thrown.set(error);
                }
            },
            "turn-retired-connect-test"
        );
        connector.setDaemon(true);
        connector.start();
        try {
            assertTrue("delegate must enter before allocation", delegate.connectEntered.await(2, TimeUnit.SECONDS));
            wrapper.close("retire-before-allocation");
            assertEquals(1, delegate.closeCount);
            assertEquals(1, failures.get());
            delegate.allowConnectAllocation.countDown();
            connector.join(2000L);
            assertFalse("connect must finish without a callback monitor deadlock", connector.isAlive());
            assertTrue(wrapper.isClosed());
            assertFalse("late physical attempt must be retired", delegate.isConnected());
            assertEquals("one initial close and one cleanup after late allocation", 2, delegate.closeCount);
            assertTrue(delegate.listeners.isEmpty());
            if (throwAfterAllocation) {
                assertTrue(thrown.get() instanceof IllegalStateException);
                assertEquals("connect threw after allocation", thrown.get().getMessage());
                assertNull(result.get());
            } else {
                assertNull(thrown.get());
                assertTrue("retired connect must not report success", result.get().isFailed());
            }
            if (mode == Mode.HANG) assertTrue("pending delegate promise must settle", delegate.currentConnectTask.isFailed());
            wrapper.close("idempotent-retirement");
            assertEquals(2, delegate.closeCount);
            assertEquals(1, failures.get());
        } finally {
            delegate.allowConnectAllocation.countDown();
            connector.join(2000L);
            wrapper.close("test-cleanup");
        }
    }

    @Test
    public void transportErrorListenerCanWaitForAnotherThreadClosingItsChannel() throws Exception {
        platform.mode = Mode.OPEN;
        NostrTURNChannel channel = connect();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicInteger errors = new AtomicInteger();
        channel.addListener(
            new NostrTURNChannelListener() {
                @Override
                public void onTurnChannelReady(NostrTURNChannel user) {}

                @Override
                public void onTurnChannelMessage(NostrTURNChannel user, ByteBuffer payload) {}

                @Override
                public void onTurnChannelClosed(NostrTURNChannel user, String reason) {}

                @Override
                public void onTurnChannelError(NostrTURNChannel user, Throwable error) {
                    errors.incrementAndGet();
                    if (Thread.holdsLock(user)) failure.set(new AssertionError("listener holds channel monitor"));
                    Thread closer = new Thread(() -> user.close("listener-close"), "turn-listener-close");
                    closer.setDaemon(true);
                    closer.start();
                    try {
                        closer.join(1000L);
                        if (closer.isAlive()) failure.set(new AssertionError("listener blocked concurrent channel close"));
                    } catch (InterruptedException interrupted) {
                        failure.set(interrupted);
                    }
                }
            }
        );
        platform.transports.get(0).fireLateError();
        assertEquals(1, errors.get());
        assertNull(failure.get());
        assertTrue(channel.isClosed());
    }

    private NostrTURNChannel connect() {
        return pool.connect(local, remote, TURN_URL, room, "game", true, null);
    }

    private void assertPoolEmpty() throws Exception {
        assertTrue(map("transports").isEmpty());
        assertTrue(map("connectingTransports").isEmpty());
    }

    private void allowRetry(NostrTURNChannel channel) throws Exception {
        // Simulate expiry of both delays without sleeping or changing production clocks.
        channel.clearResurrectionBackoff();
        map("failedTransportRetryAtMs").clear();
    }

    private Map<?, ?> map(String name) throws Exception {
        return (Map<?, ?>) field(NostrTURNPool.class, name).get(pool);
    }

    private static TURNTransport activeTransport(NostrTURNChannel channel) throws Exception {
        return (TURNTransport) field(NostrTURNChannel.class, "transport").get(channel);
    }

    private static Field field(Class<?> type, String name) throws Exception {
        Field result = type.getDeclaredField(name);
        result.setAccessible(true);
        return result;
    }

    private enum Mode {
        HANG,
        OPEN,
        FAIL,
        THROW,
        LISTENER_THROW,
    }

    private static final class LifecyclePlatform extends JVMAsyncPlatform {

        private final CapturedExecutor scheduler = new CapturedExecutor();
        private final List<ControlledTransport> transports = new ArrayList<ControlledTransport>();
        private Mode mode = Mode.HANG;
        private Runnable onNewTransport;
        private boolean failCreation;

        @Override
        public AsyncExecutor newAsyncExecutor(Object key) {
            return key == NostrTURNPool.class ? scheduler : super.newAsyncExecutor(key);
        }

        @Override
        public WebsocketTransport newTransport() {
            if (failCreation) throw new IllegalStateException("transport creation failed");
            ControlledTransport transport = new ControlledTransport(mode);
            transports.add(transport);
            Runnable callback = onNewTransport;
            onNewTransport = null;
            if (callback != null) callback.run();
            return transport;
        }
    }

    private static final class CapturedCall<T> {

        private final Callable<T> callback;
        private final long delayMs;
        private final AsyncTask<T> task;
        private Consumer<T> resolve;
        private Consumer<Throwable> reject;
        private boolean fired;

        private CapturedCall(Callable<T> callback, long delay, TimeUnit unit) {
            this.callback = callback;
            this.delayMs = unit.toMillis(delay);
            this.task =
                AsyncTask.create((accept, fail) -> {
                    resolve = accept;
                    reject = fail;
                });
        }

        private void fire() {
            fired = true;
            try {
                resolve.accept(callback.call());
            } catch (Exception error) {
                reject.accept(error);
                throw new AssertionError(error);
            }
        }
    }

    private static final class CapturedExecutor implements AsyncExecutor {

        private final List<CapturedCall<?>> calls = new ArrayList<CapturedCall<?>>();
        private int closeCount;

        @Override
        public <T> AsyncTask<T> runLater(Callable<T> callback, long delay, TimeUnit unit) {
            CapturedCall<T> call = new CapturedCall<T>(callback, delay, unit);
            calls.add(call);
            return call.task;
        }

        @Override
        public <T> AsyncTask<T> run(Callable<T> callback) {
            try {
                return AsyncTask.completed(callback.call());
            } catch (Exception error) {
                return AsyncTask.failed(error);
            }
        }

        private CapturedCall<?> next(long delayMs) {
            for (CapturedCall<?> call : calls) {
                if (!call.fired && call.delayMs == delayMs) return call;
            }
            throw new AssertionError("No captured callback with delay " + delayMs);
        }

        private void fireNext(long delayMs) {
            next(delayMs).fire();
        }

        @Override
        public void close() {
            closeCount++;
        }
    }

    private static final class ControlledTransport implements WebsocketTransport {

        private final Mode mode;
        private final List<WebsocketTransportListener> listeners = new ArrayList<WebsocketTransportListener>();
        // Retained only by the test to fire callbacks already dequeued before removeListener.
        private final List<WebsocketTransportListener> capturedListeners = new ArrayList<WebsocketTransportListener>();
        private Consumer<Throwable> rejectConnect;
        private Consumer<Void> resolveConnect;
        private boolean connected;
        private int closeCount;
        private CountDownLatch connectEntered;
        private CountDownLatch allowConnectAllocation;
        private boolean closeSettlesConnect;
        private boolean throwAfterAllocation;
        private AsyncTask<Void> currentConnectTask;

        private ControlledTransport(Mode mode) {
            this.mode = mode;
        }

        @Override
        public AsyncTask<Void> connect(String url) {
            if (connectEntered != null) {
                connectEntered.countDown();
                try {
                    if (!allowConnectAllocation.await(2, TimeUnit.SECONDS)) throw new AssertionError(
                        "delegate allocation was not released"
                    );
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("connect interrupted", error);
                }
            }
            if (mode == Mode.THROW) throw new IllegalStateException("synchronous connect failure");
            if (mode == Mode.FAIL) return AsyncTask.failed(new IllegalStateException("connect rejected"));
            AsyncTask<Void> task = AsyncTask.create((resolve, reject) -> {
                resolveConnect = resolve;
                rejectConnect = reject;
            });
            currentConnectTask = task;
            if (mode == Mode.OPEN) fireLateOpen();
            if (throwAfterAllocation) throw new IllegalStateException("connect threw after allocation");
            return task;
        }

        private void fail() {
            if (rejectConnect != null) rejectConnect.accept(new IllegalStateException("late connect failure"));
        }

        private void fireLateOpen() {
            connected = true;
            for (WebsocketTransportListener listener : capturedListeners) listener.onConnectionOpen();
            if (resolveConnect != null) resolveConnect.accept(null);
        }

        private void fireLateClose() {
            for (WebsocketTransportListener listener : capturedListeners) listener.onConnectionClosedByServer("late close");
        }

        private void fireLateError() {
            for (WebsocketTransportListener listener : capturedListeners) {
                listener.onConnectionError(new IllegalStateException("late error"));
                listener.onConnectionBinaryMessage(ByteBuffer.wrap(new byte[] { 1, 2, 3 }));
            }
        }

        @Override
        public AsyncTask<Void> close(String reason) {
            closeCount++;
            connected = false;
            if (closeSettlesConnect && rejectConnect != null) rejectConnect.accept(
                new IllegalStateException("physical attempt retired")
            );
            // Deliberately reentrant even after removal, like a callback already in flight.
            for (WebsocketTransportListener listener : capturedListeners) listener.onConnectionClosedByClient(reason);
            return AsyncTask.completed(null);
        }

        @Override
        public boolean isConnected() {
            return connected;
        }

        @Override
        public AsyncTask<Void> send(String message) {
            return AsyncTask.completed(null);
        }

        @Override
        public AsyncTask<Void> sendBinary(ByteBuffer frame) {
            return AsyncTask.completed(null);
        }

        @Override
        public void setMaxMessageSize(int maxMessageSize) {}

        @Override
        public int getMaxMessageSize() {
            return 1024 * 1024;
        }

        @Override
        public void addListener(WebsocketTransportListener listener) {
            listeners.add(listener);
            capturedListeners.add(listener);
            if (mode == Mode.LISTENER_THROW) throw new IllegalStateException("listener registration failed");
        }

        @Override
        public void removeListener(WebsocketTransportListener listener) {
            listeners.remove(listener);
        }
    }
}
