/**
 * BSD 3-Clause License
 *
 * Copyright (c) 2025, Riccardo Balbo
 */
package org.ngengine.nostr4j.rtc.routing;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Fixed-window admission control applied before routed packet parsing or
 * cryptographic work. State is bounded because peer identifiers are attacker
 * controlled.
 */
public final class NeighborTrafficLimiter implements AutoCloseable {

    public static final class Admission implements AutoCloseable {

        private final NeighborTrafficLimiter owner;
        private final boolean control;
        private boolean closed;

        private Admission(NeighborTrafficLimiter owner, boolean control) {
            this.owner = owner;
            this.control = control;
        }

        @Override
        public void close() {
            synchronized (owner) {
                if (closed) return;
                closed = true;
                if (control) owner.controlDecryptionsInFlight--;
            }
        }
    }

    private static final long RATE_WINDOW_MS = 1000L;
    private static final long MALFORMED_WINDOW_MS = 60_000L;

    private final int maxTrackedNeighbors;
    private final int maxControlDecryptions;
    private final int maxPacketsPerSecond;
    private final int maxBytesPerSecond;
    private final int maxMalformedPerMinute;
    private final Map<NodeId, NeighborState> neighbors = new LinkedHashMap<NodeId, NeighborState>(16, 0.75f, true);
    private int controlDecryptionsInFlight;
    private boolean closed;

    public NeighborTrafficLimiter() {
        this(
            RoutingLimits.MAX_TRACKED_DIRECT_NEIGHBORS,
            RoutingLimits.MAX_CONTROL_DECRYPTIONS_IN_FLIGHT,
            RoutingLimits.MAX_PACKETS_PER_SECOND_PER_NEIGHBOR,
            RoutingLimits.MAX_BYTES_PER_SECOND_PER_NEIGHBOR,
            RoutingLimits.MAX_MALFORMED_PACKETS_PER_MINUTE_PER_NEIGHBOR
        );
    }

    NeighborTrafficLimiter(
        int maxTrackedNeighbors,
        int maxControlDecryptions,
        int maxPacketsPerSecond,
        int maxBytesPerSecond,
        int maxMalformedPerMinute
    ) {
        if (
            maxTrackedNeighbors <= 0 ||
            maxControlDecryptions <= 0 ||
            maxPacketsPerSecond <= 0 ||
            maxBytesPerSecond <= 0 ||
            maxMalformedPerMinute <= 0
        ) {
            throw new IllegalArgumentException("Traffic limiter bounds must be positive");
        }
        this.maxTrackedNeighbors = maxTrackedNeighbors;
        this.maxControlDecryptions = maxControlDecryptions;
        this.maxPacketsPerSecond = maxPacketsPerSecond;
        this.maxBytesPerSecond = maxBytesPerSecond;
        this.maxMalformedPerMinute = maxMalformedPerMinute;
    }

    public synchronized Admission tryAcquire(NodeId peer, int bytes, boolean control, long nowMs) {
        Objects.requireNonNull(peer, "peer");
        if (closed || bytes < 0 || bytes > RoutingLimits.MAX_ROUTED_FRAME_BYTES) return null;
        NeighborState state = state(peer, nowMs);
        resetWindows(state, nowMs);
        if (
            state.malformedPackets >= maxMalformedPerMinute ||
            state.packets >= maxPacketsPerSecond ||
            bytes > maxBytesPerSecond - state.bytes ||
            (control && controlDecryptionsInFlight >= maxControlDecryptions)
        ) {
            return null;
        }
        state.packets++;
        state.bytes += bytes;
        if (control) controlDecryptionsInFlight++;
        return new Admission(this, control);
    }

    public synchronized void recordMalformed(NodeId peer, long nowMs) {
        if (closed || peer == null) return;
        NeighborState state = state(peer, nowMs);
        resetWindows(state, nowMs);
        if (state.malformedPackets < maxMalformedPerMinute) {
            state.malformedPackets++;
        }
    }

    synchronized int trackedNeighborCount() {
        return neighbors.size();
    }

    synchronized int controlDecryptionsInFlight() {
        return controlDecryptionsInFlight;
    }

    private NeighborState state(NodeId peer, long nowMs) {
        NeighborState state = neighbors.get(peer);
        if (state != null) return state;
        while (neighbors.size() >= maxTrackedNeighbors) {
            NodeId eldest = neighbors.keySet().iterator().next();
            neighbors.remove(eldest);
        }
        state = new NeighborState(nowMs);
        neighbors.put(peer, state);
        return state;
    }

    private static void resetWindows(NeighborState state, long nowMs) {
        if (nowMs < state.rateWindowStartedMs || nowMs - state.rateWindowStartedMs >= RATE_WINDOW_MS) {
            state.rateWindowStartedMs = nowMs;
            state.packets = 0;
            state.bytes = 0;
        }
        if (nowMs < state.malformedWindowStartedMs || nowMs - state.malformedWindowStartedMs >= MALFORMED_WINDOW_MS) {
            state.malformedWindowStartedMs = nowMs;
            state.malformedPackets = 0;
        }
    }

    @Override
    public synchronized void close() {
        closed = true;
        neighbors.clear();
        controlDecryptionsInFlight = 0;
    }

    private static final class NeighborState {

        private long rateWindowStartedMs;
        private int packets;
        private int bytes;
        private long malformedWindowStartedMs;
        private int malformedPackets;

        private NeighborState(long nowMs) {
            rateWindowStartedMs = nowMs;
            malformedWindowStartedMs = nowMs;
        }
    }
}
