/**
 * BSD 3-Clause License
 * Copyright (c) 2025, Riccardo Balbo
 */
package org.ngengine.nostr4j.rtc;

import java.nio.ByteBuffer;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.logging.Logger;
import org.ngengine.nostr4j.event.SignedNostrEvent;
import org.ngengine.nostr4j.keypair.NostrKeyPair;
import org.ngengine.nostr4j.rtc.listeners.NostrTURNChannelListener;
import org.ngengine.nostr4j.rtc.signal.NostrRTCLocalPeer;
import org.ngengine.nostr4j.rtc.signal.NostrRTCPeer;
import org.ngengine.nostr4j.rtc.turn.NostrTURNCodec;
import org.ngengine.nostr4j.rtc.turn.NostrTURNEvent;
import org.ngengine.platform.AsyncExecutor;
import org.ngengine.platform.AsyncTask;
import org.ngengine.platform.NGEPlatform;
import org.ngengine.platform.NGEUtils;
import org.ngengine.platform.transport.WebsocketTransport;
import org.ngengine.platform.transport.WebsocketTransportListener;

/**
 * Manages TURN server connections and virtual socket sessions.
 *
 * Responsibilities:
 * - Pool websocket connections to TURN servers
 * - Manage session lifecycle (challenge -> connect -> ack)
 * - Route binary data between virtual sockets
 * - Handle protocol state transitions
 */
public final class NostrTURNPool implements AutoCloseable {

    static final Logger logger = Logger.getLogger(NostrTURNPool.class.getName());
    private static final long loopInterval = 100;
    private static final long WEBSOCKET_CONNECT_TIMEOUT_MS = 5000L;
    private static final long DEFAULT_FAILED_RESURRECTION_BACKOFF_MS = 1000L;
    private static final String CLEANUP_CLOSE_REASON = "TURN pool cleanup: transport not connected or unused";

    private final AsyncExecutor executor = NGEUtils.getPlatform().newAsyncExecutor(NostrTURNPool.class);

    private volatile boolean closed;

    private final List<NostrTURNChannel> channels = new CopyOnWriteArrayList<>();
    private final Map<String, AsyncTask<TURNTransport>> transports = new ConcurrentHashMap<>();
    private final Map<AsyncTask<TURNTransport>, TURNTransport> connectingTransports =
        new ConcurrentHashMap<AsyncTask<TURNTransport>, TURNTransport>();
    // Channels sharing a failed URL share its retry delay as well as its connection.
    private final Map<String, Long> failedTransportRetryAtMs = new ConcurrentHashMap<String, Long>();
    private final int maxAcceptedDiff;
    private volatile long failedResurrectionBackoffMs = DEFAULT_FAILED_RESURRECTION_BACKOFF_MS;

    public NostrTURNPool() {
        this(32);
    }

    public NostrTURNPool(int maxDiff) {
        this.maxAcceptedDiff = maxDiff;
        loop();
    }

    public void setFailedResurrectionBackoff(long backoff, TimeUnit unit) {
        if (backoff < 0) {
            throw new IllegalArgumentException("Failed resurrection backoff cannot be negative");
        }
        this.failedResurrectionBackoffMs = unit.toMillis(backoff);
    }

    public void setFailedResurrectionBackoffMs(long backoffMs) {
        setFailedResurrectionBackoff(backoffMs, TimeUnit.MILLISECONDS);
    }

    /**
     * Connect local peer to remote peer via the
     * specified turn settings.
     *
     * The returned channel is a logical channel that never
     * goes offline: if the underlying transport connection dies it will be
     * transparently resurrected on a new transport connection.
     */
    NostrTURNChannel connect(
        NostrRTCLocalPeer localPeer,
        NostrRTCPeer remotePeer,
        String turnServerUrl,
        NostrKeyPair roomKeyPair,
        String channelLabel,
        boolean reliable,
        NostrTURNChannelListener listener
    ) {
        if (closed) throw new IllegalStateException("TURN pool is closed");
        NostrTURNChannel channel = new NostrTURNChannel(
            localPeer,
            remotePeer,
            turnServerUrl,
            roomKeyPair,
            channelLabel,
            reliable,
            maxAcceptedDiff
        );
        if (listener != null) channel.addListener(listener);
        channel.addListener(
            new NostrTURNChannelListener() {
                @Override
                public void onTurnChannelReady(NostrTURNChannel channel) {}

                @Override
                public void onTurnChannelMessage(NostrTURNChannel channel, ByteBuffer payload) {}

                @Override
                public void onTurnChannelError(NostrTURNChannel channel, Throwable e) {}

                @Override
                public void onTurnChannelClosed(NostrTURNChannel channel, String reason) {
                    channels.remove(channel);
                }
            }
        );

        this.channels.add(channel);
        if (closed) {
            channel.close("TURN pool is closed");
        } else {
            resurrectChannel(channel);
        }
        return channel;
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        for (NostrTURNChannel channel : channels) {
            channel.close("closed by pool");
        }
        channels.clear();
        for (TURNTransport connecting : connectingTransports.values()) {
            connecting.close("TURN pool is closing");
        }
        connectingTransports.clear();
        for (AsyncTask<TURNTransport> transport : transports.values()) {
            if (!transport.isDone()) {
                transport.cancel();
            }
            transport.then(tr -> {
                if (tr != null) {
                    tr.close("TURN pool is closing");
                }
                return null;
            });
        }
        transports.clear();
        failedTransportRetryAtMs.clear();
        executor.close();
    }

    /**
     * Fetches or creates the underlying websocket transport for the
     * given channel. If an appropriate transport already exists it will
     * be used in multiplexing mode.
     * @param channel
     * @return
     */
    private AsyncTask<TURNTransport> useWebsocketTransport(NostrTURNChannel channel) {
        String turnServerUrl = channel.getServerUrl();
        AsyncTask<TURNTransport> wsP;
        while (true) {
            if (closed || channel.isClosed()) {
                return AsyncTask.failed(new IllegalStateException("TURN pool or channel is closed"));
            }
            AsyncTask<TURNTransport> current = transports.get(turnServerUrl);
            TURNTransport existing = null;
            boolean shouldCreate = current == null || current.isFailed();
            if (!shouldCreate && current.isDone()) {
                existing = NGEUtils.awaitNoThrow(current);
                shouldCreate = existing == null || !existing.isConnected();
            }
            if (!shouldCreate) {
                wsP = current;
                break;
            }
            if (!canCreateTransport(turnServerUrl, System.currentTimeMillis())) {
                return AsyncTask.failed(new IllegalStateException("TURN websocket retry is backed off for: " + turnServerUrl));
            }

            // Publish the promise before starting any transport callbacks. No socket is
            // allocated for a contender that loses the conditional map update.
            AtomicReference<Consumer<AsyncTask<TURNTransport>>> start =
                new AtomicReference<Consumer<AsyncTask<TURNTransport>>>();
            AsyncTask<TURNTransport> candidate = createTransportTask(turnServerUrl, channel, start::set);
            boolean installed = current == null
                ? transports.putIfAbsent(turnServerUrl, candidate) == null
                : transports.replace(turnServerUrl, current, candidate);
            if (!installed) {
                continue;
            }
            candidate.catchException(error -> {
                TURNTransport connecting = connectingTransports.remove(candidate);
                if (connecting != null) connecting.close("TURN websocket connect task failed or cancelled");
                transports.remove(turnServerUrl, candidate);
            });
            if (existing != null) {
                existing.close("TURN websocket transport replaced");
            }
            start.get().accept(candidate);
            wsP = candidate;
            break;
        }

        return wsP
            .then(ws -> {
                synchronized (channel) {
                    if (closed || channel.isClosed() || !turnServerUrl.equals(channel.getServerUrl())) {
                        throw new IllegalStateException("TURN transport request is no longer current");
                    }
                    if (!ws.isConnected()) {
                        channel.detachTransport(ws);
                        throw new IllegalStateException("Websocket transport is not connected for URL: " + turnServerUrl);
                    }
                    channel.setTransport(ws);
                    if (!channel.isUsingTransport(ws)) {
                        throw new IllegalStateException("TURN transport closed during installation");
                    }
                }
                channel.openConnectionMaybe(ws);
                return ws;
            })
            .catchException(e -> {
                logger.warning(
                    "Failed to establish websocket transport for TURN server: " + turnServerUrl + " - " + e.getMessage()
                );
                throw new RuntimeException(e);
            });
    }

    private boolean canCreateTransport(String url, long nowMs) {
        Long retryAt = failedTransportRetryAtMs.get(url);
        if (retryAt == null) return true;
        if (nowMs < retryAt.longValue()) return false;
        failedTransportRetryAtMs.remove(url, retryAt);
        return true;
    }

    private AsyncTask<TURNTransport> createTransportTask(
        String url,
        NostrTURNChannel channel,
        Consumer<Consumer<AsyncTask<TURNTransport>>> deferStart
    ) {
        return NGEPlatform
            .get()
            .wrapPromise((res2, rej2) -> deferStart.accept(ownerTask -> {
                if (closed || channel.isClosed()) {
                    rej2.accept(new IllegalStateException("TURN pool or channel is closed"));
                    return;
                }
                if (!canCreateTransport(url, System.currentTimeMillis())) {
                    rej2.accept(new IllegalStateException("TURN websocket retry is backed off for: " + url));
                    return;
                }
                final WebsocketTransport transport;
                try {
                    transport = NGEPlatform.get().newTransport();
                } catch (Throwable error) {
                    if (!closed && transports.get(url) == ownerTask) {
                        failedTransportRetryAtMs.put(url, System.currentTimeMillis() + failedResurrectionBackoffMs);
                    }
                    rej2.accept(error);
                    return;
                }
                TURNTransport wss = new TURNTransport(transport);
                connectingTransports.put(ownerTask, wss);
                AtomicBoolean settled = new AtomicBoolean(false);

                Consumer<Throwable> failOnce = cause -> {
                    if (!settled.compareAndSet(false, true)) {
                        return;
                    }
                    if (!closed && transports.get(url) == ownerTask) {
                        failedTransportRetryAtMs.put(url, System.currentTimeMillis() + failedResurrectionBackoffMs);
                    }
                    connectingTransports.remove(ownerTask, wss);
                    wss.clearPendingConnectFailure();
                    wss.clearConnectTimeoutTask();
                    wss.close("turn-websocket-connect-failed");
                    rej2.accept(cause == null ? new RuntimeException("TURN websocket connect failed") : cause);
                };
                Runnable succeedOnce = () -> {
                    if (!settled.compareAndSet(false, true)) {
                        return;
                    }
                    if (transports.get(url) == ownerTask) failedTransportRetryAtMs.remove(url);
                    connectingTransports.remove(ownerTask, wss);
                    wss.clearPendingConnectFailure();
                    wss.clearConnectTimeoutTask();
                    res2.accept(wss);
                };

                wss.setPendingConnectFailure(failOnce);
                try {
                    wss.setConnectionListener(
                        new WebsocketTransportListener() {
                            @Override
                            public void onConnectionClosedByServer(String reason) {
                                failOnce.accept(new RuntimeException("Websocket closed by server: " + reason));
                                wss.close("TURN websocket closed by server");
                            }

                            @Override
                            public void onConnectionOpen() {
                                if (closed || wss.isClosed()) {
                                    failOnce.accept(new IllegalStateException("TURN websocket opened after close"));
                                    return;
                                }
                                succeedOnce.run();
                            }

                            @Override
                            public void onConnectionMessage(String msg) {
                                for (NostrTURNChannel user : wss.getUsers()) {
                                    if (user.isUsingTransport(wss)) user.onConnectionMessage(msg);
                                }
                            }

                            @Override
                            public void onConnectionBinaryMessage(ByteBuffer msg) {
                                if (!wss.isClosed()) dispatchBinaryFrameToUsers(wss, msg);
                            }

                            @Override
                            public void onConnectionClosedByClient(String reason) {
                                failOnce.accept(new RuntimeException("Websocket closed by client: " + reason));
                                wss.close("TURN websocket closed by client");
                            }

                            @Override
                            public void onConnectionError(Throwable e) {
                                if (wss.isClosed()) return;
                                for (NostrTURNChannel user : wss.getUsers()) {
                                    user.onError(e, wss);
                                }
                                if (!settled.get()) {
                                    failOnce.accept(e);
                                }
                            }
                        }
                    );

                    synchronized (channel) {
                        if (!channel.isClosed() && url.equals(channel.getServerUrl())) channel.setTransport(wss);
                    }
                    if (closed || wss.isClosed() || !wss.isUsed()) {
                        failOnce.accept(new IllegalStateException("TURN transport has no active owner"));
                        return;
                    }
                    AsyncTask<Void> timeoutTask = executor.runLater(
                        () -> {
                            failOnce.accept(
                                new RuntimeException(
                                    "Websocket connect timed out after " + WEBSOCKET_CONNECT_TIMEOUT_MS + " ms for: " + url
                                )
                            );
                            return null;
                        },
                        WEBSOCKET_CONNECT_TIMEOUT_MS,
                        TimeUnit.MILLISECONDS
                    );
                    wss.setConnectTimeoutTask(timeoutTask);

                    wss.connect(url)
                        .catchException(ex -> {
                            failOnce.accept(ex);
                        });
                } catch (Throwable error) {
                    failOnce.accept(error);
                }
            }));
    }

    private static void cacheChallengeFrameIfPresent(TURNTransport ws, ByteBuffer frame) {
        try {
            SignedNostrEvent header = NostrTURNCodec.decodeHeader(frame.asReadOnlyBuffer());
            if (header.getKind() != NostrTURNEvent.KIND) {
                return;
            }
            if (!"challenge".equals(header.getFirstTagFirstValue("t"))) {
                return;
            }
            byte[] copy = new byte[frame.remaining()];
            frame.asReadOnlyBuffer().get(copy);
            ws.setLastChallengeFrame(copy);
        } catch (Exception ignored) {
            // Not a parsable control frame.
        }
    }

    void dispatchBinaryFrameToUsers(TURNTransport transport, ByteBuffer msg) {
        if (transport == null || transport.isClosed() || msg == null) {
            return;
        }
        ByteBuffer source = msg.asReadOnlyBuffer();
        source.rewind();
        cacheChallengeFrameIfPresent(transport, source);

        final String type;
        final long envelopeVsocketId;
        try {
            SignedNostrEvent header = NostrTURNCodec.decodeHeader(source.asReadOnlyBuffer());
            type = header.getFirstTagFirstValue("t");
            envelopeVsocketId = NostrTURNCodec.extractVsocketId(source.asReadOnlyBuffer());
        } catch (Throwable decodeError) {
            for (NostrTURNChannel user : transport.getUsers()) {
                user.onError(decodeError, transport);
            }
            return;
        }

        for (NostrTURNChannel user : transport.getUsers()) {
            if (!user.isUsingTransport(transport) || !shouldDispatchToUser(type, envelopeVsocketId, user)) {
                continue;
            }
            ByteBuffer frame = source.asReadOnlyBuffer();
            frame.rewind();
            try {
                user.onBinaryMessage(frame, transport);
            } catch (Throwable ex) {
                user.onError(ex, transport);
            }
        }
    }

    private static boolean shouldDispatchToUser(String type, long envelopeVsocketId, NostrTURNChannel user) {
        if ("challenge".equals(type)) {
            return true;
        }
        if ("data".equals(type) || "delivery_ack".equals(type) || "ack".equals(type) || "disconnect".equals(type)) {
            return envelopeVsocketId != 0L && user.getRoutingVsocketId() == envelopeVsocketId;
        }
        // Unknown types keep previous permissive behavior, while still routing targeted envelopes narrowly.
        if (envelopeVsocketId == 0L) {
            return true;
        }
        return user.getRoutingVsocketId() == envelopeVsocketId;
    }

    /**
     * Resurrect a channel: gives it a fresh transport backend if its current one
     * is bad.
     * @param channel
     */
    private void resurrectChannel(NostrTURNChannel channel) {
        if (closed || !channel.beginResurrection(System.currentTimeMillis())) {
            return;
        }
        useWebsocketTransport(channel)
            .then(transport -> {
                channel.clearResurrectionBackoff();
                channel.setResurrecting(false);
                return null;
            })
            .catchException(e -> {
                channel.backoffResurrection(System.currentTimeMillis(), failedResurrectionBackoffMs);
                logger.warning("Failed to resurrect TURN channel: " + e.getMessage());
                channel.setResurrecting(false);
            });
    }

    private void loop() {
        if (closed) return;
        executor.runLater(
            () -> {
                if (closed) {
                    return null;
                }
                try {
                    long nowMs = System.currentTimeMillis();
                    for (Entry<String, Long> retry : failedTransportRetryAtMs.entrySet()) {
                        if (nowMs >= retry.getValue().longValue()) {
                            failedTransportRetryAtMs.remove(retry.getKey(), retry.getValue());
                        }
                    }
                    // cleanup idle connections
                    for (Entry<String, AsyncTask<TURNTransport>> entry : transports.entrySet()) {
                        String url = entry.getKey();
                        AsyncTask<TURNTransport> transportTask = entry.getValue();
                        if (!transportTask.isDone()) {
                            TURNTransport connecting = connectingTransports.get(transportTask);
                            if (connecting != null && !connecting.isUsed() && transports.remove(url, transportTask)) {
                                connecting.close(CLEANUP_CLOSE_REASON);
                                transportTask.cancel();
                                connectingTransports.remove(transportTask, connecting);
                            }
                            continue;
                        }
                        if (transportTask.isFailed()) {
                            transports.remove(url, transportTask);
                            continue;
                        }
                        TURNTransport transport = NGEUtils.awaitNoThrow(transportTask);
                        if (transport == null) {
                            transports.remove(url, transportTask);
                            continue;
                        }
                        boolean unused = !transport.isUsed();
                        boolean disconnected = !transport.isConnected();
                        if (unused || disconnected) {
                            if (transports.remove(url, transportTask)) {
                                transport.close(CLEANUP_CLOSE_REASON);
                            }
                        }
                    }

                    // resurrect channels
                    for (NostrTURNChannel ch : channels) {
                        resurrectChannel(ch);
                    }
                } catch (Exception e) {
                    logger.warning("Error during TURN pool cleanup: " + e.getMessage());
                }
                loop();
                return null;
            },
            loopInterval,
            TimeUnit.MILLISECONDS
        );
    }

    static final class TURNTransport {

        private final WebsocketTransport transport;
        private final CopyOnWriteArrayList<NostrTURNChannel> users = new CopyOnWriteArrayList<>();
        private volatile byte[] lastChallengeFrame = null;
        private volatile AsyncTask<Void> connectTimeoutTask = null;
        private volatile Consumer<Throwable> pendingConnectFailure = null;
        private volatile WebsocketTransportListener connectionListener = null;
        private final AtomicBoolean closed = new AtomicBoolean(false);

        public TURNTransport(WebsocketTransport transport) {
            this.transport = transport;
        }

        public WebsocketTransport getTransport() {
            return transport;
        }

        Collection<NostrTURNChannel> getUsers() {
            return users;
        }

        public boolean isUsed() {
            return !users.isEmpty();
        }

        public boolean isConnected() {
            return !closed.get() && transport.isConnected();
        }

        boolean isClosed() {
            return closed.get();
        }

        AsyncTask<Void> connect(String url) {
            if (closed.get()) {
                return AsyncTask.failed(new IllegalStateException("TURN websocket transport is closed"));
            }
            AsyncTask<Void> attempt;
            try {
                attempt = transport.connect(url);
            } finally {
                // close() can retire this wrapper just before the reusable delegate
                // installs its attempt. Clean up after that launch too, without
                // holding a wrapper/channel monitor across external callbacks.
                if (closed.get()) closeDelegate(CLEANUP_CLOSE_REASON);
            }
            return closed.get()
                ? AsyncTask.failed(new IllegalStateException("TURN websocket transport is closed"))
                : attempt;
        }

        public void close(String reason) {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            Consumer<Throwable> failure = this.pendingConnectFailure;
            clearPendingConnectFailure();
            clearConnectTimeoutTask();
            WebsocketTransportListener listener = this.connectionListener;
            this.connectionListener = null;
            try {
                if (listener != null) transport.removeListener(listener);
            } catch (Throwable error) {
                logger.warning("Failed to detach TURN websocket listener: " + error.getMessage());
            }
            for (NostrTURNChannel user : users) {
                user.detachTransport(this);
            }
            users.clear();
            try {
                closeDelegate(reason);
            } finally {
                if (failure != null) {
                    failure.accept(new RuntimeException("Websocket transport closed while connecting: " + reason));
                }
            }
        }

        private void closeDelegate(String reason) {
            try {
                transport.close(reason).catchException(error ->
                    logger.warning("Failed to close TURN websocket transport: " + error.getMessage())
                );
            } catch (Throwable error) {
                logger.warning("Failed to close TURN websocket transport: " + error.getMessage());
            }
        }

        public void addUser(NostrTURNChannel channel) {
            if (!closed.get()) {
                users.addIfAbsent(channel);
            }
        }

        public void removeUser(NostrTURNChannel channel) {
            users.remove(channel);
        }

        void setConnectionListener(WebsocketTransportListener listener) {
            this.connectionListener = listener;
            transport.addListener(listener);
            if (closed.get()) transport.removeListener(listener);
        }

        void setConnectTimeoutTask(AsyncTask<Void> timeoutTask) {
            boolean cancel;
            synchronized (this) {
                cancel = closed.get() || pendingConnectFailure == null;
                if (!cancel) this.connectTimeoutTask = timeoutTask;
            }
            if (cancel) timeoutTask.cancel();
        }

        void clearConnectTimeoutTask() {
            AsyncTask<Void> timeoutTask;
            synchronized (this) {
                timeoutTask = this.connectTimeoutTask;
                this.connectTimeoutTask = null;
            }
            if (timeoutTask != null) {
                timeoutTask.cancel();
            }
        }

        void setPendingConnectFailure(Consumer<Throwable> pendingConnectFailure) {
            this.pendingConnectFailure = pendingConnectFailure;
            if (closed.get()) pendingConnectFailure.accept(new IllegalStateException("TURN websocket transport is closed"));
        }

        void clearPendingConnectFailure() {
            this.pendingConnectFailure = null;
        }

        void setLastChallengeFrame(byte[] frame) {
            if (frame == null || frame.length == 0) {
                this.lastChallengeFrame = null;
                return;
            }
            byte[] copy = new byte[frame.length];
            System.arraycopy(frame, 0, copy, 0, frame.length);
            this.lastChallengeFrame = copy;
        }

        byte[] getLastChallengeFrame() {
            byte[] frame = this.lastChallengeFrame;
            if (frame == null || frame.length == 0) {
                return null;
            }
            byte[] copy = new byte[frame.length];
            System.arraycopy(frame, 0, copy, 0, frame.length);
            return copy;
        }
    }
}
