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
package org.ngengine.nostr4j.rtc.turn;

import java.io.Serializable;
import java.time.Duration;

public final class NostrTURNSettings implements Cloneable, Serializable {

    public static final int CHUNK_LENGTH = 1024;
    public static final Duration PACKET_TIMEOUT = Duration.ofSeconds(10);
    public static final Duration MAX_LATENCY = Duration.ofSeconds(5);
    public static final Duration LOOP_INTERVAL = Duration.ofSeconds(1);
    public static final int TURN_KIND = 29999;

    private final int chunkLength;
    private final Duration packetTimeout;
    private final Duration maxLatency;
    private final Duration loopInterval;
    private final int kind;

    public NostrTURNSettings(int chunkLength, Duration packetTimeout, Duration maxLatency, Duration loopInterval, int kind) {
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
        TURN_KIND
    );

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
        return (
            "NostrTURNSettings{" +
            "chunkLength=" +
            chunkLength +
            ", packetTimeout=" +
            packetTimeout +
            ", maxLatency=" +
            maxLatency +
            ", loopInterval=" +
            loopInterval +
            ", kind=" +
            kind +
            '}'
        );
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
