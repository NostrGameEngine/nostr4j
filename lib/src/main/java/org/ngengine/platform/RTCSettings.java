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
package org.ngengine.platform;

import java.io.Serializable;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Objects;

public final class RTCSettings implements Cloneable, Serializable {

    private static final long serialVersionUID = 1L;

    public static final Duration SIGNALING_LOOP_INTERVAL = Duration.ofSeconds(5);
    public static final Duration PEER_EXPIRATION = Duration.ofMinutes(5);
    public static final Duration DELAYED_CANDIDATES_INTERVAL = Duration.ofMillis(100);
    public static final Duration ROOM_LOOP_INTERVAL = Duration.ofSeconds(1);
    public static final Duration P2P_TIMEOUT = Duration.ofSeconds(120);

    public static final Collection<String> PUBLIC_STUN_SERVERS = Collections.unmodifiableCollection(
        Arrays.asList(
            new String[] {
                "stun.cloudflare.com:3478",
                "stun.l.google.com:19302",
                "stun.l.google.com:5349",
                "stun1.l.google.com:3478",
                "stun1.l.google.com:5349",
                "stun2.l.google.com:19302",
                "stun2.l.google.com:5349",
                "stun3.l.google.com:3478",
                "stun3.l.google.com:5349",
                "stun4.l.google.com:19302",
                "stun4.l.google.com:5349",
                "stunserver2024.stunprotocol.org:3478",
            }
        )
    );

    private final Duration signalingLoopInterval;
    private final Duration peerExpiration;
    private final Duration delayedCandidatesInterval;
    private final Duration roomLoopInterval;
    private final Duration p2pAttemptTimeout;

    public RTCSettings(
        Duration announceInterval,
        Duration peerExpiration,
        Duration delayedCandidatesInterval,
        Duration roomLoopInterval,
        Duration p2pAttemptTimeout
    ) {
        this.signalingLoopInterval = announceInterval;
        this.peerExpiration = peerExpiration;
        this.delayedCandidatesInterval = delayedCandidatesInterval;
        this.roomLoopInterval = roomLoopInterval;
        this.p2pAttemptTimeout = p2pAttemptTimeout;
    }

    public Duration getSignalingLoopInterval() {
        return signalingLoopInterval;
    }

    public Duration getPeerExpiration() {
        return peerExpiration;
    }

    public Duration getRoomLoopInterval() {
        return roomLoopInterval;
    }

    public Duration getDelayedCandidatesInterval() {
        return delayedCandidatesInterval;
    }

    public Duration getP2pAttemptTimeout() {
        return p2pAttemptTimeout;
    }

    public static final RTCSettings DEFAULT = new RTCSettings(
        SIGNALING_LOOP_INTERVAL,
        PEER_EXPIRATION,
        DELAYED_CANDIDATES_INTERVAL,
        ROOM_LOOP_INTERVAL,
        P2P_TIMEOUT
    );

    @Override
    public RTCSettings clone() {
        try {
            return (RTCSettings) super.clone();
        } catch (CloneNotSupportedException e) {
            throw new RuntimeException("Clone not supported", e);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof RTCSettings)) return false;
        RTCSettings that = (RTCSettings) o;
        return (
            signalingLoopInterval == that.signalingLoopInterval &&
            peerExpiration == that.peerExpiration &&
            delayedCandidatesInterval == that.delayedCandidatesInterval &&
            roomLoopInterval == that.roomLoopInterval &&
            p2pAttemptTimeout == that.p2pAttemptTimeout
        );
    }

    @Override
    public int hashCode() {
        return Objects.hash(
            signalingLoopInterval,
            peerExpiration,
            delayedCandidatesInterval,
            roomLoopInterval,
            p2pAttemptTimeout
        );
    }

    @Override
    public String toString() {
        return (
            "NostrRTCSettings{" +
            "announceInterval=" +
            signalingLoopInterval +
            ", peerExpiration=" +
            peerExpiration +
            ", delayedCandidatesInterval=" +
            delayedCandidatesInterval +
            ", roomLoopInterval=" +
            roomLoopInterval +
            ", p2pAttemptTimeout=" +
            p2pAttemptTimeout +
            '}'
        );
    }
}
