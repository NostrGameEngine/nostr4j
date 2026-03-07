/**
 * BSD 3-Clause License
 *
 * Copyright (c) 2025, Riccardo Balbo
 */
package org.ngengine.nostr4j.rtc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;
import org.ngengine.nostr4j.keypair.NostrKeyPair;
import org.ngengine.nostr4j.rtc.listeners.NostrRTCSocketListener;
import org.ngengine.nostr4j.rtc.signal.NostrRTCLocalPeer;
import org.ngengine.nostr4j.rtc.signal.NostrRTCPeer;
import org.ngengine.nostr4j.signer.NostrKeyPairSigner;
import org.ngengine.platform.AsyncExecutor;
import org.ngengine.platform.NGEUtils;
import org.ngengine.platform.RTCSettings;
import org.ngengine.platform.transport.RTCTransportIceCandidate;

public class TestNostrRTCSocketReadyEmission {

    @Test
    public void testReadyIsEmittedOncePerChannel() throws Exception {
        AsyncExecutor executor = NGEUtils.getPlatform().newAsyncExecutor("ready-emission-test");
        NostrRTCSocket socket = null;
        try {
            NostrKeyPair roomKeyPair = new NostrKeyPair();
            NostrRTCLocalPeer localPeer = new NostrRTCLocalPeer(
                NostrKeyPairSigner.generate(),
                Collections.emptyList(),
                "ready-app",
                "ready-proto",
                roomKeyPair,
                null
            );
            NostrRTCPeer remotePeer = new NostrRTCPeer(
                NGEUtils.awaitNoThrow(NostrKeyPairSigner.generate().getPublicKey()),
                "ready-app",
                "ready-proto",
                "remote-ready-session",
                roomKeyPair.getPublicKey(),
                null
            );
            socket = new NostrRTCSocket(executor, remotePeer, roomKeyPair, localPeer, RTCSettings.DEFAULT, null, null);

            Map<String, Integer> readyCountByChannel = new HashMap<>();
            socket.addListener(
                new NostrRTCSocketListener() {
                    @Override
                    public void onRTCSocketRouteUpdate(
                        NostrRTCSocket socket,
                        java.util.Collection<RTCTransportIceCandidate> candidates,
                        String turnServer
                    ) {}

                    @Override
                    public void onRTCSocketClose(NostrRTCSocket socket) {}

                    @Override
                    public void onRTCChannelReady(NostrRTCChannel channel) {
                        readyCountByChannel.merge(channel.getName(), Integer.valueOf(1), Integer::sum);
                    }
                }
            );

            NostrRTCChannel alpha = socket.createChannel("alpha");
            NostrRTCChannel beta = socket.createChannel("beta");

            Method emitReady = NostrRTCChannel.class.getDeclaredMethod("emitChannelReady");
            emitReady.setAccessible(true);
            emitReady.invoke(alpha);
            emitReady.invoke(beta);
            emitReady.invoke(alpha);
            emitReady.invoke(beta);

            assertEquals("alpha should emit once", Integer.valueOf(1), readyCountByChannel.get("alpha"));
            assertEquals("beta should emit once", Integer.valueOf(1), readyCountByChannel.get("beta"));
            assertEquals("two channels should emit ready", 2, readyCountByChannel.size());
            assertTrue("missing alpha ready callback", readyCountByChannel.containsKey("alpha"));
            assertTrue("missing beta ready callback", readyCountByChannel.containsKey("beta"));
        } finally {
            if (socket != null) {
                socket.close();
            }
            executor.close();
        }
    }
}
