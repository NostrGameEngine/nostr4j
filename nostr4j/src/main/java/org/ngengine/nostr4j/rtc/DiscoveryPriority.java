/**
 * BSD 3-Clause License
 *
 * Copyright (c) 2025, Riccardo Balbo
 */
package org.ngengine.nostr4j.rtc;

import org.ngengine.nostr4j.rtc.signal.NostrRTCPeer;

/**
 * Local preference for physical neighbors. Finite nonnegative values admit a
 * candidate, including zero; negative values exclude its physical link only.
 * Larger values are preferred within the room's capacity and routing constraints.
 * This callback must be short, nonblocking and must not mutate the room.
 */
@FunctionalInterface
public interface DiscoveryPriority {
    float priorityOf(NostrRTCPeer peer);
}
