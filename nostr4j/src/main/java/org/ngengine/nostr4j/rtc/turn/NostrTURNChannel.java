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

package org.ngengine.nostr4j.rtc.turn;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.ngengine.nostr4j.event.SignedNostrEvent;
import org.ngengine.nostr4j.keypair.NostrKeyPair;
import org.ngengine.nostr4j.rtc.signal.NostrRTCLocalPeer;
import org.ngengine.nostr4j.rtc.signal.NostrRTCPeer;
import org.ngengine.nostr4j.rtc.turn.NostrTURNPool.TURNTransport;
import org.ngengine.nostr4j.rtc.turn.event.NostrTURNAckEvent;
import org.ngengine.nostr4j.rtc.turn.event.NostrTURNChallengeEvent;
import org.ngengine.nostr4j.rtc.turn.event.NostrTURNCodec;
import org.ngengine.nostr4j.rtc.turn.event.NostrTURNConnectEvent;
import org.ngengine.nostr4j.rtc.turn.event.NostrTURNDataEvent;
import org.ngengine.nostr4j.rtc.turn.event.NostrTURNDeliveryAckEvent;
import org.ngengine.nostr4j.rtc.turn.event.NostrTURNDisconnectEvent;
import org.ngengine.nostr4j.rtc.turn.event.NostrTURNEvent;
import org.ngengine.platform.AsyncExecutor;
import org.ngengine.platform.AsyncTask;
import org.ngengine.platform.NGEPlatform;

/**
 * A virtual socket (vsocket) over TURN protocol.
 *
 * Represents an authenticated bidirectional channel between two peers via a TURN server.
 * The underlying session lifecycle is managed by NostrTURNPool.SessionManager.
 *
 * This class is just a convenient handle to send/receive data and listen for events.
 */
public final class NostrTURNChannel {

    private static final Logger logger = Logger.getLogger(NostrTURNChannel.class.getName());
    private static final AtomicLong VSOCKET_COUNTER = new AtomicLong(1L);
    private static final long DELIVERY_ACK_TIMEOUT_MS = 5000L;

    private final List<NostrTURNChannelListener> listeners = new CopyOnWriteArrayList<>();

    private final byte[] encryptionKey;

    private volatile TURNTransport transport;
    private volatile NostrTURNDataEvent incomingDataEvent;
    private volatile NostrTURNDataEvent outgoingDataEvent;
    private volatile NostrTURNDeliveryAckEvent outgoingDeliveryAckEvent;
    private final long vSocketId;
    private volatile int state = 0;
    private volatile boolean resurrecting = false;
    private volatile boolean closed = false;

    private final NostrRTCLocalPeer localPeer;
    private final NostrRTCPeer remotePeer;
    private volatile String turnServer; // can change due to redirects
    private final NostrKeyPair roomKeyPair;
    private final String channelLabel;
    private final int maxDiff;
    private final AtomicInteger outgoingMessageCounter = new AtomicInteger(1);
    private final Map<Integer, PendingWrite> pendingWrites = new ConcurrentHashMap<Integer, PendingWrite>();
    private final AsyncExecutor ackTimeoutExecutor;

    private static final class PendingWrite {
        private final Consumer<Boolean> resolve;
        private final Consumer<Throwable> reject;
        private volatile AsyncTask<Void> timeoutTask;

        private PendingWrite(Consumer<Boolean> resolve, Consumer<Throwable> reject) {
            this.resolve = resolve;
            this.reject = reject;
        }
    }

    // private final String channelLabel;
    // private final String sessionId;
    // private final String protocolId;
    // private final String applicationId;

    NostrTURNChannel(
        NostrRTCLocalPeer localPeer,
        NostrRTCPeer remotePeer,
        String turnServerUrl,
        NostrKeyPair roomKeyPair,
        String channelLabel,
        int maxDiff
    ) {
        this.turnServer = Objects.requireNonNull(turnServerUrl, "TURN server URL cannot be null");
        this.encryptionKey = NGEPlatform.get().randomBytes(32);
        this.roomKeyPair = Objects.requireNonNull(roomKeyPair, "Room key pair cannot be null");
        this.localPeer = Objects.requireNonNull(localPeer, "Local peer cannot be null");
        this.remotePeer = Objects.requireNonNull(remotePeer, "Remote peer cannot be null");
        this.channelLabel = Objects.requireNonNull(channelLabel, "Channel label cannot be null");
        this.maxDiff = maxDiff;
        this.vSocketId = nextVsocketId();
        this.ackTimeoutExecutor = NGEPlatform.get().newAsyncExecutor(NostrTURNChannel.class.getSimpleName() + "-ack-timeout");
    }

    private static long nextVsocketId() {
        long id = VSOCKET_COUNTER.getAndIncrement();
        if (id == 0L) {
            return VSOCKET_COUNTER.getAndIncrement();
        }
        return id;
    }

    public void addListener(NostrTURNChannelListener listener) {
        listeners.add(listener);
    }

    public void removeListener(NostrTURNChannelListener listener) {
        listeners.remove(listener);
    }

    public String getServerUrl() {
        return turnServer;
    }

    void setResurrecting(boolean b) {
        this.resurrecting = b;
    }

    boolean isResurrecting() {
        return resurrecting;
    }

    private void disconnect() {
        TURNTransport current = this.transport;
        if (current != null) {
            current.removeUser(this);
        }
        this.outgoingDataEvent = null;
        this.incomingDataEvent = null;
        this.outgoingDeliveryAckEvent = null;
        failPendingWrites(new IllegalStateException("TURN channel disconnected"));
        this.state = 0;
        setTransport(null);
    }

    public void redirectTo(String newTurnServer) {
        // set turn server and disconnect, this will force resurrection on the new turn
        this.turnServer = newTurnServer;
        disconnect();
    }

    void setTransport(TURNTransport transport) {
      this.resurrecting = false;
        TURNTransport previous = this.transport;
        this.transport = transport;
        if (transport == null || previous != transport) {
            this.outgoingDataEvent = null;
            this.incomingDataEvent = null;
            this.outgoingDeliveryAckEvent = null;
            failPendingWrites(new IllegalStateException("TURN transport changed"));
            this.state = 0;
        }
    }

    public boolean isReady() {
        return state == 2 && this.transport != null && this.transport.isConnected();
    }

    boolean isConnected() {
        return state > 0 &&  this.transport != null && this.transport.isConnected();
    }

    public AsyncTask<Boolean> write(ByteBuffer payload) {
        TURNTransport currentTransport = this.transport;
        if (!isReady() || currentTransport == null || !currentTransport.isConnected()) {
            return AsyncTask.completed(Boolean.FALSE);
        }

        if (outgoingDataEvent == null) {
            outgoingDataEvent =
                NostrTURNDataEvent.createOutgoing(localPeer, remotePeer, roomKeyPair, channelLabel, vSocketId, encryptionKey);
        }

        final int messageId = nextOutgoingMessageId();
        return AsyncTask.create((resolve, reject) -> {
            PendingWrite pendingWrite = new PendingWrite(resolve, reject);
            PendingWrite existing = pendingWrites.put(Integer.valueOf(messageId), pendingWrite);
            if (existing != null) {
                reject.accept(new IllegalStateException("Duplicate TURN message id: " + messageId));
                return;
            }

            pendingWrite.timeoutTask =
                ackTimeoutExecutor.runLater(
                    () -> {
                        PendingWrite timedOut = pendingWrites.remove(Integer.valueOf(messageId));
                        if (timedOut != null) {
                            timedOut.reject.accept(new RuntimeException("TURN delivery ack timeout for messageId=" + messageId));
                        }
                        return null;
                    },
                    DELIVERY_ACK_TIMEOUT_MS,
                    TimeUnit.MILLISECONDS
                );

            outgoingDataEvent
                .encodeToFrame(List.of(payload), messageId)
                .compose(bbf -> {
                    TURNTransport activeTransport = this.transport;
                    if (activeTransport == null || activeTransport != currentTransport || !activeTransport.isConnected()) {
                        clearPendingWrite(messageId);
                        return AsyncTask.completed(Boolean.FALSE);
                    }
                    return activeTransport.getTransport().sendBinary(bbf).then(v -> Boolean.TRUE);
                })
                .then(sent -> {
                    if (!Boolean.TRUE.equals(sent)) {
                        PendingWrite pending = clearPendingWrite(messageId);
                        if (pending != null) {
                            pending.resolve.accept(Boolean.FALSE);
                        }
                    }
                    return null;
                })
                .catchException(ex -> {
                    PendingWrite pending = clearPendingWrite(messageId);
                    if (pending != null) {
                        pending.reject.accept(ex);
                    }
                });
        });
    }

    public void close(String reason) {
        if (isConnected()) {
            NostrTURNDisconnectEvent disconnectEvent = NostrTURNDisconnectEvent.createDisconnect(
                localPeer,
                remotePeer,
                roomKeyPair,
                channelLabel,
                vSocketId,
                reason,
                false
            );
            sendControlEvent(disconnectEvent);
        }
        closed = true;
        failPendingWrites(new IllegalStateException("TURN channel closed: " + reason));
        for (NostrTURNChannelListener l : listeners) {
            try {
                l.onTurnChannelClosed(this, reason);
            } catch (Exception ex) {
                logger.log(Level.WARNING, "Error in TURN channel listener", ex);
            }
        }
        ackTimeoutExecutor.close();
        disconnect();
    }

    boolean isClosed() {
        return closed;
    }

    void onError(Throwable e) {
        failPendingWrites(e == null ? new RuntimeException("TURN channel error") : e);
        logger.log(Level.SEVERE, "TURN channel error", e);
        for (NostrTURNChannelListener l : listeners) {
            try {
                l.onTurnChannelError(this, e);
            } catch (Exception ex) {
                logger.log(Level.WARNING, "Error in TURN channel listener", ex);
            }
        }
    }

    void onConnectionMessage(String msg) {
        logger.fine("TURN: Received connection message: " + msg);
    }

    private void onPayload(ByteBuffer payload) {
        for (NostrTURNChannelListener l : listeners) {
            try {
                l.onTurnChannelMessage(this, payload);
            } catch (Exception ex) {
                logger.log(Level.WARNING, "Error in TURN channel listener", ex);
            }
        }
    }

    /**
     * Try handling data packet.
     * When called without header, it will attempt the quick path -> compare with previous
     * header assuming the other peer is reusing it.
     * When called with header, it will go through the full parsing and validation.
     *
     * Call this first with no header to try the quick path
     *  -> if it returns false, try again after parsing the header for the slow path
     * @param msg
     * @param header
     * @return true if the message was handled, false otherwise
     */
    boolean tryHandleData(ByteBuffer msg, SignedNostrEvent header, int messageId) {
        if (header == null) {
            return false;
        }

        if (state != 2) {
            logger.warning("TURN: Received data in invalid state " + state);
            return true;
        }

        long envelopeVsocketId = NostrTURNCodec.extractVsocketId(msg);
        incomingDataEvent =
            NostrTURNDataEvent.parseIncoming(header, localPeer, remotePeer, roomKeyPair, channelLabel, envelopeVsocketId);

        incomingDataEvent
            .decodeFramePayloads(msg)
            .then(ps -> {
                for (ByteBuffer payload : ps) {
                    onPayload(payload);
                }
                sendDeliveryAck(messageId);
                return null;
            })
            .catchException(ex -> {
                logger.log(Level.WARNING, "Failed to decode TURN data event", ex);
            });

        return true;
    }

    void onBinaryMessage(ByteBuffer msg) {
        long envelopeVsocketId;
        int envelopeMessageId;
        try {
            envelopeVsocketId = NostrTURNCodec.extractVsocketId(msg);
            envelopeMessageId = NostrTURNCodec.extractMessageId(msg);
        } catch (Exception e) {
            logger.log(Level.WARNING, "TURN: Invalid binary frame", e);
            return;
        }

        SignedNostrEvent header;
        try {
            header = NostrTURNCodec.decodeHeader(msg);
        } catch (Exception e) {
            logger.log(Level.WARNING, "TURN: Invalid binary frame", e);
            return;
        }
        if (header.getKind() != NostrTURNEvent.KIND) {
            logger.warning("TURN: Invalid event kind " + header.getKind());
            return;
        }

        String type = header.getFirstTagFirstValue("t");
        if (type == null || type.isEmpty()) {
            logger.warning("TURN: No event type in header");
            return;
        }

        switch (type) {
            case "data":
                {
                    if (envelopeVsocketId == 0L) {
                        throw new IllegalArgumentException("TURN data must have non-zero envelope vsocketId");
                    }
                    if (envelopeMessageId == 0) {
                        throw new IllegalArgumentException("TURN data must have non-zero messageId");
                    }
                    if (envelopeVsocketId != vSocketId) {
                        return;
                    }
                    if (state != 2) {
                        logger.warning("TURN: Received data in invalid state " + state);
                        return;
                    }
                    tryHandleData(msg, header, envelopeMessageId);
                    break;
                }
            case "delivery_ack":
                {
                    if (envelopeVsocketId == 0L) {
                        throw new IllegalArgumentException("TURN delivery_ack must have non-zero envelope vsocketId");
                    }
                    if (envelopeMessageId == 0) {
                        throw new IllegalArgumentException("TURN delivery_ack must have non-zero messageId");
                    }
                    if (envelopeVsocketId != vSocketId) {
                        return;
                    }
                    if (state != 2) {
                        logger.warning("TURN: Received delivery_ack in invalid state " + state);
                        return;
                    }
                    NostrTURNDeliveryAckEvent.parseIncoming(header, localPeer, remotePeer, envelopeVsocketId, envelopeMessageId);
                    completePendingWrite(envelopeMessageId);
                    break;
                }
            case "challenge":
                {
                    if (envelopeVsocketId != 0L) {
                        throw new IllegalArgumentException("TURN challenge must have envelope vsocketId=0");
                    }
                    if (envelopeMessageId != 0) {
                        throw new IllegalArgumentException("TURN challenge must have messageId=0");
                    }
                    if (state != 0) {
                        logger.warning("TURN: Received challenge in invalid state " + state);
                        return;
                    }
                    NostrTURNChallengeEvent challengeEvent = NostrTURNChallengeEvent.parseIncoming(header, localPeer, maxDiff);
                    String redirect = challengeEvent.getRedirect();
                    if (redirect != null && !redirect.isEmpty() && !redirect.equals(turnServer)) {
                        logger.fine("TURN: Redirecting to " + redirect);
                        redirectTo(redirect);
                        return;
                    }
                    String challenge = challengeEvent.getChallenge();
                    int requiredDifficulty = challengeEvent.getRequiredDifficulty();
                    NostrTURNConnectEvent connection = NostrTURNConnectEvent.createConnect(
                        localPeer,
                        remotePeer,
                        roomKeyPair,
                        channelLabel,
                        challenge,
                        vSocketId,
                        requiredDifficulty
                    );
                    state = 1;
                    sendControlEvent(connection);
                    break;
                }
            case "ack":
                {
                    if (envelopeVsocketId == 0L) {
                        throw new IllegalArgumentException("TURN ack must have non-zero envelope vsocketId");
                    }
                    if (envelopeMessageId != 0) {
                        throw new IllegalArgumentException("TURN ack must have messageId=0");
                    }
                    if (envelopeVsocketId != vSocketId) {
                        return;
                    }
                    if (state != 1) {
                        logger.warning("TURN: Received ack in invalid state " + state);
                        return;
                    }
                    NostrTURNAckEvent.parseIncoming(header, envelopeVsocketId);
                    state = 2;

                    // notify listeners
                    for (NostrTURNChannelListener l : listeners) {
                        try {
                            l.onTurnChannelReady(this);
                        } catch (Exception ex) {
                            logger.log(Level.WARNING, "Error in TURN channel listener", ex);
                        }
                    }

            
                    break;
                }
            case "disconnect":
                {
                    if (envelopeVsocketId == 0L) {
                        throw new IllegalArgumentException("TURN disconnect must have non-zero envelope vsocketId");
                    }
                    if (envelopeMessageId != 0) {
                        throw new IllegalArgumentException("TURN disconnect must have messageId=0");
                    }
                    if (envelopeVsocketId != vSocketId) {
                        return;
                    }
                    NostrTURNDisconnectEvent disconnectEvent = NostrTURNDisconnectEvent.parseIncoming(
                        header,
                        localPeer,
                        roomKeyPair,
                        remotePeer,
                        channelLabel,
                        envelopeVsocketId
                    );
                    String reason = disconnectEvent.getReason();
                    boolean error = disconnectEvent.isError();
                    logger.fine("TURN: Disconnected by peer. Reason: " + reason + ", error  " + error);
                    if (error) {
                        for (NostrTURNChannelListener l : listeners) {
                            try {
                                l.onTurnChannelError(this, new RuntimeException(reason));
                            } catch (Exception ex) {
                                logger.log(Level.WARNING, "Error in TURN channel listener", ex);
                            }
                        }
                    }

                    disconnect();
                    break;
                }
            default:
                logger.warning("TURN: Unknown event type " + type);
                break;
        }
    }

    private void sendControlEvent(NostrTURNEvent event) {
        TURNTransport currentTransport = this.transport;
        if (currentTransport == null || !currentTransport.isConnected()) {
            logger.fine("TURN channel has no active transport, cannot send control event");
            if (event instanceof NostrTURNConnectEvent) {
                state = 0;
            }
            return;
        }
        event
            .encodeToFrame(null)
            .compose(bbf -> {
                return currentTransport.getTransport().sendBinary(bbf);
            })
            .catchException(ex -> {
                logger.log(Level.FINE, "Failed to send TURN event: {0}", ex);
                if (event instanceof NostrTURNConnectEvent) {
                    state = 0;
                    setTransport(null);
                }
            });
    }

    private int nextOutgoingMessageId() {
        int id = outgoingMessageCounter.getAndIncrement();
        if (id == 0) {
            return outgoingMessageCounter.getAndIncrement();
        }
        return id;
    }

    private PendingWrite clearPendingWrite(int messageId) {
        PendingWrite removed = pendingWrites.remove(Integer.valueOf(messageId));
        if (removed != null && removed.timeoutTask != null) {
            removed.timeoutTask.cancel();
        }
        return removed;
    }

    private void completePendingWrite(int messageId) {
        PendingWrite pending = clearPendingWrite(messageId);
        if (pending != null) {
            pending.resolve.accept(Boolean.TRUE);
        }
    }

    private void failPendingWrites(Throwable error) {
        RuntimeException fallback = new RuntimeException("TURN write failed");
        Throwable cause = error == null ? fallback : error;
        for (Integer key : pendingWrites.keySet()) {
            PendingWrite pending = clearPendingWrite(key.intValue());
            if (pending != null) {
                pending.reject.accept(cause);
            }
        }
    }

    private void sendDeliveryAck(int messageId) {
        if (messageId == 0 || state != 2) {
            return;
        }
        TURNTransport currentTransport = this.transport;
        if (currentTransport == null || !currentTransport.isConnected()) {
            return;
        }
        if (outgoingDeliveryAckEvent == null) {
            outgoingDeliveryAckEvent = NostrTURNDeliveryAckEvent.createOutgoing(
                localPeer,
                remotePeer,
                roomKeyPair,
                channelLabel,
                vSocketId
            );
        }
        outgoingDeliveryAckEvent
            .encodeToFrame(null, messageId)
            .compose(bbf -> {
                TURNTransport activeTransport = this.transport;
                if (activeTransport == null || activeTransport != currentTransport || !activeTransport.isConnected()) {
                    return AsyncTask.completed(null);
                }
                return activeTransport.getTransport().sendBinary(bbf);
            })
            .catchException(ex -> {
                logger.log(Level.FINE, "Failed to send TURN delivery_ack: {0}", ex);
            });
    }
}
