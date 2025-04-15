package org.ngengine.nostr4j.rtc.turn;

import java.io.Serializable;
import java.time.Duration;

public final class NostrTURNSettings implements Cloneable, Serializable {

    public static final int CHUNK_LENGTH = 1024;
    public static final Duration PACKET_TIMEOUT = Duration.ofSeconds(10);
    public static final Duration MAX_LATENCY = Duration.ofSeconds(5);
    public static final Duration LOOP_INTERVAL =  Duration.ofSeconds(1);
    public static final int TURN_KIND = 29999;

    private final int chunkLength;
    private final Duration packetTimeout;
    private final Duration maxLatency;
    private final Duration loopInterval;
    private final int kind;

    public NostrTURNSettings(int chunkLength, Duration packetTimeout, Duration maxLatency, 
            Duration loopInterval, int kind) {
        this.chunkLength = chunkLength;
        this.packetTimeout = packetTimeout;
        this.maxLatency = maxLatency;
        this.loopInterval = loopInterval;
        this.kind = kind;
    }

    public int getChunkLength() {
        return chunkLength;
    }

    public Duration getPacketTimeout() {
        return packetTimeout;
    }

    public Duration getMaxLatency() {
        return maxLatency;
    }

    public Duration getLoopInterval() {
        return loopInterval;
    }

    public int getKind() {
        return kind;
    }

    public static final NostrTURNSettings DEFAULT = new NostrTURNSettings(
            CHUNK_LENGTH,
            PACKET_TIMEOUT,
            MAX_LATENCY,
            LOOP_INTERVAL,
            TURN_KIND);


    @Override
    public NostrTURNSettings clone() {
        try {
            return (NostrTURNSettings) super.clone();
        } catch (CloneNotSupportedException e) {
            throw new RuntimeException("Clone not supported", e);
        }
    }

    @Override
    public String toString() {
        return "NostrTURNSettings{" +
                "chunkLength=" + chunkLength +
                ", packetTimeout=" + packetTimeout +
                ", maxLatency=" + maxLatency +
                ", loopInterval=" + loopInterval +
                ", kind=" + kind +
                '}';
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;

        NostrTURNSettings that = (NostrTURNSettings) obj;

        if (chunkLength != that.chunkLength) return false;
        if (packetTimeout != that.packetTimeout) return false;
        if (maxLatency != that.maxLatency) return false;
        if (loopInterval != that.loopInterval) return false;
        return kind == that.kind;
    }

    @Override
    public int hashCode() {
        int result = chunkLength;
        result = 31 * result + (int) (chunkLength ^ (chunkLength >>> 32));
        result = 31 * result + (int) (packetTimeout.toMillis() ^ (packetTimeout.toMillis() >>> 32));
        result = 31 * result + (int) (maxLatency.toMillis() ^ (maxLatency.toMillis() >>> 32));
        result = 31 * result + (int) (loopInterval.toMillis() ^ (loopInterval.toMillis() >>> 32));
        result = 31 * result + kind;
        return result;
    }
}