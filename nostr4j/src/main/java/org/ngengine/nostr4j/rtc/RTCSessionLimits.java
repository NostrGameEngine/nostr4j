/**
 * BSD 3-Clause License
 *
 * Copyright (c) 2026, Riccardo Balbo
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

import java.io.Serializable;
import java.time.Duration;
import java.util.Objects;

/**
 * Bounded authenticated-session admission, independent of physical-neighbor capacity.
 * These limits apply per public key or to provisional signaling, never to player distance.
 */
public final class RTCSessionLimits implements Serializable {

    private static final long serialVersionUID = 1L;
    public static final int MAX_PROVISIONAL_PEERS = 128;
    public static final int MAX_PROVISIONAL_SESSIONS_PER_IDENTITY = 2;
    public static final int MAX_ANNOUNCED_SESSIONS_PER_IDENTITY = 8;
    public static final int MAX_PRESENCE_RECORDS_PER_IDENTITY = 32;
    public static final long PROVISIONAL_LIFETIME_MILLIS = 30_000L;
    public static final int MAX_SESSION_ID_CHARS = 128;
    public static final int MAX_PROVISIONAL_OFFER_CHARS = 65_536;
    public static final int MAX_PROVISIONAL_ROUTES = 4;
    public static final int MAX_PROVISIONAL_ROUTE_CHARS = 65_536;
    public static final int MAX_PROVISIONAL_CANDIDATES_PER_ROUTE = 64;
    public static final int MAX_PROVISIONAL_CANDIDATE_CHARS = 4096;
    public static final int MAX_PROVISIONAL_SDP_MID_CHARS = 256;
    public static final int MAX_PROVISIONAL_TURN_URL_CHARS = 2048;

    private static final RTCSessionLimits DEFAULT = new RTCSessionLimits(
        MAX_PROVISIONAL_PEERS,
        MAX_PROVISIONAL_SESSIONS_PER_IDENTITY,
        MAX_ANNOUNCED_SESSIONS_PER_IDENTITY,
        MAX_PRESENCE_RECORDS_PER_IDENTITY,
        Duration.ofMillis(PROVISIONAL_LIFETIME_MILLIS)
    );
    private final int maxProvisionalPeers;
    private final int maxProvisionalSessionsPerIdentity;
    private final int maxAnnouncedSessionsPerIdentity;
    private final int maxPresenceRecordsPerIdentity;
    private final Duration provisionalLifetime;

    /**
     * Configures local admission quotas without changing the signaling protocol.
     *
     * @param maxProvisionalPeers total sessions awaiting a verified announcement
     * @param maxProvisionalSessionsPerIdentity provisional sessions per public key
     * @param maxAnnouncedSessionsPerIdentity announced sessions per public key
     * @param maxPresenceRecordsPerIdentity presence and replay watermarks per public key;
     *        must be at least the announced-session quota
     * @param provisionalLifetime maximum retention from the first signal, between 1 ms and 1 day
     */
    public RTCSessionLimits(
        int maxProvisionalPeers,
        int maxProvisionalSessionsPerIdentity,
        int maxAnnouncedSessionsPerIdentity,
        int maxPresenceRecordsPerIdentity,
        Duration provisionalLifetime
    ) {
        requirePositive(maxProvisionalPeers, "maxProvisionalPeers");
        requirePositive(maxProvisionalSessionsPerIdentity, "maxProvisionalSessionsPerIdentity");
        requirePositive(maxAnnouncedSessionsPerIdentity, "maxAnnouncedSessionsPerIdentity");
        requirePositive(maxPresenceRecordsPerIdentity, "maxPresenceRecordsPerIdentity");
        if (maxPresenceRecordsPerIdentity < maxAnnouncedSessionsPerIdentity) {
            throw new IllegalArgumentException("Presence-record quota must cover announced sessions");
        }
        Objects.requireNonNull(provisionalLifetime, "provisionalLifetime");
        if (
            provisionalLifetime.isNegative() ||
            provisionalLifetime.compareTo(Duration.ofDays(1)) > 0 ||
            provisionalLifetime.toMillis() < 1L
        ) {
            throw new IllegalArgumentException("provisionalLifetime must be between 1 ms and 1 day");
        }
        this.maxProvisionalPeers = maxProvisionalPeers;
        this.maxProvisionalSessionsPerIdentity = maxProvisionalSessionsPerIdentity;
        this.maxAnnouncedSessionsPerIdentity = maxAnnouncedSessionsPerIdentity;
        this.maxPresenceRecordsPerIdentity = maxPresenceRecordsPerIdentity;
        this.provisionalLifetime = provisionalLifetime;
    }

    private static void requirePositive(int value, String name) {
        if (value < 1) throw new IllegalArgumentException(name + " must be positive");
    }

    public static RTCSessionLimits getDefault() {
        return DEFAULT;
    }

    public int getMaxProvisionalPeers() {
        return maxProvisionalPeers;
    }

    public int getMaxProvisionalSessionsPerIdentity() {
        return maxProvisionalSessionsPerIdentity;
    }

    public int getMaxAnnouncedSessionsPerIdentity() {
        return maxAnnouncedSessionsPerIdentity;
    }

    public int getMaxPresenceRecordsPerIdentity() {
        return maxPresenceRecordsPerIdentity;
    }

    public Duration getProvisionalLifetime() {
        return provisionalLifetime;
    }

    @Override
    public boolean equals(Object value) {
        if (this == value) return true;
        if (!(value instanceof RTCSessionLimits)) return false;
        RTCSessionLimits other = (RTCSessionLimits) value;
        return (
            maxProvisionalPeers == other.maxProvisionalPeers &&
            maxProvisionalSessionsPerIdentity == other.maxProvisionalSessionsPerIdentity &&
            maxAnnouncedSessionsPerIdentity == other.maxAnnouncedSessionsPerIdentity &&
            maxPresenceRecordsPerIdentity == other.maxPresenceRecordsPerIdentity &&
            provisionalLifetime.equals(other.provisionalLifetime)
        );
    }

    @Override
    public int hashCode() {
        return Objects.hash(
            maxProvisionalPeers,
            maxProvisionalSessionsPerIdentity,
            maxAnnouncedSessionsPerIdentity,
            maxPresenceRecordsPerIdentity,
            provisionalLifetime
        );
    }

    public static boolean isValidSessionId(String sessionId) {
        return sessionId != null && !sessionId.isEmpty() && sessionId.length() <= MAX_SESSION_ID_CHARS;
    }
}
