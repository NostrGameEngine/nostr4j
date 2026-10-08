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
import java.util.function.BooleanSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.ngengine.nostr4j.rtc.listeners.NostrRTCChannelListener;
import org.ngengine.nostr4j.rtc.listeners.NostrTURNChannelListener;
import org.ngengine.nostr4j.rtc.routing.InternalRoutedTransport;
import org.ngengine.nostr4j.rtc.signal.NostrRTCPeer;
import org.ngengine.nostr4j.rtc.turn.NostrTURNDataEvent;
import org.ngengine.platform.AsyncTask;
import org.ngengine.platform.NGEPlatform;
import org.ngengine.platform.transport.RTCDataChannel;

public final class NostrRTCChannel {

    private static final Logger logger = Logger.getLogger(NostrRTCChannel.class.getName());
    static final int PAYLOAD_ENVELOPE_HEADER_SIZE = Long.BYTES + Short.BYTES + Short.BYTES;
    static final int MAX_FRAMED_PAYLOAD_SIZE = NostrTURNDataEvent.MAX_FRAMED_PAYLOAD_SIZE;
    static final int MAX_APPLICATION_FRAGMENT_SIZE = MAX_FRAMED_PAYLOAD_SIZE - PAYLOAD_ENVELOPE_HEADER_SIZE;
    private static final int INNER_FRAME_HEADER_SIZE = PAYLOAD_ENVELOPE_HEADER_SIZE;
    private static final int RECEIVE_DEDUP_WINDOW = 4096;
    static final int MAX_PENDING_FRAGMENT_PACKETS = 64;
    static final int MAX_FRAGMENTS_PER_PACKET = 1024;
    static final int MAX_REASSEMBLY_BYTES = 16 * 1024 * 1024;
    private int pendingFragmentBytes;
    private AsyncTask<Void> fragmentCleanupTask;
    private static final long FRAGMENT_REASSEMBLY_TIMEOUT_MS = 30_000L;
    private volatile RTCDataChannel channel;
    private final NostrRTCSocket socket;
    private final String name;
    private final boolean ordered;
    private final boolean reliable;
    private final Number maxRetransmits;
    private final Duration maxPacketLifeTime;
    private int bufferedAmountThreshold = -1;
    private volatile boolean closed = false;
    private final AtomicLong writeGeneration = new AtomicLong();
    private final CopyOnWriteArrayList<NostrRTCChannelListener> listeners = new CopyOnWriteArrayList<>();

    private volatile NostrTURNChannel turnReceive;
    private volatile NostrTURNChannel turnSend;
    private volatile NostrTURNChannel confirmedTurnSend;
    private volatile long confirmedTurnGeneration = Long.MIN_VALUE;
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
            return now - createdAtMs >= FRAGMENT_REASSEMBLY_TIMEOUT_MS;
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
        synchronized (socket) {
            updateNativeChannelState(chan);
        }
        finishNativeChannelChange(chan, () -> true);
    }

    boolean isUsingNativeChannel(RTCDataChannel expected) {
        return channel == expected;
    }

    /**
     * State-only part, called under the owning socket monitor.
     */
    void updateNativeChannelState(RTCDataChannel chan) {
        boolean replaced = this.channel != chan;
        this.channel = chan;
        if (replaced) writeGeneration.incrementAndGet();
        this.resurrecting = false;
    }

    /**
     * Native/application calls run after releasing the owning socket monitor.
     */
    void finishNativeChannelChange(RTCDataChannel chan, BooleanSupplier ownerActive) {
        // TURN/native connectivity checks can call provider code, so obtain this snapshot outside the socket monitor.
        boolean turnReady = isTurnReady();
        Runnable cleanup = () -> {};
        Runnable ready = () -> {};
        boolean bootstrapTurn = false;
        int threshold = -1;
        synchronized (socket) {
            if (closed || this.channel != chan || !ownerActive.getAsBoolean()) return;
            if (chan != null && !socket.isForceTURN()) {
                threshold = bufferedAmountThreshold;
                cleanup = detachTurn();
                ready = socket.prepareChannelReadyNotification(this, turnReady, ownerActive);
            } else {
                bootstrapTurn = socket.isTurnFallbackAllowed() || socket.isForceTURN();
            }
        }
        try {
            if (chan != null && threshold > 0) chan.setBufferedAmountLowThreshold(threshold);
            ready.run();
        } finally {
            cleanup.run();
        }
        if (bootstrapTurn && ownerActive.getAsBoolean()) ensureTurn();
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
        long packetId = payload.getLong();
        int fragmentId = payload.getShort();
        int fragmentCount = payload.getShort();
        if (packetId <= 0L) {
            return null;
        }
        if (
            fragmentCount <= 0 ||
            fragmentCount > MAX_FRAGMENTS_PER_PACKET ||
            fragmentId < 0 ||
            fragmentId >= fragmentCount ||
            payload.remaining() > MAX_APPLICATION_FRAGMENT_SIZE
        ) {
            return null;
        }
        return Long.valueOf(packetId);
    }

    AsyncTask<Boolean> write(ByteBuffer data) {
        return write(prepareOutgoingPacket(data));
    }

    AsyncTask<Boolean> write(PreparedPacket packet) {
        return write(packet, () -> true);
    }

    long getWriteGeneration() {
        return writeGeneration.get();
    }

    AsyncTask<Boolean> write(PreparedPacket packet, BooleanSupplier attemptActive) {
        int payloadChunkSize = MAX_APPLICATION_FRAGMENT_SIZE;
        InternalRoutedTransport routed = socket.getRoutedTransport();
        if (!socket.isRTCConnected() && routed != null && routed.shouldUseRoute(this)) {
            int routedFramedLimit = routed.maximumNormalFrameBytes(this);
            payloadChunkSize = Math.min(payloadChunkSize, Math.max(1, routedFramedLimit - PAYLOAD_ENVELOPE_HEADER_SIZE));
        }

        ByteBuffer[] frames = encodePacketFragments(packet, payloadChunkSize);
        AsyncTask<Boolean> chain = AsyncTask.completed(Boolean.TRUE);
        for (ByteBuffer frame : frames) {
            final ByteBuffer framePayload = frame.asReadOnlyBuffer();
            chain =
                chain.compose(ok -> {
                    if (!Boolean.TRUE.equals(ok) || closed || socket.isClosed() || !attemptActive.getAsBoolean()) {
                        return AsyncTask.completed(Boolean.FALSE);
                    }
                    return writeSingleFragment(framePayload);
                });
        }
        return chain;
    }

    /**
     * Returns the maximum application bytes carried by one Payload Envelope fragment.
     */
    public int getMaxFragmentSize() {
        return MAX_APPLICATION_FRAGMENT_SIZE;
    }

    private ByteBuffer[] encodePacketFragments(PreparedPacket packet, int payloadChunkSize) {
        ByteBuffer payload = packet.payload();
        int chunkSize = Math.max(1, payloadChunkSize);
        int totalSize = payload.remaining();
        int fragmentCount = Math.max(1, (totalSize + chunkSize - 1) / chunkSize);
        if (totalSize > MAX_REASSEMBLY_BYTES || fragmentCount > MAX_FRAGMENTS_PER_PACKET) {
            throw new IllegalArgumentException("Packet exceeds fragment reassembly limits");
        }
        ByteBuffer[] frames = new ByteBuffer[fragmentCount];
        for (int fragmentId = 0; fragmentId < fragmentCount; fragmentId++) {
            int fragmentSize = Math.min(chunkSize, payload.remaining());
            ByteBuffer framed = ByteBuffer.allocate(INNER_FRAME_HEADER_SIZE + fragmentSize);
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
        if (socket.canUsePhysicalChannel(name) && isConnected() && !socket.isForceTURN()) {
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
        InternalRoutedTransport routed = socket.getRoutedTransport();
        if (routed != null && routed.shouldUseRoute(this)) {
            return routed.writeRouted(this, payload.asReadOnlyBuffer());
        }
        if (!socket.canUsePhysicalChannel(name)) {
            return AsyncTask.completed(Boolean.FALSE);
        }
        if (socket.isTurnFallbackAllowed() || socket.isForceTURN()) {
            ensureTurn();
        }
        NostrTURNChannel currentTurnSend = this.turnSend;
        if (currentTurnSend != null) {
            long generation = getWriteGeneration();
            long turnGeneration = currentTurnSend.getConnectionGeneration();
            return currentTurnSend
                .write(payload)
                .then(delivered -> {
                    // Reliable TURN completion requires the existing authenticated peer receipt.
                    synchronized (socket) {
                        if (
                            Boolean.TRUE.equals(delivered) &&
                            reliable &&
                            turnSend == currentTurnSend &&
                            generation == getWriteGeneration() &&
                            !closed &&
                            socket.isPhysicalLinkEnabled() &&
                            currentTurnSend.getConnectionGeneration() == turnGeneration
                        ) {
                            confirmedTurnGeneration = turnGeneration;
                            confirmedTurnSend = currentTurnSend;
                        }
                    }
                    return delivered;
                });
        }
        return AsyncTask.completed(Boolean.FALSE);
    }

    boolean isReady() {
        if (closed) {
            return false;
        }
        if (socket.canUsePhysicalChannel(name) && !socket.isForceTURN() && channel != null) {
            return true;
        }
        InternalRoutedTransport routed = socket.getRoutedTransport();
        if (routed != null && routed.isRouteReady(this)) {
            return true;
        }
        if (!socket.canUsePhysicalChannel(name)) {
            return false;
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
        writeGeneration.incrementAndGet();
        synchronized (receivedPacketIdsLock) {
            if (fragmentCleanupTask != null) fragmentCleanupTask.cancel();
            fragmentCleanupTask = null;
            pendingFragments.clear();
            pendingFragmentBytes = 0;
            receivedPacketIds.clear();
        }
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
        if (!socket.isPhysicalLinkEnabled()) {
            return false;
        }
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

    /**
     * Direct bidirectional readiness, without consulting routed delivery.
     */
    boolean isPhysicalReady() {
        if (closed || !socket.isPhysicalLinkEnabled()) return false;
        if (!socket.isForceTURN() && socket.isRTCConnected() && channel != null) return true;
        NostrTURNChannel send = turnSend;
        NostrTURNChannel receive = turnReceive;
        return send != null && receive != null && send.isReady() && receive.isReady();
    }

    /**
     * Replacing a healthy link requires RTC readiness or a confirmed delivery to the TURN peer.
     */
    boolean isReplacementReady() {
        if (!isPhysicalReady()) return false;
        NostrTURNChannel send = turnSend;
        return (
            (!socket.isForceTURN() && socket.isRTCConnected() && channel != null) ||
            (send != null && confirmedTurnSend == send && confirmedTurnGeneration == send.getConnectionGeneration())
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

    boolean onRoutedSocketMessage(ByteBuffer bbf) {
        if (tryExtractPacketId(bbf) == null) {
            return false;
        }
        InboundFragment accepted = acceptIncomingFragment(bbf);
        if (!accepted.accepted) return false;
        ByteBuffer payload = accepted.payload;
        if (payload != null) {
            for (NostrRTCChannelListener listener : listeners) {
                try {
                    listener.onRTCSocketMessage(this, payload.duplicate(), false);
                } catch (Throwable e) {
                    logger.log(Level.SEVERE, "Exception in listener", e);
                }
            }
        }
        // An accepted fragment is acknowledged even when it was a
        // duplicate or is still waiting for other normal NIP-DC fragments.
        return true;
    }

    void onRoutedBroadcastMessage(ByteBuffer payload) {
        for (NostrRTCChannelListener listener : listeners) {
            try {
                listener.onRTCSocketMessage(this, payload.asReadOnlyBuffer(), false);
            } catch (Throwable error) {
                logger.log(Level.SEVERE, "Exception in listener", error);
            }
        }
    }

    private static final class InboundFragment {

        static final InboundFragment REJECTED = new InboundFragment(false, null);
        static final InboundFragment PENDING = new InboundFragment(true, null);
        final boolean accepted;
        final ByteBuffer payload;

        InboundFragment(boolean accepted, ByteBuffer payload) {
            this.accepted = accepted;
            this.payload = payload;
        }
    }

    private ByteBuffer unwrapIncomingPayload(ByteBuffer bbf) {
        return acceptIncomingFragment(bbf).payload;
    }

    private InboundFragment acceptIncomingFragment(ByteBuffer bbf) {
        ByteBuffer payload = bbf.duplicate();
        if (payload.remaining() < INNER_FRAME_HEADER_SIZE) {
            return InboundFragment.REJECTED;
        }
        long packetId = payload.getLong();
        int fragmentId = payload.getShort();
        int fragmentCount = payload.getShort();
        if (packetId <= 0L) {
            return InboundFragment.REJECTED;
        }
        if (
            fragmentCount <= 0 ||
            fragmentCount > MAX_FRAGMENTS_PER_PACKET ||
            fragmentId < 0 ||
            fragmentId >= fragmentCount ||
            payload.remaining() > MAX_APPLICATION_FRAGMENT_SIZE
        ) {
            return InboundFragment.REJECTED;
        }

        synchronized (receivedPacketIdsLock) {
            if (closed) return InboundFragment.REJECTED;
            pruneExpiredPendingFragmentsLocked();
            if (receivedPacketIds.contains(Long.valueOf(packetId))) {
                return InboundFragment.PENDING;
            }

            PendingInboundFragments pending = pendingFragments.get(Long.valueOf(packetId));
            if (pending != null && pending.fragmentCount != fragmentCount) return InboundFragment.REJECTED;
            if (pending == null) {
                if (pendingFragments.size() >= MAX_PENDING_FRAGMENT_PACKETS) return InboundFragment.REJECTED;
                pending = new PendingInboundFragments(fragmentCount);
                pendingFragments.put(Long.valueOf(packetId), pending);
                scheduleFragmentCleanupLocked();
            }
            if (pending.fragments[fragmentId] != null) return InboundFragment.PENDING;
            if (payload.remaining() > MAX_REASSEMBLY_BYTES - pendingFragmentBytes) return InboundFragment.REJECTED;
            int previousBytes = pending.totalBytes;
            ByteBuffer merged = pending.addFragment(fragmentId, payload.slice());
            pendingFragmentBytes += pending.totalBytes - previousBytes;
            if (merged == null) return InboundFragment.PENDING;
            pendingFragments.remove(Long.valueOf(packetId));
            pendingFragmentBytes -= pending.totalBytes;
            recordCompletedPacketIdLocked(packetId);
            if (pendingFragments.isEmpty() && fragmentCleanupTask != null) {
                fragmentCleanupTask.cancel();
                fragmentCleanupTask = null;
            }
            return new InboundFragment(true, merged);
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
        long now = System.currentTimeMillis();
        pendingFragments
            .entrySet()
            .removeIf(entry -> {
                PendingInboundFragments pending = entry.getValue();
                if (!pending.isExpired(now)) return false;
                pendingFragmentBytes -= pending.totalBytes;
                return true;
            });
    }

    private void scheduleFragmentCleanupLocked() {
        if (fragmentCleanupTask != null || closed || pendingFragments.isEmpty()) return;
        long delayMs = FRAGMENT_REASSEMBLY_TIMEOUT_MS;
        long now = System.currentTimeMillis();
        for (PendingInboundFragments pending : pendingFragments.values()) {
            delayMs = Math.min(delayMs, Math.max(1L, pending.createdAtMs + FRAGMENT_REASSEMBLY_TIMEOUT_MS - now));
        }
        fragmentCleanupTask =
            socket.scheduleChannelMaintenance(
                () -> {
                    synchronized (receivedPacketIdsLock) {
                        fragmentCleanupTask = null;
                        pruneExpiredPendingFragmentsLocked();
                        scheduleFragmentCleanupLocked();
                    }
                },
                delayMs
            );
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
        if (socket.isPhysicalLinkEnabled() && socket.isTurnFallbackAllowed()) {
            ensureTurn();
        }
    }

    void disablePhysicalTransports() {
        Runnable cleanup;
        synchronized (socket) {
            cleanup = detachPhysicalTransports();
        }
        cleanup.run();
    }

    /**
     * State-only detach under the socket monitor; the returned action closes captured handles only.
     */
    Runnable detachPhysicalTransports() {
        RTCDataChannel previousChannel = channel;
        updateNativeChannelState(null);
        Runnable turnCleanup = detachTurn();
        return () -> {
            if (previousChannel != null) try {
                previousChannel.close();
            } catch (Throwable error) {
                logger.log(Level.FINE, "Failed to close disabled RTC data channel", error);
            }
            turnCleanup.run();
        };
    }

    private void disposeTurn() {
        Runnable cleanup;
        synchronized (socket) {
            cleanup = detachTurn();
        }
        cleanup.run();
    }

    private void disposeTurnIfOwned(NostrTURNChannel expected) {
        Runnable cleanup;
        synchronized (socket) {
            if (turnReceive != expected && turnSend != expected) return;
            cleanup = detachTurn();
        }
        cleanup.run();
    }

    /**
     * Caller holds the socket monitor. No provider or application code runs here.
     */
    private Runnable detachTurn() {
        NostrTURNChannel receive = turnReceive;
        NostrTURNChannel send = turnSend;
        turnReceive = null;
        turnSend = null;
        confirmedTurnSend = null;
        confirmedTurnGeneration = Long.MIN_VALUE;
        return () -> {
            if (receive != null) receive.close("rtc-p2p-established");
            if (send != null && send != receive) send.close("rtc-p2p-established");
        };
    }

    private void ensureTurn() {
        if (closed || socket.isClosed() || !socket.isPhysicalLinkEnabled()) {
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
                                disposeTurnIfOwned(channel);
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
                            disposeTurnIfOwned(channel);
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
