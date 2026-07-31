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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import java.nio.ByteBuffer;
import java.util.Arrays;
import org.junit.Test;
import org.ngengine.nostr4j.keypair.NostrPublicKey;
import org.ngengine.platform.NGEUtils;

public class TestNostrTURNRoutingHash {

    private static final NostrPublicKey ROOM = key(0x00);
    private static final NostrPublicKey SOURCE = key(0x20);
    private static final NostrPublicKey TARGET = key(0x40);

    @Test
    public void testCanonicalFixedVector() {
        ByteBuffer hash = compute(
            ROOM,
            "default",
            "alice-session",
            "bob-session",
            "chat-v1",
            "com.example.app",
            SOURCE,
            TARGET
        );

        assertEquals("19d17bf07732fac845698bdbe2d05408793da9fac3d6aa2b88d75a13b64bbf42", NGEUtils.bytesToHex(hash));
        assertEquals(0, hash.position());
        assertEquals(NostrTURNRoutingHash.ROUTING_HASH_SIZE, hash.remaining());
    }

    @Test
    public void testEveryRoutingFieldChangesHash() {
        byte[] baseline = bytes(compute(ROOM, "default", "alice", "bob", "p", "a", SOURCE, TARGET));

        assertDifferent(baseline, compute(key(1), "default", "alice", "bob", "p", "a", SOURCE, TARGET));
        assertDifferent(baseline, compute(ROOM, "other", "alice", "bob", "p", "a", SOURCE, TARGET));
        assertDifferent(baseline, compute(ROOM, "default", "alice2", "bob", "p", "a", SOURCE, TARGET));
        assertDifferent(baseline, compute(ROOM, "default", "alice", "bob2", "p", "a", SOURCE, TARGET));
        assertDifferent(baseline, compute(ROOM, "default", "alice", "bob", "p2", "a", SOURCE, TARGET));
        assertDifferent(baseline, compute(ROOM, "default", "alice", "bob", "p", "a2", SOURCE, TARGET));
        assertDifferent(baseline, compute(ROOM, "default", "alice", "bob", "p", "a", key(0x21), TARGET));
        assertDifferent(baseline, compute(ROOM, "default", "alice", "bob", "p", "a", SOURCE, key(0x41)));
    }

    @Test
    public void testLengthPrefixesRemoveFieldBoundaryAmbiguity() {
        ByteBuffer first = compute(ROOM, "ab", "c", "bob", "p", "a", SOURCE, TARGET);
        ByteBuffer second = compute(ROOM, "a", "bc", "bob", "p", "a", SOURCE, TARGET);

        assertFalse(Arrays.equals(bytes(first), bytes(second)));
    }

    private static ByteBuffer compute(
        NostrPublicKey room,
        String channel,
        String sourceSession,
        String targetSession,
        String protocol,
        String application,
        NostrPublicKey source,
        NostrPublicKey target
    ) {
        return NostrTURNRoutingHash.compute(room, channel, sourceSession, targetSession, protocol, application, source, target);
    }

    private static void assertDifferent(byte[] baseline, ByteBuffer actual) {
        assertFalse(Arrays.equals(baseline, bytes(actual)));
    }

    private static byte[] bytes(ByteBuffer value) {
        ByteBuffer view = value.duplicate();
        byte[] bytes = new byte[view.remaining()];
        view.get(bytes);
        return bytes;
    }

    private static NostrPublicKey key(int firstByte) {
        byte[] bytes = new byte[32];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) (firstByte + i);
        }
        return NostrPublicKey.fromBytes(bytes);
    }
}
