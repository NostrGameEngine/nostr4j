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
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
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
    private static final int MAX_FRAGMENT_SIZE = 0xFFFF;
    private static final byte[] INNER_FRAME_MAGIC = new byte[] {
        0x6E,
        0x34,
        0x6A,
        0x2D,
        0x72,
        0x74,
        0x63,
        0x2D,
        0x69,
        0x6E,
        0x6E,
        0x65,
        0x72,
        0x2D,
        0x76,
        0x31,
    };
    private static final byte INNER_FRAME_VERSION = 1;
    private static final int INNER_FRAME_HEADER_SIZE = INNER_FRAME_MAGIC.length + 1 + Long.BYTES + Short.BYTES + Short.BYTES;
    private static final int RECEIVE_DEDUP_WINDOW = 4096;
    private static final long FRAGMENT_REASSEMBLY_TIMEOUT_MS = 30_000L;
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
    private final AtomicLong nextPacketId = new AtomicLong(1L);
    private final Object receivedPacketIdsLock = new Object();
    private final Set<Long> receivedPacketIds = new LinkedHashSet<Long>();
    private final Map<Long, PendingInboundFragments> pendingFragments = new HashMap<Long, PendingInboundFragments>();

    private static final class PendingInboundFragments {

        private final long createdAtMs;
        private final int fragmentCount;
        private final ByteBuffer[] fragments;
        private int receivedFragments;
        private int totalBytes;

        private PendingInboundFragments(int fragmentCount) {
            this.createdAtMs = System.currentTimeMillis();
            this.fragmentCount = fragmentCount;
            this.fragments = new ByteBuffer[fragmentCount];
            this.receivedFragments = 0;
            this.totalBytes = 0;
        }

        private boolean isExpired(long now) {
            return now - createdAtMs > FRAGMENT_REASSEMBLY_TIMEOUT_MS;
        }

        private synchronized ByteBuffer addFragment(int fragmentId, ByteBuffer fragmentPayload) {
            if (fragmentId < 0 || fragmentId >= fragmentCount) {
                return null;
            }
            if (fragments[fragmentId] != null) {
                return null;
            }

            ByteBuffer copy = ByteBuffer.allocate(fragmentPayload.remaining());
            copy.put(fragmentPayload.duplicate());
            copy.flip();
            fragments[fragmentId] = copy.asReadOnlyBuffer();
            receivedFragments++;
            totalBytes += copy.remaining();
            if (receivedFragments != fragmentCount) {
                return null;
            }

            ByteBuffer merged = ByteBuffer.allocate(totalBytes);
            for (int i = 0; i < fragmentCount; i++) {
                ByteBuffer fragment = fragments[i];
                if (fragment == null) {
                    return null;
                }
                merged.put(fragment.duplicate());
            }
            merged.flip();
            return merged.asReadOnlyBuffer();
        }
    }

    static final class PreparedPacket {

        private final long packetId;
        private final ByteBuffer payload;

        private PreparedPacket(long packetId, ByteBuffer payload) {
            this.packetId = packetId;
            this.payload = payload;
        }

        ByteBuffer payload() {
            return payload.duplicate();
        }

        long packetId() {
            return packetId;
        }
    }

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

    PreparedPacket prepareOutgoingPacket(ByteBuffer data) {
        ByteBuffer source = data.duplicate();
        ByteBuffer payload = ByteBuffer.allocate(source.remaining());
        payload.put(source);
        payload.flip();
        long packetId = nextPacketId.getAndUpdate(current -> current == Long.MAX_VALUE ? 1L : current + 1L);
        return new PreparedPacket(packetId, payload.asReadOnlyBuffer());
    }

    static Long tryExtractPacketId(ByteBuffer bbf) {
        ByteBuffer payload = bbf.duplicate();
        if (payload.remaining() < INNER_FRAME_HEADER_SIZE) {
            return null;
        }
        for (byte b : INNER_FRAME_MAGIC) {
            if (payload.get() != b) {
                return null;
            }
        }
        if (payload.get() != INNER_FRAME_VERSION) {
            return null;
        }
        long packetId = payload.getLong();
        if (packetId <= 0L) {
            return null;
        }
        return Long.valueOf(packetId);
    }

    AsyncTask<Boolean> write(ByteBuffer data) {
        return write(prepareOutgoingPacket(data));
    }

    AsyncTask<Boolean> write(PreparedPacket packet) {
        int limit = MAX_FRAGMENT_SIZE;
        int payloadChunkSize;
        if (limit <= 0) {
            payloadChunkSize = Integer.MAX_VALUE - INNER_FRAME_HEADER_SIZE;
        } else {
            payloadChunkSize = Math.max(1, limit - INNER_FRAME_HEADER_SIZE);
        }

        ByteBuffer[] frames = encodePacketFragments(packet, payloadChunkSize);
        AsyncTask<Boolean> chain = AsyncTask.completed(Boolean.TRUE);
        for (ByteBuffer frame : frames) {
            final ByteBuffer framePayload = frame.asReadOnlyBuffer();
            chain =
                chain.compose(ok -> {
                    if (!Boolean.TRUE.equals(ok)) {
                        return AsyncTask.completed(Boolean.FALSE);
                    }
                    return writeSingleFragment(framePayload);
                });
        }
        return chain;
    }

    

    public int getMaxFragmentSize() {
        return MAX_FRAGMENT_SIZE;
    }

    private ByteBuffer[] encodePacketFragments(PreparedPacket packet, int payloadChunkSize) {
        ByteBuffer payload = packet.payload();
        int chunkSize = Math.max(1, payloadChunkSize);
        int totalSize = payload.remaining();
        int fragmentCount = Math.max(1, (totalSize + chunkSize - 1) / chunkSize);
        if (fragmentCount > Short.MAX_VALUE) {
            throw new IllegalArgumentException("fragmentCount exceeds short max: " + fragmentCount);
        }
        ByteBuffer[] frames = new ByteBuffer[fragmentCount];
        for (int fragmentId = 0; fragmentId < fragmentCount; fragmentId++) {
            int fragmentSize = Math.min(chunkSize, payload.remaining());
            ByteBuffer framed = ByteBuffer.allocate(INNER_FRAME_HEADER_SIZE + fragmentSize);
            framed.put(INNER_FRAME_MAGIC);
            framed.put(INNER_FRAME_VERSION);
            framed.putLong(packet.packetId());
            framed.putShort((short) fragmentId);
            framed.putShort((short) fragmentCount);

            ByteBuffer slice = payload.slice();
            slice.limit(fragmentSize);
            framed.put(slice);
            payload.position(payload.position() + fragmentSize);

            framed.flip();
            frames[fragmentId] = framed.asReadOnlyBuffer();
        }
        return frames;
    }

    private AsyncTask<Boolean> writeSingleFragment(ByteBuffer payload) {
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
                        .write(payload)
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
            return currentTurnSend.write(payload);
        }
        return AsyncTask.completed(Boolean.FALSE);
    }

    boolean isReady() {
        if (closed) {
            return false;
        }
        if (!socket.isForceTURN() && channel != null) {
            return true;
        }
        if (socket.isTurnFallbackAllowed() || socket.isForceTURN()) {
            NostrTURNChannel currentTurnSend = this.turnSend;
            NostrTURNChannel currentTurnReceive = this.turnReceive;
            return (
                currentTurnSend != null &&
                currentTurnSend.isReady() &&
                currentTurnReceive != null &&
                currentTurnReceive.isReady()
            );
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
            try {
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

    boolean hasUsableTransport() {
        return channel != null || isTurnReady();
    }

    boolean isTurnReady() {
        NostrTURNChannel currentTurnSend = this.turnSend;
        if (currentTurnSend != null && currentTurnSend.isReady()) {
            return true;
        }
        NostrTURNChannel currentTurnReceive = this.turnReceive;
        return (
            currentTurnReceive != null && currentTurnReceive.isReady() && currentTurnSend != null && currentTurnSend.isReady()
        );
    }

    public boolean isClosed() {
        return closed;
    }

    void onRTCChannelError(Throwable e) {
        for (NostrRTCChannelListener l : listeners) {
            try {
                l.onRTCChannelError(this, e);
            } catch (Throwable ex) {
                logger.log(Level.SEVERE, "Exception in listener", ex);
            }
        }
    }

    void onRTCSocketMessage(ByteBuffer bbf) {
        ByteBuffer payload = unwrapIncomingPayload(bbf);
        if (payload == null) {
            return;
        }
        for (NostrRTCChannelListener l : listeners) {
            try {
                l.onRTCSocketMessage(this, payload.duplicate(), false);
            } catch (Throwable e) {
                logger.log(Level.SEVERE, "Exception in listener", e);
            }
        }
    }

    void onTURNSocketMessage(ByteBuffer bbf) {
        ByteBuffer payload = unwrapIncomingPayload(bbf);
        if (payload == null) {
            return;
        }
        for (NostrRTCChannelListener listener : listeners) {
            try {
                listener.onRTCSocketMessage(this, payload.duplicate(), true);
            } catch (Throwable e) {
                logger.log(Level.SEVERE, "Exception in listener", e);
            }
        }
    }

    private ByteBuffer unwrapIncomingPayload(ByteBuffer bbf) {
        ByteBuffer payload = bbf.duplicate();
        if (payload.remaining() < INNER_FRAME_HEADER_SIZE) {
            return null;
        }
        for (byte b : INNER_FRAME_MAGIC) {
            if (payload.get() != b) {
                return null;
            }
        }
        if (payload.get() != INNER_FRAME_VERSION) {
            return null;
        }
        long packetId = payload.getLong();
        int fragmentId = payload.getShort();
        int fragmentCount = payload.getShort();
        if (packetId <= 0L) {
            return null;
        }
        if (fragmentCount <= 0 || fragmentId < 0 || fragmentId >= fragmentCount) {
            return null;
        }

        synchronized (receivedPacketIdsLock) {
            pruneExpiredPendingFragmentsLocked();
            if (receivedPacketIds.contains(Long.valueOf(packetId))) {
                return null;
            }

            PendingInboundFragments pending = pendingFragments.get(Long.valueOf(packetId));
            if (pending == null || pending.fragmentCount != fragmentCount) {
                pending = new PendingInboundFragments(fragmentCount);
                pendingFragments.put(Long.valueOf(packetId), pending);
            }

            ByteBuffer merged = pending.addFragment(fragmentId, payload.slice());
            if (merged == null) {
                return null;
            }

            pendingFragments.remove(Long.valueOf(packetId));
            if (!recordCompletedPacketIdLocked(packetId)) {
                return null;
            }
            return merged;
        }
    }

    private boolean recordCompletedPacketIdLocked(long packetId) {
        if (receivedPacketIds.contains(Long.valueOf(packetId))) {
            return false;
        }
        receivedPacketIds.add(Long.valueOf(packetId));
        while (receivedPacketIds.size() > RECEIVE_DEDUP_WINDOW) {
            Long oldest = receivedPacketIds.iterator().next();
            receivedPacketIds.remove(oldest);
        }
        return true;
    }

    private void pruneExpiredPendingFragmentsLocked() {
        if (pendingFragments.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        pendingFragments.entrySet().removeIf(entry -> {
            PendingInboundFragments pending = entry.getValue();
            return pending == null || pending.isExpired(now);
        });
    }

    void onRTCBufferedAmountLow() {
        for (NostrRTCChannelListener l : listeners) {
            try {
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
        boolean hasSendTurn = sendTurn != null && !sendTurn.isEmpty();
        boolean hasReceiveTurn = receiveTurn != null && !receiveTurn.isEmpty();
        if (!hasSendTurn || !hasReceiveTurn) {
            disposeTurn();
            onRTCChannelError(
                new IllegalStateException("TURN fallback requires both sender and receiver TURN servers to be configured")
            );
            return;
        }
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
                        this.reliable,
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
                    this.reliable,
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
                    this.reliable,
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
