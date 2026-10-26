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
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS
 * OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION)
 * HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT
 * LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY
 * OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH
 * DAMAGE.
 */
package org.ngengine.nostr4j.rtc;

import static org.junit.Assert.*;

import java.time.Instant;
import org.junit.Test;
import org.ngengine.nostr4j.keypair.NostrKeyPair;
import org.ngengine.nostr4j.rtc.signal.NostrRTCPeer;

public class TestRTCConnectionDiagnosticsIsolation {

    @Test
    public void candidateAndVictimExposeOnlyCopiedMetadataEvenThroughLegacyPeerAccess() {
        NostrKeyPair key = new NostrKeyPair();
        NostrKeyPair room = new NostrKeyPair();
        NostrRTCPeer live = new NostrRTCPeer(
            key.getPublicKey(),
            "test",
            "test:1",
            "current",
            room.getPublicKey(),
            "turn-original",
            3
        );
        NostrRTCPeer victim = new NostrRTCPeer(
            key.getPublicKey(),
            "test",
            "test:1",
            "victim",
            room.getPublicKey(),
            "victim-original",
            3
        );
        Instant originalTime = Instant.parse("2026-10-08T00:00:00Z");
        live.setLastSeen(originalTime);
        victim.setLastSeen(originalTime);
        RTCConnectionDiagnostics.Candidate candidate = new RTCConnectionDiagnostics.Candidate(
            live,
            1f,
            "OPTIONAL",
            "CONNECTING",
            "RTC",
            false,
            false,
            false,
            0,
            0,
            "pending",
            victim
        );
        NostrRTCPeer detached = candidate.getPeer();
        assertNotSame(live, detached);
        assertEquals(live, detached);
        detached.setTurnServer(null);
        detached.setNipDcVersion(4);
        detached.setLastSeen(Instant.EPOCH);
        candidate.getSwapVictim().setTurnServer(null);
        assertEquals("turn-original", live.getTurnServer());
        assertEquals("victim-original", victim.getTurnServer());
        assertEquals(3, live.getNipDcVersion());
        assertEquals(originalTime, live.getLastSeen());
        live.setTurnServer("turn-later");
        live.setNipDcVersion(4);
        live.setLastSeen(Instant.EPOCH);
        victim.setTurnServer("victim-later");
        RTCConnectionDiagnostics.PeerIdentity identity = candidate.getPeerIdentity();
        assertSame(identity, candidate.getPeerIdentity());
        assertEquals(key.getPublicKey().asHex(), identity.getPublicKey());
        assertEquals("turn-original", identity.getTurnServer());
        assertEquals(3, identity.getNipDcVersion());
        assertEquals(originalTime, identity.getLastSeen());
        assertEquals("turn-original", candidate.getPeer().getTurnServer());
        assertEquals("victim-original", candidate.getSwapVictimIdentity().getTurnServer());
        assertEquals("victim-original", candidate.getSwapVictim().getTurnServer());
        assertNotSame(candidate.getPeer(), candidate.getPeer());
    }

    @Test
    public void absentVictimStaysAbsent() {
        NostrKeyPair key = new NostrKeyPair();
        NostrRTCPeer peer = new NostrRTCPeer(key.getPublicKey(), "test", "test:1", "current", key.getPublicKey(), null);
        RTCConnectionDiagnostics.Candidate candidate = new RTCConnectionDiagnostics.Candidate(
            peer,
            null,
            "OPTIONAL",
            "DISCOVERED",
            "NONE",
            false,
            false,
            false,
            0,
            0,
            "idle",
            null
        );
        assertNull(candidate.getSwapVictim());
        assertNull(candidate.getSwapVictimIdentity());
        assertNull(candidate.getPeerIdentity().getTurnServer());
    }
}
