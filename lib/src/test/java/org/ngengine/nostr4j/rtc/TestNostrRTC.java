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

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.HashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.ngengine.nostr4j.NostrPool;
import org.ngengine.nostr4j.NostrRelay;
import org.ngengine.nostr4j.TestLogger;
import org.ngengine.nostr4j.keypair.NostrKeyPair;
import org.ngengine.nostr4j.keypair.NostrPrivateKey;
import org.ngengine.nostr4j.rtc.signal.NostrRTCLocalPeer;
import org.ngengine.nostr4j.rtc.turn.NostrTURNSettings;
import org.ngengine.nostr4j.signer.NostrKeyPairSigner;

public class TestNostrRTC {

    private static final Logger logger = TestLogger.getRoot(Level.FINEST);

    private static void newPeer(String name, NostrKeyPair localKeyPair, NostrKeyPair roomKeyPair) throws Exception {
        NostrPool pool = new NostrPool();
        pool.connectRelay(new NostrRelay("wss://nostr.rblb.it"));

        NostrKeyPairSigner signer = new NostrKeyPairSigner(localKeyPair);

        NostrRTCLocalPeer localPeer = new NostrRTCLocalPeer(
            signer,
            NostrRTCSettings.PUBLIC_STUN_SERVERS,
            "wss://nostr.rblb.it:7777",
            new HashMap<>()
        );

        NostrRTCRoom room = new NostrRTCRoom(NostrRTCSettings.DEFAULT, NostrTURNSettings.DEFAULT, localPeer, roomKeyPair, pool);
        room.onConnection((peerKey, socket) -> {
            logger.info(name + " peer connected: " + peerKey);
        });
        room.onDisconnection((peerKey, socket) -> {
            System.out.println(name + " peer disconnected: " + peerKey);
        });
        room.onMessage((peerKey, socket, bbf, turn) -> {
            byte[] bb = new byte[bbf.limit()];
            bbf.get(bb);
            System.out.println(name + " incoming message: " + new String(bb) + " p2p:" + !turn);
        });
        room.start();
        // room.close();

    }

    static void loopSend(NostrRTCSocket socket) {
        new Thread(() -> {
            try {
                while (true) {
                    Thread.sleep(1000);
                    socket.write(ByteBuffer.wrap(("hello").getBytes()));
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        })
            .start();
    }

    public static void main(String[] args) throws Exception {
        NostrKeyPair localKeyPair = new NostrKeyPair();
        NostrKeyPair roomKeyPair = new NostrKeyPair(
            NostrPrivateKey.fromHex("9a2ebe445818e9cc41b4308c90fc62edcbbcfc9a2b3155e1359823b54de21d96")
        );

        System.out.println("Starting peer: " + localKeyPair.getPublicKey());
        System.out.println("On room: " + roomKeyPair.getPublicKey());

        newPeer("peer" + Math.floor(Math.random() * 10), localKeyPair, roomKeyPair);

        while (true) {
            Thread.sleep(1000);
        }
    }
}
