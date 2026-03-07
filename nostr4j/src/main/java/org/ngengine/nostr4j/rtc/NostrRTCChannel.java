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

import java.io.Closeable;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;
import org.ngengine.nostr4j.rtc.listeners.NostrRTCChannelListener;
import org.ngengine.nostr4j.rtc.signal.NostrRTCPeer;
import org.ngengine.nostr4j.rtc.turn.NostrTURNChannel;
import org.ngengine.nostr4j.rtc.turn.NostrTURNChannelListener;
import org.ngengine.nostr4j.rtc.turn.NostrTURNPool;
import org.ngengine.platform.AsyncExecutor;
import org.ngengine.platform.AsyncTask;
import org.ngengine.platform.ExecutionQueue;
import org.ngengine.platform.NGEPlatform;
import org.ngengine.platform.transport.RTCDataChannel;

public class NostrRTCChannel implements Closeable {

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
    private final Queue<ByteBuffer> messageQueue = NGEPlatform.get().newConcurrentQueue(ByteBuffer.class);
    private final CopyOnWriteArrayList<NostrRTCChannelListener> listeners = new CopyOnWriteArrayList<>();
    private final ExecutionQueue drainQueue = NGEPlatform.get().newExecutionQueue();
    private final Object drainLock = new Object();
    private final AsyncExecutor retryExecutor = NGEPlatform
        .get()
        .newAsyncExecutor(NostrRTCChannel.class.getSimpleName() + "-drain");
    private volatile boolean draining = false;
    private volatile AsyncTask<Void> retryDrainTask;

    private volatile NostrTURNChannel turnReceive;
    private volatile NostrTURNChannel turnSend;
    // private volatile boolean turnBootstrapInProgress = false;
    private volatile boolean resurrecting = false;
    private final AtomicBoolean readyNotificationEmitted = new AtomicBoolean(false);

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
        if (!readyNotificationEmitted.getAndSet(true)) {
            socket.emitChannelReady(this);
        }
    }

    void setChannel(RTCDataChannel chan) {
        this.channel = chan;
        this.resurrecting = false;
        if (chan != null) {
            if (bufferedAmountThreshold > 0) chan.setBufferedAmountLowThreshold(bufferedAmountThreshold);
            emitChannelReady();
            disposeTurn();
        } else if (socket.isTurnFallbackAllowed()) {
            ensureTurn();
        }
        scheduleDrain();
    }

    private AsyncTask<Void> writeToAvailablePath(ByteBuffer data) {
        RTCDataChannel currentChannel = this.channel;
        if (isConnected() && !socket.isForceTURN()) {
            AsyncTask<Void> task = currentChannel.write(data);
            if (task != null) {
                return task;
            }
        }
        if (socket.isTurnFallbackAllowed() || socket.isForceTURN()) {
            ensureTurn();
        }
        NostrTURNChannel currentTurnSend = this.turnSend;
        if (currentTurnSend != null && currentTurnSend.isReady()) {
            AsyncTask<Void> task = currentTurnSend.write(data);
            if (task != null) {
                return task;
            }
        }
        return null;
    }

    public AsyncTask<Void> write(ByteBuffer data) {
        AsyncTask<Void> writeTask = writeToAvailablePath(data);
        if (writeTask != null) {
            return writeTask;
        }

        ByteBuffer copy = data.duplicate();
        ByteBuffer bbf = ByteBuffer.allocateDirect(copy.remaining());
        bbf.put(copy);
        bbf.flip();
        messageQueue.add(bbf.duplicate());
        scheduleDrain();
        return AsyncTask.completed(null);
    }

    private void scheduleDrain() {
        synchronized (drainLock) {
            if (draining) {
                return;
            }
            draining = true;
        }
        drainQueue.enqueue(this::drainQueuedMessages);
    }

    private void drainQueuedMessages(java.util.function.Consumer<Void> resolve, java.util.function.Consumer<Throwable> reject) {
        ByteBuffer msg = messageQueue.poll();
        if (msg == null) {
            synchronized (drainLock) {
                draining = false;
                if (!messageQueue.isEmpty()) {
                    draining = true;
                    drainQueue.enqueue(this::drainQueuedMessages);
                }
            }
            resolve.accept(null);
            return;
        }

        AsyncTask<Void> writeTask = writeToAvailablePath(msg);
        if (writeTask == null) {
            messageQueue.add(msg);
            synchronized (drainLock) {
                draining = false;
            }
            scheduleDrainRetry();
        } else {
            writeTask
                .then(v -> {
                    drainQueuedMessages(resolve, reject);
                    return null;
                })
                .catchException(e -> {
                    messageQueue.add(msg);
                    synchronized (drainLock) {
                        draining = false;
                    }
                    scheduleDrainRetry();
                    reject.accept(e);
                });
        }
    }

    private void scheduleDrainRetry() {
        if (closed) {
            return;
        }
        if (retryDrainTask != null) {
            return;
        }
        retryDrainTask =
            retryExecutor.runLater(
                () -> {
                    retryDrainTask = null;
                    if (!closed) {
                        scheduleDrain();
                    }
                    return null;
                },
                100,
                TimeUnit.MILLISECONDS
            );
    }

    public void close() {
        if (closed) return;
        closed = true;
        AsyncTask<Void> retry = retryDrainTask;
        if (retry != null) {
            retry.cancel();
        }
        retryDrainTask = null;
        try {
            retryExecutor.close();
        } catch (Exception ignored) {}
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
            l.onRTCChannelClosed(this);
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
            l.onRTCChannelError(this, e);
        }
    }

    void onRTCSocketMessage(ByteBuffer bbf) {
        for (NostrRTCChannelListener l : listeners) {
            l.onRTCSocketMessage(this, bbf, false);
        }
    }

    void onTURNSocketMessage(ByteBuffer bbf) {
        for (NostrRTCChannelListener listener : listeners) {
            listener.onRTCSocketMessage(this, bbf, true);
        }
    }

    void onRTCBufferedAmountLow() {
        for (NostrRTCChannelListener l : listeners) {
            l.onRTCBufferedAmountLow(this);
        }
    }

    public void addListener(NostrRTCChannelListener listener) {
        listeners.addIfAbsent(listener);
    }

    public void removeListener(NostrRTCChannelListener listener) {
        listeners.remove(listener);
    }

    void activateFallbackIfNeeded() {
        if (socket.isTurnFallbackAllowed()) {
            ensureTurn();
        }
        scheduleDrain();
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

        if (turnSend != null && !Objects.equals(sendTurn, turnSend.getServerUrl())) {
            turnSend.redirectTo(sendTurn);
        }
        if (turnReceive != null && !Objects.equals(receiveTurn, turnReceive.getServerUrl())) {
            turnReceive.redirectTo(receiveTurn);
        }

        boolean sharedTurn = sendTurn != null && !sendTurn.isEmpty() && Objects.equals(sendTurn, receiveTurn);

        if (sharedTurn) {
            NostrTURNChannel shared = turnSend != null ? turnSend : turnReceive;
            if (shared == null) {
                shared = pool.connect(this.socket.getLocalPeer(), remote, sendTurn, this.socket.getRoomKeyPair(), this.name);
                bindTurnReceive(shared);
            }
            turnSend = shared;
            turnReceive = shared;
            return;
        }

        if (sendTurn != null && !sendTurn.isEmpty() && turnSend == null) {
            turnSend = pool.connect(this.socket.getLocalPeer(), remote, sendTurn, this.socket.getRoomKeyPair(), this.name);
        }

        if (receiveTurn != null && !receiveTurn.isEmpty() && turnReceive == null) {
            turnReceive =
                pool.connect(this.socket.getLocalPeer(), remote, receiveTurn, this.socket.getRoomKeyPair(), this.name);
            bindTurnReceive(turnReceive);
        }
    }

    private void bindTurnReceive(NostrTURNChannel channel) {
        channel.addListener(
            new NostrTURNChannelListener() {
                @Override
                public void onTurnChannelReady(NostrTURNChannel channel) {
                    emitChannelReady();
                    scheduleDrain();
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
