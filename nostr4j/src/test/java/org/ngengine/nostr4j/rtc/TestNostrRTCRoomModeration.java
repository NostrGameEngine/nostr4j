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

package org.ngengine.nostr4j.rtc;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Map;
import org.junit.Test;
import org.ngengine.nostr4j.NostrPool;
import org.ngengine.nostr4j.RTCSettings;
import org.ngengine.nostr4j.keypair.NostrKeyPair;
import org.ngengine.nostr4j.keypair.NostrPublicKey;
import org.ngengine.nostr4j.rtc.signal.NostrRTCLocalPeer;
import org.ngengine.nostr4j.rtc.signal.NostrRTCOfferSignal;
import org.ngengine.nostr4j.rtc.signal.NostrRTCPeer;
import org.ngengine.nostr4j.signer.NostrKeyPairSigner;

public class TestNostrRTCRoomModeration {

    @Test
    public void banBlocksReconnectOfferSelectionUntilUnban() throws Exception {
        Fixture fixture = new Fixture();
        try {
            assertTrue(shouldOfferConnection(fixture.room, fixture.remotePeer.getPubkey()));

            fixture.room.ban(fixture.remotePeer.getPubkey());
            assertFalse(shouldOfferConnection(fixture.room, fixture.remotePeer.getPubkey()));

            fixture.room.unban(fixture.remotePeer.getPubkey());
            assertTrue(shouldOfferConnection(fixture.room, fixture.remotePeer.getPubkey()));
        } finally {
            fixture.room.close();
        }
    }

    @Test
    public void banRejectsIncomingOfferFromPeerAfterKick() throws Exception {
        Fixture fixture = new Fixture();
        try {
            fixture.room.ban(fixture.remotePeer.getPubkey());

            NostrRTCOfferSignal offer = new NostrRTCOfferSignal(
                fixture.remoteSigner,
                fixture.roomKeyPair,
                fixture.remotePeer,
                "offer:ignored"
            );
            invoke(NostrRTCRoom.class.getDeclaredMethod("onReceiveOffer", NostrRTCOfferSignal.class), fixture.room, offer);

            assertTrue(connections(fixture.room).isEmpty());
        } finally {
            fixture.room.close();
        }
    }

    private static boolean shouldOfferConnection(NostrRTCRoom room, NostrPublicKey peer) throws Exception {
        Method method = NostrRTCRoom.class.getDeclaredMethod("shouldOfferConnection", NostrPublicKey.class);
        return ((Boolean) invoke(method, room, peer)).booleanValue();
    }

    private static Object invoke(Method method, Object target, Object... arguments) throws Exception {
        method.setAccessible(true);
        return method.invoke(target, arguments);
    }

    @SuppressWarnings("unchecked")
    private static Map<NostrRTCPeer, NostrRTCSocket> connections(NostrRTCRoom room) throws Exception {
        Field field = NostrRTCRoom.class.getDeclaredField("connections");
        field.setAccessible(true);
        return (Map<NostrRTCPeer, NostrRTCSocket>) field.get(room);
    }

    private static final class Fixture {

        private final NostrKeyPair roomKeyPair = new NostrKeyPair();
        private final NostrKeyPairSigner remoteSigner;
        private final NostrRTCPeer remotePeer;
        private final NostrRTCRoom room;

        private Fixture() {
            NostrKeyPair localKeyPair = new NostrKeyPair();
            NostrKeyPair remoteKeyPair;
            do {
                remoteKeyPair = new NostrKeyPair();
            } while (localKeyPair.getPublicKey().asHex().compareTo(remoteKeyPair.getPublicKey().asHex()) >= 0);

            NostrKeyPairSigner localSigner = new NostrKeyPairSigner(localKeyPair);
            this.remoteSigner = new NostrKeyPairSigner(remoteKeyPair);
            NostrRTCLocalPeer localPeer = new NostrRTCLocalPeer(
                localSigner,
                Collections.emptyList(),
                "moderation-test-app",
                "moderation-test-protocol",
                "local-session",
                roomKeyPair,
                null
            );
            this.remotePeer =
                new NostrRTCPeer(
                    remoteKeyPair.getPublicKey(),
                    "moderation-test-app",
                    "moderation-test-protocol",
                    "remote-session",
                    roomKeyPair.getPublicKey(),
                    null
                );
            this.room = new NostrRTCRoom(RTCSettings.DEFAULT, localPeer, roomKeyPair, new NostrPool(), null, null);
        }
    }
}
