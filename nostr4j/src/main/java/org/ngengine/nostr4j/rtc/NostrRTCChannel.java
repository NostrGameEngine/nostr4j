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

import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.ngengine.nostr4j.rtc.listeners.NostrRTCChannelListener;
import org.ngengine.nostr4j.rtc.listeners.NostrTURNChannelListener;
import org.ngengine.nostr4j.rtc.signal.NostrRTCPeer;
import org.ngengine.platform.AsyncTask;
import org.ngengine.platform.NGEPlatform;
import org.ngengine.platform.transport.RTCDataChannel;

public final class NostrRTCChannel {

    private static final Logger logger = Logger.getLogger(NostrRTCChannel.class.getName());
    private RTCDataChannel channel;
    private final NostrRTCSocket socket;
    private final String name;
    private final boolean ordered;
    private final boolean reliable;
    private final Number maxRetransmits;
    private final Duration maxPacketLifeTime;
    private int bufferedAmountThreshold = -1;
    private boolean closed = false;
    private final CopyOnWriteArrayList<NostrRTCChannelListener> listeners = new CopyOnWriteArrayList<>();

    private volatile NostrTURNChannel turnReceive;
    private volatile NostrTURNChannel turnSend;
    // private volatile boolean turnBootstrapInProgress = false;
    private volatile boolean resurrecting = false;

    NostrRTCChannel(
        String name,
        NostrRTCSocket socket,
        boolean ordered,
        boolean reliable,
        Number maxRetransmits,
        Duration maxPacketLifeTime
    ) {
        this.socket = socket;
        this.name = name;
        this.ordered = ordered;
        this.reliable = reliable;
        this.maxRetransmits = maxRetransmits != null ? maxRetransmits : Integer.valueOf(0);
        this.maxPacketLifeTime = maxPacketLifeTime;
    }

    public String getName() {
        return name;
    }

    NostrRTCSocket getSocket() {
        return socket;
    }

    void setResurrecting(boolean resurrecting) {
        this.resurrecting = resurrecting;
    }

    boolean isResurrecting() {
        return resurrecting;
    }

    private void emitChannelReady() {
        if (closed) {
            return;
        }
        socket.emitChannelReady(this);
    }

    void setChannel(RTCDataChannel chan) {
        this.channel = chan;
        this.resurrecting = false;
        if (chan != null && !socket.isForceTURN()) {
            if (bufferedAmountThreshold > 0) chan.setBufferedAmountLowThreshold(bufferedAmountThreshold);
            emitChannelReady();
            disposeTurn();
        } else if (socket.isTurnFallbackAllowed()) {
            ensureTurn();
        }
    }

    AsyncTask<Boolean> write(ByteBuffer data) {
        RTCDataChannel currentChannel = this.channel;
        if (isConnected() && !socket.isForceTURN()) {
            return NGEPlatform
                .get()
                .wrapPromise((res, rej) -> {
                    if (!isConnected()) {
                        res.accept(false);
                        return;
                    }
                    currentChannel
                        .write(data)
                        .then(r -> {
                            res.accept(true);
                            return null;
                        })
                        .catchException(ex -> {
                            res.accept(false);
                        });
                });
        }
        if (socket.isTurnFallbackAllowed() || socket.isForceTURN()) {
            ensureTurn();
        }
        NostrTURNChannel currentTurnSend = this.turnSend;
        if (currentTurnSend != null) {
            return currentTurnSend.write(data);
        }
        return AsyncTask.completed(Boolean.FALSE);
    }

    boolean isWriteReady() {
        if (closed) {
            return false;
        }
        if (!socket.isForceTURN() && channel != null) {
            return true;
        }
        if (socket.isTurnFallbackAllowed() || socket.isForceTURN()) {
            NostrTURNChannel currentTurnSend = this.turnSend;
            return currentTurnSend != null && currentTurnSend.isReady();
        }
        return false;
    }

    void close() {
        if (closed) return;
        closed = true;
        if (channel != null) {
            channel.close();
        }
        if (turnReceive != null) {
            turnReceive.close("rtc-closed");
        }
        if (turnSend != null) {
            turnSend.close("rtc-closed");
        }
        for (NostrRTCChannelListener l : listeners) {
            try{
                l.onRTCChannelClosed(this);
            } catch (Throwable e) {
                logger.log(Level.SEVERE, "Exception in listener", e);
            }
        }
    }

    public boolean isOrdered() {
        if (channel != null) {
            return channel.isOrdered();
        } else {
            return ordered;
        }
    }

    public boolean isReliable() {
        if (channel != null) {
            return channel.isReliable();
        } else {
            return reliable;
        }
    }

    public int getMaxRetransmits() {
        if (channel != null) {
            return channel.getMaxRetransmits();
        } else {
            return maxRetransmits.intValue();
        }
    }

    public Duration getMaxPacketLifeTime() {
        if (channel != null) {
            return channel.getMaxPacketLifeTime();
        } else {
            return maxPacketLifeTime;
        }
    }

    public AsyncTask<Number> getMaxMessageSize() {
        if (channel != null) {
            return channel.getMaxMessageSize();
        } else {
            return AsyncTask.completed(-1);
        }
    }

    public AsyncTask<Number> getAvailableAmount() {
        if (channel != null) {
            return channel.getAvailableAmount();
        } else {
            return AsyncTask.completed(-1);
        }
    }

    public AsyncTask<Number> getBufferedAmount() {
        if (channel != null) {
            return channel.getBufferedAmount();
        } else {
            return AsyncTask.completed(0);
        }
    }

    public AsyncTask<Void> setBufferedAmountLowThreshold(int threshold) {
        this.bufferedAmountThreshold = threshold;
        if (channel != null) {
            return channel.setBufferedAmountLowThreshold(threshold);
        } else {
            return AsyncTask.completed(null);
        }
    }

    int getBufferedAmountLowThreshold() {
        return bufferedAmountThreshold;
    }

    boolean isConnected() {
        return channel != null;
    }

    public boolean isClosed() {
        return closed;
    }

    void onRTCChannelError(Throwable e) {
        for (NostrRTCChannelListener l : listeners) {
            try{
                l.onRTCChannelError(this, e);
            } catch (Throwable ex) {
                logger.log(Level.SEVERE, "Exception in listener", ex);
            }
        }
    }

    void onRTCSocketMessage(ByteBuffer bbf) {
        for (NostrRTCChannelListener l : listeners) {
            try{
                l.onRTCSocketMessage(this, bbf, false);
            } catch (Throwable e) {
                logger.log(Level.SEVERE, "Exception in listener", e);
            }
        }
    }

    void onTURNSocketMessage(ByteBuffer bbf) {
        for (NostrRTCChannelListener listener : listeners) {
            try{
                listener.onRTCSocketMessage(this, bbf, true);
            } catch (Throwable e) {
                logger.log(Level.SEVERE, "Exception in listener", e);
            }
        }
    }

    void onRTCBufferedAmountLow() {
        for (NostrRTCChannelListener l : listeners) {
            try{
                l.onRTCBufferedAmountLow(this);
            } catch (Throwable e) {
                logger.log(Level.SEVERE, "Exception in listener", e);
            }
        }
    }

    void addListener(NostrRTCChannelListener listener) {
        listeners.addIfAbsent(listener);
    }

    void removeListener(NostrRTCChannelListener listener) {
        listeners.remove(listener);
    }

    void activateFallbackIfNeeded() {
        if (socket.isTurnFallbackAllowed()) {
            ensureTurn();
        }
    }

    private void disposeTurn() {
        NostrTURNChannel receive = turnReceive;
        NostrTURNChannel send = turnSend;
        turnReceive = null;
        turnSend = null;
        if (receive != null) {
            receive.close("rtc-p2p-established");
        }
        if (send != null && send != receive) {
            send.close("rtc-p2p-established");
        }
    }

    private void ensureTurn() {
        if (closed || socket.isClosed()) {
            return;
        }

        NostrTURNPool pool = socket.getTurnPool();
        if (pool == null) {
            return;
        }

        NostrRTCPeer remote = this.socket.getRemotePeer();
        if (remote == null) {
            return;
        }

        String sendTurn = this.socket.resolveSendTurnUrl();
        String receiveTurn = this.socket.resolveReceiveTurnUrl();
        boolean sharedTurn = sendTurn != null && !sendTurn.isEmpty() && Objects.equals(sendTurn, receiveTurn);

        if (turnSend != null && !Objects.equals(sendTurn, turnSend.getServerUrl())) {
            turnSend.redirectTo(sendTurn);
        }
        if (turnReceive != null && !Objects.equals(receiveTurn, turnReceive.getServerUrl())) {
            turnReceive.redirectTo(receiveTurn);
        }

        if (sharedTurn) {
            NostrTURNChannel shared = turnSend != null ? turnSend : turnReceive;
            if (shared == null) {
                shared =
                    pool.connect(
                        this.socket.getLocalPeer(),
                        remote,
                        sendTurn,
                        this.socket.getRoomKeyPair(),
                        this.name,
                        new NostrTURNChannelListener() {
                            @Override
                            public void onTurnChannelReady(NostrTURNChannel channel) {
                                emitChannelReady();
                            }

                            @Override
                            public void onTurnChannelClosed(NostrTURNChannel channel, String reason) {
                                disposeTurn();
                            }

                            @Override
                            public void onTurnChannelError(NostrTURNChannel channel, Throwable e) {
                                onRTCChannelError(e);
                            }

                            @Override
                            public void onTurnChannelMessage(NostrTURNChannel channel, ByteBuffer payload) {
                                onTURNSocketMessage(payload);
                            }
                        }
                    );
            }
            turnSend = shared;
            turnReceive = shared;
            return;
        }

        if (sendTurn != null && !sendTurn.isEmpty() && turnSend == null) {
            turnSend =
                pool.connect(
                    this.socket.getLocalPeer(),
                    remote,
                    sendTurn,
                    this.socket.getRoomKeyPair(),
                    this.name,
                    new NostrTURNChannelListener() {
                        @Override
                        public void onTurnChannelReady(NostrTURNChannel channel) {
                            emitChannelReady();
                        }

                        @Override
                        public void onTurnChannelClosed(NostrTURNChannel channel, String reason) {}

                        @Override
                        public void onTurnChannelError(NostrTURNChannel channel, Throwable e) {
                            onRTCChannelError(e);
                        }

                        @Override
                        public void onTurnChannelMessage(NostrTURNChannel channel, ByteBuffer payload) {}
                    }
                );
        }

        if (receiveTurn != null && !receiveTurn.isEmpty() && turnReceive == null) {
            turnReceive =
                pool.connect(
                    this.socket.getLocalPeer(),
                    remote,
                    receiveTurn,
                    this.socket.getRoomKeyPair(),
                    this.name,
                    new NostrTURNChannelListener() {
                        @Override
                        public void onTurnChannelReady(NostrTURNChannel channel) {
                            // receive path is ready; write readiness is signaled by turnSend
                        }

                        @Override
                        public void onTurnChannelClosed(NostrTURNChannel channel, String reason) {
                            disposeTurn();
                        }

                        @Override
                        public void onTurnChannelError(NostrTURNChannel channel, Throwable e) {
                            onRTCChannelError(e);
                        }

                        @Override
                        public void onTurnChannelMessage(NostrTURNChannel channel, ByteBuffer payload) {
                            onTURNSocketMessage(payload);
                        }
                    }
                );
        }
    }
}
