/**
 * BSD 3-Clause License
 * Copyright (c) 2025, Riccardo Balbo
 */
package org.ngengine.nostr4j.rtc;

import java.nio.ByteBuffer;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
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

    private final AsyncExecutor executor = NGEUtils.getPlatform().newAsyncExecutor(NostrTURNPool.class);

    private volatile boolean closed;

    private final List<NostrTURNChannel> channels = new CopyOnWriteArrayList<>();
    private final Map<String, AsyncTask<TURNTransport>> transports = new ConcurrentHashMap<>();
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
    NostrTURNChannel connect(
        NostrRTCLocalPeer localPeer,
        NostrRTCPeer remotePeer,
        String turnServerUrl,
        NostrKeyPair roomKeyPair,
        String channelLabel,
        boolean reliable,
        NostrTURNChannelListener listener
    ) {
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
        for (AsyncTask<TURNTransport> transport : transports.values()) {
            transport.then(tr -> {
                tr.close("TURN pool is closing");
                return null;
            });
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

        // try to reuse existing transports
        AsyncTask<TURNTransport> wsP = transports.compute(
            turnServerUrl,
            (url, tr) -> {
                if (tr == null || tr.isFailed() || (tr.isDone() && !NGEUtils.awaitNoThrow(tr).isConnected())) {
                    return NGEPlatform
                        .get()
                        .wrapPromise((res2, rej2) -> {
                            WebsocketTransport transport = NGEPlatform.get().newTransport();
                            TURNTransport wss = new TURNTransport(transport);
                            wss.transport.addListener(
                                new WebsocketTransportListener() {
                                    @Override
                                    public void onConnectionClosedByServer(String reason) {
                                        for (NostrTURNChannel user : wss.getUsers()) {
                                            user.setTransport(null);
                                        }
                                        rej2.accept(new RuntimeException("Websocket closed by server: " + reason));
                                    }

                                    @Override
                                    public void onConnectionOpen() {
                                        for (NostrTURNChannel user : wss.getUsers()) {
                                            user.setTransport(wss);
                                        }
                                        res2.accept(wss);
                                    }

                                    @Override
                                    public void onConnectionMessage(String msg) {
                                        for (NostrTURNChannel user : wss.getUsers()) {
                                            user.onConnectionMessage(msg);
                                        }
                                    }

                                    @Override
                                    public void onConnectionBinaryMessage(ByteBuffer msg) {
                                        ByteBuffer source = msg.asReadOnlyBuffer();
                                        source.rewind();
                                        cacheChallengeFrameIfPresent(wss, source);
                                        for (NostrTURNChannel user : wss.getUsers()) {
                                            ByteBuffer frame = source.asReadOnlyBuffer();
                                            frame.rewind();
                                            try {
                                                user.onBinaryMessage(frame);
                                            } catch (IllegalArgumentException ignored) {
                                                // Frame not meant for this logical channel.
                                            } catch (Throwable ex) {
                                                user.onError(ex);
                                            }
                                        }
                                    }

                                    @Override
                                    public void onConnectionClosedByClient(String reason) {
                                        for (NostrTURNChannel user : wss.getUsers()) {
                                            user.setTransport(null);
                                        }
                                        rej2.accept(new RuntimeException("Websocket closed by client: " + reason));
                                    }

                                    @Override
                                    public void onConnectionError(Throwable e) {
                                        for (NostrTURNChannel user : wss.getUsers()) {
                                            user.onError(e);
                                        }
                                    }
                                }
                            );

                            wss.addUser(channel);
                            wss.transport
                                .connect(url)
                                .catchException(e -> {
                                    wss.removeUser(channel);
                                    channel.setTransport(null);
                                    transports.remove(url, tr);
                                    rej2.accept(e);
                                });
                        });
                }
                return tr;
            }
        );

        return wsP
            .then(ws -> {
                if (ws.transport.isConnected()) {
                    ws.addUser(channel);
                    channel.setTransport(ws);
                    channel.openConnectionMaybe();
                    return ws;
                } else {
                    logger.warning(
                        "Websocket transport is not connected for URL: " +
                        turnServerUrl +
                        " - this should not happen, failing channel connection"
                    );
                    channel.setTransport(null);
                    ws.removeUser(channel);
                    throw new RuntimeException("Websocket transport is not connected for URL: " + turnServerUrl);
                }
            })
            .catchException(e -> {
                logger.warning(
                    "Failed to establish websocket transport for TURN server: " + turnServerUrl + " - " + e.getMessage()
                );
                channel.setTransport(null);
                throw new RuntimeException(e);
            });
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
                    Iterator<Entry<String, AsyncTask<TURNTransport>>> transportIterator = transports.entrySet().iterator();
                    while (transportIterator.hasNext()) {
                        Entry<String, AsyncTask<TURNTransport>> entry = transportIterator.next();
                        AsyncTask<TURNTransport> transportTask = entry.getValue();
                        if (transportTask.isDone()) {
                            TURNTransport transport = NGEUtils.awaitNoThrow(transportTask);
                            boolean unused = !transport.isUsed();
                            boolean disconnected = !transport.isConnected() && !transport.isConnecting;
                            if (unused || disconnected) {
                                if (transport.isConnected() || transport.isConnecting) {
                                    transport.close("TURN pool cleanup: transport not connected or unused");
                                }
                                transport
                                    .getUsers()
                                    .forEach(ch -> {
                                        ch.setTransport(null);
                                    });
                                transport.getUsers().clear();
                                transportIterator.remove();
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
        boolean isConnecting = false;

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
            return transport.isConnected();
        }

        public void close(String reason) {
            transport.close(reason);
        }

        public void addUser(NostrTURNChannel channel) {
            if (!users.contains(channel)) {
                users.add(channel);
            }
        }

        public void removeUser(NostrTURNChannel channel) {
            users.remove(channel);
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
