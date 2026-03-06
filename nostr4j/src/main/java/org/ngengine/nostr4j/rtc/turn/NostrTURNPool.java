/**
 * BSD 3-Clause License
 * Copyright (c) 2025, Riccardo Balbo
 */
package org.ngengine.nostr4j.rtc.turn;

import java.nio.ByteBuffer;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import org.ngengine.nostr4j.event.SignedNostrEvent;
import org.ngengine.nostr4j.keypair.NostrKeyPair;
import org.ngengine.nostr4j.rtc.signal.NostrRTCLocalPeer;
import org.ngengine.nostr4j.rtc.signal.NostrRTCPeer;
import org.ngengine.nostr4j.rtc.turn.event.NostrTURNCodec;
import org.ngengine.nostr4j.rtc.turn.event.NostrTURNEvent;
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

    private final AsyncExecutor executor = NGEUtils.getPlatform().newAsyncExecutor(NostrTURNPool.class);

    private volatile boolean closed;

    private final List<NostrTURNChannel> channels = new CopyOnWriteArrayList<>();
    private final Map<String, TURNTransport> transports = new ConcurrentHashMap<>();
    private final int maxAcceptedDiff;

    public NostrTURNPool() {
        this(32);
    }

    public NostrTURNPool(int maxDiff) {
        this.maxAcceptedDiff = maxDiff;
        loop();
    }

    /**
     * Connect local peer to remote peer via the
     * specified turn settings.
     *
     * The returned channel is a logical channel that never
     * goes offline: if the underlying transport connection dies it will be
     * transparently resurrected on a new transport connection.
     */
    public NostrTURNChannel connect(
        NostrRTCLocalPeer localPeer,
        NostrRTCPeer remotePeer,
        String turnServerUrl,
        NostrKeyPair roomKeyPair,
        String channelLabel
    ) {
        NostrTURNChannel channel = new NostrTURNChannel(
            localPeer,
            remotePeer,
            turnServerUrl,
            roomKeyPair,
            channelLabel,
            maxAcceptedDiff
        );
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
        resurrectChannel(channel);
        return channel;
    }

    @Override
    public void close() {
        closed = true;
        for (NostrTURNChannel channel : channels) {
            channel.close("closed by pool");
        }
        channels.clear();
        for (TURNTransport transport : transports.values()) {
            transport.close("TURN pool is closing");
        }
        transports.clear();
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
        return NGEPlatform
            .get()
            .wrapPromise((res, rej) -> {
                // try to reuse existing transports
                TURNTransport ws = transports.compute(
                    turnServerUrl,
                    (url, tr) -> {
                        if (tr == null || (!tr.isConnecting && !tr.transport.isConnected())) {
                            TURNTransport wss = new TURNTransport(NGEPlatform.get().newTransport());
                            attachCoreTransportListener(wss);
                            wss.addUser(channel);
                            wss.isConnecting = true;
                            wss.transport
                                .connect(url)
                                .then(v -> {
                                    wss.isConnecting = false;
                                    return null;
                                })
                                .catchException(e -> {
                                    logger.warning("Failed to connect to TURN server: " + e.getMessage());
                                    wss.isConnecting = false;
                                    wss.removeUser(channel);
                                    channel.setTransport(null);
                                    rej.accept(e);
                                });
                            tr = wss;
                        }
                        return tr;
                    }
                );

                ws.addUser(channel);
                if (ws.transport.isConnected()) {
                    channel.setTransport(ws);
                    ws.replayLastChallenge(channel);
                    res.accept(ws);
                    return;
                }
                attachHandshakeListener(ws, channel, res, rej);
            });
    }

    private void attachCoreTransportListener(TURNTransport ws) {
        if (!ws.markCoreListenerAttached()) {
            return;
        }
        ws.transport.addListener(
            new WebsocketTransportListener() {
                @Override
                public void onConnectionClosedByServer(String reason) {
                    for (NostrTURNChannel user : ws.getUsers()) {
                        user.setTransport(null);
                    }
                }

                @Override
                public void onConnectionOpen() {
                    for (NostrTURNChannel user : ws.getUsers()) {
                        user.setTransport(ws);
                    }
                }

                @Override
                public void onConnectionMessage(String msg) {
                    for (NostrTURNChannel user : ws.getUsers()) {
                        user.onConnectionMessage(msg);
                    }
                }

                @Override
                public void onConnectionBinaryMessage(ByteBuffer msg) {
                    ByteBuffer source = msg.asReadOnlyBuffer();
                    source.rewind();
                    cacheChallengeFrameIfPresent(ws, source);
                    for (NostrTURNChannel user : ws.getUsers()) {
                        ByteBuffer frame = source.asReadOnlyBuffer();
                        frame.rewind();
                        try {
                            user.onBinaryMessage(frame);
                        } catch (IllegalArgumentException ignored) {
                            // Frame not meant for this logical channel.
                        } catch (Exception ex) {
                            user.onError(ex);
                        }
                    }
                }

                @Override
                public void onConnectionClosedByClient(String reason) {
                    for (NostrTURNChannel user : ws.getUsers()) {
                        user.setTransport(null);
                    }
                }

                @Override
                public void onConnectionError(Throwable e) {
                    for (NostrTURNChannel user : ws.getUsers()) {
                        user.onError(e);
                    }
                }
            }
        );
    }

    private void attachHandshakeListener(
        TURNTransport ws,
        NostrTURNChannel channel,
        java.util.function.Consumer<TURNTransport> res,
        java.util.function.Consumer<Throwable> rej
    ) {
        WebsocketTransportListener[] ref = new WebsocketTransportListener[1];
        ref[0] =
            new WebsocketTransportListener() {
                private volatile boolean done;

                private void cleanup() {
                    WebsocketTransportListener listener = ref[0];
                    if (listener != null) {
                        ws.transport.removeListener(listener);
                    }
                }

                private void resolveOnce() {
                    if (done) {
                        return;
                    }
                    done = true;
                    cleanup();
                    channel.setTransport(ws);
                    res.accept(ws);
                }

                private void rejectOnce(Throwable err) {
                    if (done) {
                        return;
                    }
                    done = true;
                    cleanup();
                    channel.setTransport(null);
                    ws.removeUser(channel);
                    rej.accept(err);
                }

                @Override
                public void onConnectionClosedByServer(String reason) {
                    rejectOnce(new RuntimeException("Websocket closed by server: " + reason));
                }

                @Override
                public void onConnectionOpen() {
                    resolveOnce();
                }

                @Override
                public void onConnectionMessage(String msg) {}

                @Override
                public void onConnectionBinaryMessage(ByteBuffer msg) {}

                @Override
                public void onConnectionClosedByClient(String reason) {
                    rejectOnce(new RuntimeException("Websocket closed by client: " + reason));
                }

                @Override
                public void onConnectionError(Throwable e) {
                    rejectOnce(e);
                }
            };
        ws.transport.addListener(ref[0]);
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

    /**
     * Resurrect a channel: gives it a fresh transport backend if its current one
     * is bad.
     * @param channel
     */
    private void resurrectChannel(NostrTURNChannel channel) {
        if (channel.isClosed() || channel.isConnected() || channel.isResurrecting()) {
            return;
        }
        channel.setResurrecting(true);
        useWebsocketTransport(channel)
            .then(transport -> {
                channel.setResurrecting(false);
                return null;
            })
            .catchException(e -> {
                logger.warning("Failed to resurrect TURN channel: " + e.getMessage());
                channel.setResurrecting(false);
            });
    }

    private void loop() {
        executor.runLater(
            () -> {
                if (closed) {
                    return null;
                }
                try {
                    // cleanup idle connections
                    Iterator<Entry<String, TURNTransport>> transportIterator = transports.entrySet().iterator();
                    while (transportIterator.hasNext()) {
                        Entry<String, TURNTransport> entry = transportIterator.next();
                        TURNTransport transport = entry.getValue();
                        if (!transport.isUsed() || (!transport.isConnected() && !transport.isConnecting)) {
                            transport.close("TURN pool cleanup: transport not connected or unused");
                            transport
                                .getUsers()
                                .forEach(channel -> {
                                    channel.setTransport(null);
                                });
                            transportIterator.remove();
                        }
                    }

                    // resurrect channels
                    for (NostrTURNChannel channel : channels) {
                        resurrectChannel(channel);
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
        private final Set<NostrTURNChannel> users = new CopyOnWriteArraySet<>();
        private volatile boolean coreListenerAttached = false;
        private volatile byte[] lastChallengeFrame = null;
        boolean isConnecting = false;

        public TURNTransport(WebsocketTransport transport) {
            this.transport = transport;
        }

        public WebsocketTransport getTransport() {
            return transport;
        }

        Set<NostrTURNChannel> getUsers() {
            return users;
        }

        public boolean isUsed() {
            return !users.isEmpty();
        }

        public boolean isConnected() {
            return transport.isConnected();
        }

        public void close(String reason) {
            transport.close(reason);
        }

        public void addUser(NostrTURNChannel channel) {
            users.add(channel);
        }

        public void removeUser(NostrTURNChannel channel) {
            users.remove(channel);
        }

        synchronized boolean markCoreListenerAttached() {
            if (coreListenerAttached) {
                return false;
            }
            coreListenerAttached = true;
            return true;
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

        void replayLastChallenge(NostrTURNChannel channel) {
            byte[] frame = this.lastChallengeFrame;
            if (channel == null || frame == null || frame.length == 0) {
                return;
            }
            try {
                channel.onBinaryMessage(ByteBuffer.wrap(frame).asReadOnlyBuffer());
            } catch (Exception ignored) {
                // Channel will continue through normal resurrection attempts.
            }
        }
    }
}
