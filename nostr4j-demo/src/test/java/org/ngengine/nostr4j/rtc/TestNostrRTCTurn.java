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
import java.time.Duration;
import java.util.Collection;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.ngengine.nostr4j.NostrPool;
import org.ngengine.nostr4j.NostrRelay;
import org.ngengine.nostr4j.TestLogger;
import org.ngengine.nostr4j.keypair.NostrKeyPair;
import org.ngengine.nostr4j.keypair.NostrPrivateKey;
import org.ngengine.nostr4j.rtc.listeners.NostrRTCSocketListener;
import org.ngengine.nostr4j.rtc.signal.NostrRTCLocalPeer;
import org.ngengine.nostr4j.rtc.signal.NostrRTCPeer;
import org.ngengine.nostr4j.rtc.turn.NostrTURNPool;
import org.ngengine.nostr4j.signer.NostrKeyPairSigner;
import org.ngengine.platform.RTCSettings;
import org.ngengine.platform.transport.RTCTransportIceCandidate;

public class TestNostrRTCTurn {

    private static final Logger logger = TestLogger.getRoot(Level.INFO);
    private static final String APPLICATION_ID = "nostr4j-rtc-test";
    private static final String PROTOCOL_ID = "test-protocol";

    private static void newPeer(
        NostrPool pool,
        NostrTURNPool turnPool,
        String name,
        NostrKeyPair localKeyPair,
        NostrKeyPair roomKeyPair
    ) throws Exception {
        // NostrPool pool = new NostrPool();
        // pool.connectRelay(new NostrRelay("wss://nostr.rblb.it"));
        // pool.connectRelay(new NostrRelay("wss://relay.ngengine.org"));
        // pool.connectRelay(new NostrRelay("wss://relay2.ngengine.org"));

        // turn server, used for fallback when direct p2p connection fails.
        String turn = "ws://127.0.0.1:8081/turn";

        // stun servers, used to fetch our public IP
        Collection<String> stun = RTCSettings.PUBLIC_STUN_SERVERS;

        // signer used to sign signaling events, can be the user own signer
        // or a disposable one
        NostrKeyPairSigner signer = new NostrKeyPairSigner(localKeyPair);

        // local rtc peer, this will be used to identify us
        NostrRTCLocalPeer localPeer = new NostrRTCLocalPeer(signer, stun, APPLICATION_ID, PROTOCOL_ID, roomKeyPair, turn);

        // room
        NostrRTCRoom room = new NostrRTCRoom(RTCSettings.DEFAULT, localPeer, roomKeyPair, pool, turn, turnPool);
        room.setForceTURN(true);

        room.addPeerSocketAvailableListener((peerKey, socket) -> {
            System.out.println(name + " peer connected: " + peerKey);
           
            room.send("channel1", peerKey, ByteBuffer.wrap(("Hello " + peerKey.getPubkey().asHex()).getBytes()));
        });
         room.addMessageListener(
            (NostrRTCPeer peer, NostrRTCSocket sk, NostrRTCChannel channel, ByteBuffer bbf, boolean isTurn) -> {
                byte[] bb = new byte[bbf.limit()];
                bbf.get(bb);
                String msg = new String(bb);
                int n = 0;
                try {
                    n = Integer.parseInt(msg.split(":")[1]);
                } catch (Exception e) {
                }
                n++;
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                }

                channel.write(ByteBuffer.wrap(("Hello back from " + name + ":" + n).getBytes()));
                System.out.println(name + " incoming message: " + new String(bb) + " p2p:" + !isTurn+" on channel: " + channel.getName());
            });

        room.addDisconnectionListener((peerKey, socket) -> {
            System.out.println(name + " peer disconnected: " + peerKey);
        });

        // beging rtc handling
        room.start();
    }

    public static void main(String[] args) throws Exception {
        NostrKeyPair localKeyPair = new NostrKeyPair();

        // Shared room secret, used to make peers discover eachother
        NostrKeyPair roomKeyPair = new NostrKeyPair(
            NostrPrivateKey.fromHex("9a2ebe445818e9cc41b4308c90fc62edcbbcfc9a2b3155e1359823b54de21d96")
        );

        System.out.println("Starting peer: " + localKeyPair.getPublicKey());
        System.out.println("On room: " + roomKeyPair.getPublicKey());

        // A nostr pool abstract relay connections and subscriptions management
        // for simplicity we always use a pool even for a single relay connection
        NostrPool pool = new NostrPool();
        
        pool.addRelay(new NostrRelay("wss://nostr.rblb.it"));
        pool.addRelay(new NostrRelay("wss://relay.ngengine.org"));
        pool.addRelay(new NostrRelay("wss://relay2.ngengine.org"));

        // Nostr TURN pool is used to manage TURN connections and
        // multiplexing
        NostrTURNPool turnPool = new NostrTURNPool();

        newPeer(pool, turnPool, "peer" + Math.floor(Math.random() * 10), localKeyPair, roomKeyPair);

        while (true) {
            Thread.sleep(1000);
        }
    }
}
