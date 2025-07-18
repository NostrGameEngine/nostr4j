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

import static org.ngengine.platform.NGEUtils.dbg;

import java.io.Closeable;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import org.ngengine.nostr4j.rtc.listeners.NostrRTCSocketListener;
import org.ngengine.nostr4j.rtc.signal.NostrRTCAnswer;
import org.ngengine.nostr4j.rtc.signal.NostrRTCIceCandidate;
import org.ngengine.nostr4j.rtc.signal.NostrRTCLocalPeer;
import org.ngengine.nostr4j.rtc.signal.NostrRTCOffer;
import org.ngengine.nostr4j.rtc.signal.NostrRTCPeer;
import org.ngengine.nostr4j.rtc.signal.NostrRTCSignal;
import org.ngengine.nostr4j.rtc.turn.NostrTURN;
import org.ngengine.nostr4j.rtc.turn.NostrTURNSettings;
import org.ngengine.platform.AsyncExecutor;
import org.ngengine.platform.AsyncTask;
import org.ngengine.platform.NGEPlatform;
import org.ngengine.platform.NGEUtils;
import org.ngengine.platform.RTCSettings;
import org.ngengine.platform.transport.RTCTransport;
import org.ngengine.platform.transport.RTCTransportListener;

/**
 * An RTC socket between two peers.
 * This class will try to establish a direct connection between the two peers, when
 * not possible it will fallback to a TURN server.
 *
 * Note:
 *      isConnected() will return true as long as any kind of connection is possible, this includes turn
 *      if the turn servers are provided. This also means that due to the nature of the TURN connection, the socket
 *       will always result connected as long as the TURN relay is reachable, even if the peer is long gone.
 *
 *      This is because, to avoid inefficiencies, the keep-alive mechanism is implemented only in the
 *      signaling protocol: when the signaling announce is stale, the socket should be closed using close().
 *      So keep in mind that you need to handle keep-alive youself, if you want to use this class by itself (without the signaling protocol).
 */
public class NostrRTCSocket implements RTCTransportListener, NostrTURN.Listener, Closeable {

    private static final Logger logger = Logger.getLogger(NostrRTCSocket.class.getName());

    private final List<NostrRTCSocketListener> listeners = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<String> localIceCandidates = new CopyOnWriteArrayList<>();

    private final String connectionId;
    private final RTCSettings settings;
    private final NostrTURNSettings turnSettings;
    private final AsyncExecutor executor;

    private final NostrRTCLocalPeer localPeer;

    private RTCTransport transport;
    private NostrTURN turn;
    private NostrRTCPeer remotePeer;

    private volatile boolean useTURN = false;
    private volatile boolean forceTURN = false;
    private volatile boolean connected = false;
    private volatile AsyncTask<Void> delayedCandidateEmission;

    public NostrRTCSocket(
        AsyncExecutor executor,
        NostrRTCLocalPeer localPeer,
        String connectionId,
        RTCSettings settings,
        NostrTURNSettings turnSettings
    ) {
        this.executor = Objects.requireNonNull(executor, "Executor cannot be null");
        this.connectionId = Objects.requireNonNull(connectionId, "Connection ID cannot be null");
        this.settings = Objects.requireNonNull(settings, "Settings cannot be null");
        this.localPeer = Objects.requireNonNull(localPeer, "Local Peer cannot be null");
        this.turnSettings = Objects.requireNonNull(turnSettings, "TURN Settings cannot be null");
    }

    /**
     * Get the local peer.
     * @return The local peer.
     */
    public NostrRTCLocalPeer getLocalPeer() {
        return localPeer;
    }

    /**
     * Get the remote peer if connected, otherwise null.
     * @return The remote peer or null.
     */
    public NostrRTCPeer getRemotePeer() {
        return remotePeer;
    }

    /**
     * Return true if the connection is established.
     * @return True if the connection is established.
     */
    public boolean isConnected() {
        return connected;
    }

    /**
     * Close the socket.
     */
    public void close() {
        logger.fine("Closing RTC Socket");
        if (this.transport != null) try {
            this.transport.close();
        } catch (Exception e) {
            logger.severe("Error closing transport: " + e.getMessage());
        }
        if (this.turn != null) try {
            this.turn.close();
        } catch (Exception e) {
            logger.severe("Error closing TURN: " + e.getMessage());
        }
        if (delayedCandidateEmission != null) {
            delayedCandidateEmission.cancel();
        }
        delayedCandidateEmission = null;
        localIceCandidates.clear();
        listeners.clear();
        for (NostrRTCSocketListener listener : listeners) {
            try {
                listener.onRTCSocketClose(this);
            } catch (Exception e) {
                logger.severe("Error closing socket: " + e.getMessage());
            }
        }
    }

    public void addListener(NostrRTCSocketListener listener) {
        listeners.add(listener);
    }

    public void removeListener(NostrRTCSocketListener listener) {
        listeners.remove(listener);
    }

    // internal, emit all candidates after a delay
    private void emitCandidates() {
        if (delayedCandidateEmission != null) {
            delayedCandidateEmission.cancel();
        }

        delayedCandidateEmission =
            this.executor.runLater(
                    () -> {
                        logger.fine("Emitting ICE candidates " + localIceCandidates);
                        NostrRTCIceCandidate iceCandidate = new NostrRTCIceCandidate(
                            localPeer.getPubkey(),
                            new ArrayList<String>(localIceCandidates),
                            new HashMap<String, Object>()
                        );
                        for (NostrRTCSocketListener listener : listeners) {
                            try {
                                listener.onRTCSocketLocalIceCandidate(this, iceCandidate);
                            } catch (Exception e) {
                                logger.severe("Error emitting ICE candidates: " + e.getMessage());
                            }
                        }
                        return null;
                    },
                    settings.getDelayedCandidatesInterval().toMillis(),
                    TimeUnit.MILLISECONDS
                );
    }

    /**
     * Listen for incoming RTC connections.
     * @return An async task that resolves with the offer string.
     * @throws IllegalStateException If the socket is already connected.
     */
    AsyncTask<NostrRTCOffer> listen() {
        try {
            if (this.transport != null) throw new IllegalStateException("Already connected");

            logger.fine("Listening for RTC connections on connection ID: " + connectionId);
            useTURN(false);

            NGEPlatform platform = NGEUtils.getPlatform();
            logger.fine("Creating RTC transport for connection ID: " + connectionId);

            this.transport = platform.newRTCTransport(settings, connectionId, localPeer.getStunServers());
            this.transport.addListener(this);

            logger.fine("Initiating RTC channel for connection ID: " + connectionId);

            return this.transport.initiateChannel()
                .then(offerString -> {
                    logger.fine("Use offer string: " + offerString + " to connect with connection ID: " + connectionId);
                    NostrRTCOffer offer = new NostrRTCOffer(
                        localPeer.getPubkey(),
                        offerString,
                        this.localPeer.getTurnServer(),
                        this.localPeer.getMisc()
                    );
                    logger.fine("Ready to send offer " + offer + " to connection ID: " + connectionId);

                    return offer;
                });
        } catch (Exception e) {
            logger.severe("Error while listening for RTC connections: " + e.getMessage());
            throw new IllegalStateException("Error while listening for RTC connections", e);
        }
    }

    /**
     * Connect to a remote peer.
     * @param offerOrAnswer The offer or answer to connect to.
     * @return An async tasks that resolves after the connection is established with an
     * answer string if the argument is an offer or null if the argument is an answer.
     * @throws IllegalStateException If the socket is already connected.
     * @throws IllegalArgumentException If the argument is not an offer or answer.
     */
    AsyncTask<NostrRTCAnswer> connect(NostrRTCSignal offerOrAnswer) {
        Objects.requireNonNull(offerOrAnswer);
        logger.fine("Connecting to RTC socket " + offerOrAnswer);
        useTURN(false);

        NGEPlatform platform = NGEUtils.getPlatform();

        String connectString;
        if (offerOrAnswer instanceof NostrRTCOffer) {
            if (this.transport != null) throw new IllegalStateException("Already connected");
            this.transport = platform.newRTCTransport(settings, connectionId, localPeer.getStunServers());
            this.transport.addListener(this);
            logger.fine("Use offer to connect");
            this.remotePeer =
                Objects.requireNonNull(((NostrRTCOffer) offerOrAnswer).getPeerInfo(), "Remote Peer cannot be null");
            emitCandidates();
            connectString = ((NostrRTCOffer) offerOrAnswer).getOfferString();
        } else if (offerOrAnswer instanceof NostrRTCAnswer) {
            // logger.fine("Use answer to connect");
            if (this.transport == null) throw new IllegalStateException("Not connected");

            this.remotePeer =
                Objects.requireNonNull(((NostrRTCAnswer) offerOrAnswer).getPeerInfo(), "Remote Peer cannot be null");
            emitCandidates();
            connectString = ((NostrRTCAnswer) offerOrAnswer).getSdp();
        } else {
            throw new IllegalArgumentException("Invalid RTC signal type");
        }

        logger.fine("Initializing TURN connection");
        if (!this.remotePeer.getTurnServer().isEmpty()) {
            this.turn = new NostrTURN(connectionId, localPeer, remotePeer, turnSettings);
            this.turn.addListener(this);
            this.turn.start();
        } else {
            this.turn = null;
        }

        return this.transport.connectToChannel(connectString)
            .then(answerString -> {
                if (answerString == null) {
                    logger.fine("Connected to RTC socket");
                    return null;
                }
                logger.fine("Connected to RTC socket, received answer " + answerString);
                NostrRTCAnswer answer = new NostrRTCAnswer(
                    localPeer.getPubkey(),
                    answerString,
                    this.localPeer.getTurnServer(),
                    this.localPeer.getMisc()
                );
                return answer;
            });
    }

    /**
     * Merge remote ICE candidates with the already
     * tracked candidates.
     * @param candidate The remote ICE candidates.
     */
    public void mergeRemoteRTCIceCandidate(NostrRTCIceCandidate candidate) {
        Objects.requireNonNull(candidate);
        Objects.requireNonNull(this.transport);
        this.transport.addRemoteIceCandidates(candidate.getCandidates());
    }

    @Override
    public void onLocalRTCIceCandidate(String candidateString) {
        logger.fine("Received local ICE candidate: " + candidateString);
        localIceCandidates.addIfAbsent(candidateString);
        emitCandidates();
    }

    @Override
    public void onRTCConnected() {
        logger.fine("Link established");
        connected = true;
        useTURN(false);
    }

    @Override
    public void onRTCDisconnected(String reason) {
        if (this.turn != null) {
            connected = true; // still connected via turn
            logger.info("RTC disconnected: " + reason);
            useTURN(true);
        } else {
            connected = false;
            logger.info("RTC disconnected: " + reason);
            this.close();
        }
    }

    /**
     * Set the socket to use the turn server.
     * @param use
     */
    public void useTURN(boolean use) {
        if (forceTURN) use = true;
        if (use == useTURN) return;
        logger.fine("Using TURN: " + use);
        this.useTURN = use;
    }

    public boolean isUsingTURN() {
        return useTURN;
    }

    @Override
    public void onRTCBinaryMessage(ByteBuffer bbf) {
        for (NostrRTCSocketListener listener : listeners) {
            try {
                listener.onRTCSocketMessage(this, bbf, false);
            } catch (Exception e) {
                logger.severe("Error emitting message: " + e.getMessage());
            }
        }
    }

    @Override
    public void onTurnPacket(NostrRTCPeer peer, ByteBuffer data) {
        for (NostrRTCSocketListener listener : listeners) {
            try {
                listener.onRTCSocketMessage(this, data, true);
            } catch (Exception e) {
                logger.severe("Error emitting message: " + e.getMessage());
            }
        }
    }

    /**
     * Send some data to the remote peer.
     * @param bbf The data to send (use Direct Buffers for performance).
     * @throws IllegalStateException If the socket is not connected.
     * @throws IllegalArgumentException If the data is null.
     * @return An async task that resolves when the data is sent.
     */
    public AsyncTask<Void> write(ByteBuffer bbf) {
        if (this.useTURN) {
            assert dbg(() -> {
                logger.finest("Send message with turn");
            });
            return this.turn.write(bbf);
        } else {
            assert dbg(() -> {
                logger.finest("Send message p2p");
            });
            return this.transport.write(bbf);
        }
    }

    @Override
    public void onRTCChannelError(Throwable e) {
        logger.severe("RTC Channel Error " + e);
    }

    /**
     * Force the usage of TURN server.
     * @param forceTURN
     */
    public void setForceTURN(boolean forceTURN) {
        this.forceTURN = forceTURN;
        if (!useTURN && forceTURN) {
            logger.fine("Forcing TURN usage");
            useTURN(true);
        }
    }
}
