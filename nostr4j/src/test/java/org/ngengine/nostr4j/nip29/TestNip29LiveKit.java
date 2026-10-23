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

package org.ngengine.nostr4j.nip29;

import static org.junit.Assert.*;

import org.junit.Test;
import org.ngengine.nostr4j.event.UnsignedNostrEvent;
import org.ngengine.platform.NGEUtils;

public class TestNip29LiveKit {

    @Test
    public void tokenEndpointUsesTheRelayOriginAndEncodesTheGroupIdentifier() {
        assertEquals(
            "https://relay.test/.well-known/nip29/livekit/room%2Fname",
            Nip29LiveKit.tokenEndpoint("wss://relay.test/some/path", "room/name")
        );
        assertEquals(
            "http://localhost:8080/.well-known/nip29/livekit/room",
            Nip29LiveKit.tokenEndpoint("ws://localhost:8080", "room")
        );
        assertThrows(IllegalArgumentException.class, () -> Nip29LiveKit.tokenEndpoint("ws://remote.test", "room"));
        assertThrows(IllegalArgumentException.class, () -> Nip29LiveKit.tokenEndpoint("wss://user:secret@relay.test", "room"));
        assertThrows(IllegalArgumentException.class, () -> Nip29LiveKit.tokenEndpoint("wss://relay.test", " "));
    }

    @Test
    public void authorizationBindsTheEndpointMethodAndPostBody() {
        String endpoint = Nip29LiveKit.tokenEndpoint("wss://relay.test", "room");
        UnsignedNostrEvent post = Nip29LiveKit.authorization(endpoint, "POST");
        assertEquals(27235, post.getKind());
        assertEquals(endpoint, post.getFirstTagFirstValue("u"));
        assertEquals("POST", post.getFirstTagFirstValue("method"));
        assertEquals(NGEUtils.getPlatform().sha256("{}"), post.getFirstTagFirstValue("payload"));
        assertNull(Nip29LiveKit.authorization(endpoint, "GET").getFirstTag("payload"));
        assertThrows(IllegalArgumentException.class, () -> Nip29LiveKit.authorization(endpoint, "DELETE"));
        assertThrows(IllegalArgumentException.class, () -> Nip29LiveKit.authorization("javascript:bad", "GET"));
    }
}
