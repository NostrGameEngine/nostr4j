package org.ngengine.nostr4j;

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
    public static final Duration P2P_TIMEOUT = Duration.ofSeconds(4);
    public static final Duration QUEUED_SEND_TIMEOUT = Duration.ofSeconds(30);

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
                    }));

    private final Duration signalingLoopInterval;
    private final Duration peerExpiration;
    private final Duration delayedCandidatesInterval;
    private final Duration roomLoopInterval;
    private final Duration p2pAttemptTimeout;
    private final Duration queuedSendTimeout;

    public RTCSettings(
            Duration announceInterval,
            Duration peerExpiration,
            Duration delayedCandidatesInterval,
            Duration roomLoopInterval,
            Duration p2pAttemptTimeout) {
        this(
                announceInterval,
                peerExpiration,
                delayedCandidatesInterval,
                roomLoopInterval,
                p2pAttemptTimeout,
                QUEUED_SEND_TIMEOUT);
    }

    public RTCSettings(
            Duration announceInterval,
            Duration peerExpiration,
            Duration delayedCandidatesInterval,
            Duration roomLoopInterval,
            Duration p2pAttemptTimeout,
            Duration queuedSendTimeout) {
        this.signalingLoopInterval = announceInterval;
        this.peerExpiration = peerExpiration;
        this.delayedCandidatesInterval = delayedCandidatesInterval;
        this.roomLoopInterval = roomLoopInterval;
        this.p2pAttemptTimeout = p2pAttemptTimeout;
        this.queuedSendTimeout = queuedSendTimeout;
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

    public Duration getQueuedSendTimeout() {
        return queuedSendTimeout;
    }

    public static final RTCSettings DEFAULT = new RTCSettings(
            SIGNALING_LOOP_INTERVAL,
            PEER_EXPIRATION,
            DELAYED_CANDIDATES_INTERVAL,
            ROOM_LOOP_INTERVAL,
            P2P_TIMEOUT,
            QUEUED_SEND_TIMEOUT);

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
        if (this == o)
            return true;
        if (!(o instanceof RTCSettings))
            return false;
        RTCSettings that = (RTCSettings) o;
        return (signalingLoopInterval == that.signalingLoopInterval &&
                peerExpiration == that.peerExpiration &&
                delayedCandidatesInterval == that.delayedCandidatesInterval &&
                roomLoopInterval == that.roomLoopInterval &&
                p2pAttemptTimeout == that.p2pAttemptTimeout &&
                queuedSendTimeout == that.queuedSendTimeout);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                signalingLoopInterval,
                peerExpiration,
                delayedCandidatesInterval,
                roomLoopInterval,
                p2pAttemptTimeout,
                queuedSendTimeout);
    }

    @Override
    public String toString() {
        return ("RTCSettings{" +
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
                ", queuedSendTimeout=" +
                queuedSendTimeout +
                '}');
    }
}
