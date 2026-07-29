/**
 * BSD 3-Clause License
 *
 * Copyright (c) 2025, Riccardo Balbo
 */
package org.ngengine.nostr4j.rtc.routing;

public final class RoutingLimits {

    public static final int MAX_DIRECT_PEERS = 64;
    public static final int MAX_TOPOLOGY_EVENT_BYTES = 64 * 1024;
    public static final int MAX_TOPOLOGY_NEIGHBORS = MAX_DIRECT_PEERS;
    public static final int MAX_TOPOLOGY_SNAPSHOTS = 2048;
    public static final int MAX_TOPOLOGY_LIFETIME_SECONDS = 300;
    public static final int MAX_ROUTE_HOPS = 16;
    public static final int MAX_ROUTE_CANDIDATES = 4;
    public static final int MAX_FAILED_ROUTE_PENALTIES = 4096;
    public static final int MAX_ROUTE_SETUP_BYTES = 64 * 1024;
    public static final int MAX_ROUTED_FRAME_BYTES = 1024 * 1024;
    public static final int MAX_ACTIVE_CIRCUITS = 4096;
    public static final int MAX_ACTIVE_CIRCUITS_PER_NEIGHBOR = 256;
    public static final int MAX_CIRCUIT_LIFETIME_SECONDS = 120;
    public static final int MAX_DEDUP_ENTRIES = 8192;
    public static final int MAX_BROADCAST_TRACKERS = 1024;
    public static final int MAX_TRACKED_DIRECT_NEIGHBORS = 128;
    public static final int MAX_CONTROL_DECRYPTIONS_IN_FLIGHT = 32;
    public static final int MAX_PACKETS_PER_SECOND_PER_NEIGHBOR = 2048;
    public static final int MAX_BYTES_PER_SECOND_PER_NEIGHBOR = 8 * 1024 * 1024;
    public static final int MAX_MALFORMED_PACKETS_PER_MINUTE_PER_NEIGHBOR = 128;

    private RoutingLimits() {}
}
