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

import static org.ngengine.nostr4j.utils.NostrUtils.dbg;

import java.io.Closeable;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import org.ngengine.nostr4j.platform.AsyncTask;
import org.ngengine.nostr4j.platform.NostrExecutor;
import org.ngengine.nostr4j.platform.Platform;
import org.ngengine.nostr4j.rtc.signal.NostrRTCAnswer;
import org.ngengine.nostr4j.rtc.signal.NostrRTCIceCandidate;
import org.ngengine.nostr4j.rtc.signal.NostrRTCLocalPeer;
import org.ngengine.nostr4j.rtc.signal.NostrRTCOffer;
import org.ngengine.nostr4j.rtc.signal.NostrRTCPeer;
import org.ngengine.nostr4j.rtc.signal.NostrRTCSignal;
import org.ngengine.nostr4j.rtc.turn.NostrTURN;
import org.ngengine.nostr4j.rtc.turn.NostrTURNSettings;
import org.ngengine.nostr4j.transport.RTCTransport;
import org.ngengine.nostr4j.utils.NostrUtils;

public class NostrRTCSocket implements RTCTransport.RTCTransportListener, NostrTURN.Listener, Closeable {

    private static final Logger logger = Logger.getLogger(NostrRTCSocket.class.getName());

    private final List<NostrRTCSocketListener> listeners = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<String> localIceCandidates = new CopyOnWriteArrayList<>();

    private final String connectionId;
    private final NostrRTCSettings settings;
    private final NostrTURNSettings turnSettings;
    private final NostrExecutor executor;

    private final NostrRTCLocalPeer localPeer;
    private final Instant createdAt = Instant.now();

    private RTCTransport transport;
    private NostrTURN turn;
    private NostrRTCPeer remotePeer;

    private volatile boolean useTURN = false;
    private volatile boolean connected = false;
    private volatile AsyncTask<Void> delayedCandidateEmission;

    public NostrRTCSocket(
        NostrExecutor executor,
        NostrRTCLocalPeer localPeer,
        String connectionId,
        NostrRTCSettings settings,
        NostrTURNSettings turnSettings
    ) {
        this.executor = Objects.requireNonNull(executor, "Executor cannot be null");
        this.connectionId = Objects.requireNonNull(connectionId, "Connection ID cannot be null");
        this.settings = Objects.requireNonNull(settings, "Settings cannot be null");
        this.localPeer = Objects.requireNonNull(localPeer, "Local Peer cannot be null");
        this.turnSettings = Objects.requireNonNull(turnSettings, "TURN Settings cannot be null");
    }

    public NostrRTCLocalPeer getLocalPeer() {
        return localPeer;
    }

    public NostrRTCPeer getRemotePeer() {
        return remotePeer;
    }

    public boolean isConnected() {
        return connected;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

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
    }

    public void addListener(NostrRTCSocketListener listener) {
        listeners.add(listener);
    }

    public void removeListener(NostrRTCSocketListener listener) {
        listeners.remove(listener);
    }

    private void emitCandidates() {
        if (delayedCandidateEmission != null) {
            delayedCandidateEmission.cancel();
        }

        delayedCandidateEmission =
            this.executor.runLater(
                    () -> {
                        // logger.fine("Emitting ICE candidates " + localIceCandidates);
                        NostrRTCIceCandidate iceCandidate = new NostrRTCIceCandidate(
                            localPeer.getPubkey(),
                            new ArrayList<String>(localIceCandidates),
                            new HashMap<String, Object>()
                        );
                        for (NostrRTCSocketListener listener : listeners) {
                            listener.onRTCSocketLocalIceCandidate(this, iceCandidate);
                        }
                        return null;
                    },
                    1,
                    TimeUnit.SECONDS
                );
    }

    AsyncTask<NostrRTCOffer> listen() {
        if (this.transport != null) throw new IllegalStateException("Already connected");

        // logger.fine("Listening for RTC connections");
        useTURN(false);

        Platform platform = NostrUtils.getPlatform();
        this.transport = platform.newRTCTransport(connectionId, localPeer.getStunServers());
        this.transport.addListener(this);

        return this.transport.initiateChannel()
            .then(offerString -> {
                NostrRTCOffer offer = new NostrRTCOffer(
                    localPeer.getPubkey(),
                    offerString,
                    this.localPeer.getTurnServer(),
                    this.localPeer.getMisc()
                );
                // logger.fine("Preparing offer " + offer);

                return offer;
            });
    }

    AsyncTask<NostrRTCAnswer> connect(NostrRTCSignal offerOrAnswer) {
        // logger.fine("Connecting to RTC socket " + offerOrAnswer);
        useTURN(false);

        Platform platform = NostrUtils.getPlatform();

        String connectString;
        if (offerOrAnswer instanceof NostrRTCOffer) {
            if (this.transport != null) throw new IllegalStateException("Already connected");
            this.transport = platform.newRTCTransport(connectionId, localPeer.getStunServers());
            this.transport.addListener(this);
            // logger.fine("Use offer to connect");
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

        // logger.fine("Initializing TURN connection");
        this.turn = new NostrTURN(connectionId, localPeer, remotePeer, turnSettings);
        this.turn.addListener(this);
        this.turn.start();

        return this.transport.connectToChannel(connectString)
            .then(answerString -> {
                if (answerString == null) {
                    // logger.fine("Connected to RTC socket");
                    return null;
                }
                // logger.fine("Connected to RTC socket, received answer " + answerString);
                NostrRTCAnswer answer = new NostrRTCAnswer(
                    localPeer.getPubkey(),
                    answerString,
                    this.localPeer.getTurnServer(),
                    this.localPeer.getMisc()
                );
                return answer;
            });
    }

    public void mergeRemoteRTCIceCandidate(NostrRTCIceCandidate candidate) {
        if (this.transport != null) {
            // logger.fine("Merging remote ICE candidates " + candidate);
            this.transport.addRemoteIceCandidates(candidate.getCandidates());
        }
    }

    @Override
    public void onLocalRTCIceCandidate(String candidateString) {
        // logger.fine("Received local ICE candidate: " + candidateString);
        localIceCandidates.addIfAbsent(candidateString);
        emitCandidates();
    }

    @Override
    public void onLinkEstablished() {
        logger.fine("Link established");
        connected = true;
        useTURN(false);
    }

    @Override
    public void onLinkLost() {
        logger.fine("Link lost");
        connected = true;
        useTURN(true);
    }

    public void useTURN(boolean use) {
        if (use == useTURN) return;
        logger.fine("Using TURN: " + use);
        this.useTURN = use;
    }

    @Override
    public void onRTCBinaryMessage(ByteBuffer bbf) {
        for (NostrRTCSocketListener listener : listeners) {
            listener.onRTCSocketMessage(this, bbf, false);
        }
    }

    @Override
    public void onTurnPacket(NostrRTCPeer peer, ByteBuffer data) {
        for (NostrRTCSocketListener listener : listeners) {
            listener.onRTCSocketMessage(this, data, true);
        }
    }

    public void write(ByteBuffer bbf) {
        if (this.useTURN) {
            assert dbg(() -> {
                logger.finest("Send message with turn");
            });
            this.turn.write(bbf);
        } else {
            assert dbg(() -> {
                logger.finest("Send message p2p");
            });
            this.transport.write(bbf);
        }
    }

    @Override
    public void onRTCChannelClosed() {
        logger.info("RTC Channel Closed");
        useTURN(true);
    }

    @Override
    public void onRTCChannelError(Throwable e) {
        logger.severe("RTC Channel Error " + e);
    }
}
