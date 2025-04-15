package org.ngengine.nostr4j.rtc;

import java.io.Serializable;
import java.time.Duration;
import java.util.Arrays;
import java.util.Objects;

public final class NostrRTCSettings implements Cloneable, Serializable {

    private static final long serialVersionUID = 1L;

  
    public static final Duration ANNOUNCE_INTERVAL = Duration.ofSeconds(10);
    public static final Duration PEER_EXPIRATION = Duration.ofMinutes(5);
    public static final Duration GC_INTERVAL = Duration.ofMinutes(1);
    public static final int KIND = 29999;
    public static final Duration CONNECTING_TIMEOUT = Duration.ofMinutes(2);
    public static final Duration P2P_TIMEOUT = Duration.ofSeconds(60);

    public static final String[] PUBLIC_STUN_SERVERS = {
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
            "stunserver2024.stunprotocol.org:3478"
    };

    private final Duration announceInterval;
    private final Duration peerExpiration;
    private final Duration gcInterval;
    private final int kind;
    private final Duration connectionAttemptTimeout;
    private final Duration p2pAttemptTimeout;

    public NostrRTCSettings(
            Duration announceInterval, 
            Duration peerExpiration, 
            Duration gcInterval, int kind,
            Duration connectionAttemptTimeout, Duration p2pAttemptTimeout) {
        this.announceInterval = announceInterval;
        this.peerExpiration = peerExpiration;
        this.gcInterval = gcInterval;
        this.kind = kind;
        this.connectionAttemptTimeout = connectionAttemptTimeout;
        this.p2pAttemptTimeout = p2pAttemptTimeout;
    }

    public Duration getAnnounceInterval() {
        return announceInterval;
    }

    public Duration getPeerExpiration() {
        return peerExpiration;
    }

    public Duration getGcInterval() {
        return gcInterval;
    }

    public int getKind() {
        return kind;
    }

    public Duration getConnectionAttemptTimeout() {
        return connectionAttemptTimeout;
    }

    public Duration getP2pAttemptTimeout() {
        return p2pAttemptTimeout;
    }

    public static final NostrRTCSettings DEFAULT = new NostrRTCSettings(
            ANNOUNCE_INTERVAL,
            PEER_EXPIRATION,
            GC_INTERVAL,
            KIND,
            CONNECTING_TIMEOUT,
            P2P_TIMEOUT);

    @Override
    public NostrRTCSettings clone() {
        try {
            return (NostrRTCSettings) super.clone();
        } catch (CloneNotSupportedException e) {
            throw new RuntimeException("Clone not supported", e);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (!(o instanceof NostrRTCSettings))
            return false;
        NostrRTCSettings that = (NostrRTCSettings) o;
        return announceInterval == that.announceInterval &&
                peerExpiration == that.peerExpiration &&
                gcInterval == that.gcInterval &&
                kind == that.kind &&
                connectionAttemptTimeout == that.connectionAttemptTimeout &&
                p2pAttemptTimeout == that.p2pAttemptTimeout;
    }

    @Override
    public int hashCode() {
        return Objects.hash(announceInterval, peerExpiration, gcInterval, kind, connectionAttemptTimeout,
                p2pAttemptTimeout);
    }

    @Override
    public String toString() {
        return "NostrRTCSettings{" +
                "announceInterval=" + announceInterval +
                ", peerExpiration=" + peerExpiration +
                ", gcInterval=" + gcInterval +
                ", kind=" + kind +
                ", connectionAttemptTimeout=" + connectionAttemptTimeout +
                ", p2pAttemptTimeout=" + p2pAttemptTimeout +
                '}';
    }
}