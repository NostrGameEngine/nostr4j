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

import static org.junit.Assert.*;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.List;
import org.junit.Test;
import org.ngengine.nostr4j.NostrPool;
import org.ngengine.nostr4j.RTCSettings;
import org.ngengine.nostr4j.keypair.NostrKeyPair;
import org.ngengine.nostr4j.rtc.signal.*;
import org.ngengine.nostr4j.signer.NostrKeyPairSigner;
import org.ngengine.platform.NGEUtils;

public class TestNostrRTCLegacyAdmission {

    @Test
    public void existingDc4PeerRemainsEligibleForPhysicalConnections() throws Exception {
        try (NostrKeyPair keys = new NostrKeyPair()) {
            RTCSettings settings = RTCSettings
                .getDefault("legacy-admission-app", "legacy-admission-protocol")
                .withSignalingRelays(List.of())
                .withStunServers(List.of());
            NostrRTCLocalPeer local = new NostrRTCLocalPeer(settings, NostrKeyPairSigner.generate(), "local", keys, null);
            NostrRTCRoom room = new NostrRTCRoom(settings, local, keys, new NostrPool(), null);
            try {
                NostrKeyPairSigner signer = NostrKeyPairSigner.generate();
                NostrRTCPeer peer = new NostrRTCPeer(
                    NGEUtils.awaitNoThrow(signer.getPublicKey()),
                    "legacy-admission-app",
                    "legacy-admission-protocol",
                    "legacy-remote",
                    keys.getPublicKey(),
                    null
                );
                NostrRTCConnectSignal signal = new NostrRTCConnectSignal(signer, keys, peer, Instant.now().plusSeconds(60), "");
                Method add = NostrRTCRoom.class.getDeclaredMethod("onAddAnnounce", NostrRTCConnectSignal.class);
                add.setAccessible(true);
                add.invoke(room, signal);
                assertNotNull(room.getSocket(peer));
                assertEquals(
                    "Existing peer must remain a physical candidate",
                    1,
                    room.getConnectionDiagnostics().getCandidates().size()
                );
            } finally {
                room.close();
            }
        }
    }
}
