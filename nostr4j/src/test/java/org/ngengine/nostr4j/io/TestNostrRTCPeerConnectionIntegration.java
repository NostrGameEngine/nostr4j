/* BSD 3-Clause License - Copyright (c) 2026, Riccardo Balbo */
package org.ngengine.nostr4j.io;

import static org.junit.Assert.*;

import java.net.ServerSocket;
import java.util.List;
import java.util.concurrent.*;
import org.junit.Test;
import org.ngengine.nostr4j.RTCSettings;
import org.ngengine.nostr4j.TestEnvironment;
import org.ngengine.nostr4j.keypair.NostrKeyPair;
import org.ngengine.nostr4j.signer.NostrKeyPairSigner;
import org.ngengine.nostr4j.turn.ref.TurnServer;

/** Exercises real relay signaling, WebRTC, room derivation and stream framing together. */
public class TestNostrRTCPeerConnectionIntegration {

    @Test(timeout = 90000)
    public void connectsUsingOnlyPeerIdsAndExchangesStreams() throws Exception {
        RTCSettings settings = RTCSettings.getDefault("org.ngengine.nostr4j.peer-stream", "byte-stream-v3")
            .withStunServers(List.of()).withSignalingRelays(List.of(TestEnvironment.relayUrl()));
        exchange(settings, null);
    }

    @Test(timeout = 90000)
    public void exchangesStreamsWithConfiguredTurnServer() throws Exception {
        try (NostrKeyPair serverIdentity = new NostrKeyPair()) {
            int port;
            try (ServerSocket reservation = new ServerSocket(0)) {
                port = reservation.getLocalPort();
            }
            TurnServer server = new TurnServer(port, new NostrKeyPairSigner(serverIdentity), 4, 30);
            server.start();
            try {
                RTCSettings settings = RTCSettings.getDefault("org.ngengine.nostr4j.peer-stream", "byte-stream-v3")
                    .withStunServers(List.of()).withSignalingRelays(List.of(TestEnvironment.relayUrl()));
                exchange(settings, "ws://127.0.0.1:" + server.getPort() + "/turn");
            } finally {
                server.stop();
            }
        }
    }

    private static void exchange(RTCSettings settings, String turnUrl) throws Exception {
        ExecutorService workers = Executors.newCachedThreadPool();
        try (
            NostrRTCPeerConnection a = new NostrRTCPeerConnection(
                settings, "integration-stream", turnUrl, 1024, 16
            );
            NostrRTCPeerConnection b = new NostrRTCPeerConnection(
                settings, "integration-stream", turnUrl, 1024, 16
            )
        ) {
            Future<?> connectA = workers.submit(() -> {
                a.connect(b.getPeerId());
                return null;
            });
            Future<?> connectB = workers.submit(() -> {
                b.connect(a.getPeerId());
                return null;
            });
            connectA.get(45, TimeUnit.SECONDS);
            connectB.get(45, TimeUnit.SECONDS);
            b.getOutputStream().write(new byte[] { 1, 2, 3 });
            assertArrayEquals(new byte[] { 1, 2, 3 }, a.getInputStream().readNBytes(3));
            byte[] large = new byte[200000];
            for (int i = 0; i < large.length; i++) large[i] = (byte) (i * 7);
            Future<?> write = workers.submit(() -> {
                a.getOutputStream().write(255);
                a.getOutputStream().write(large);
                a.getOutputStream().close();
                return null;
            });
            assertEquals(255, b.getInputStream().read());
            assertArrayEquals(large, b.getInputStream().readAllBytes());
            write.get(25, TimeUnit.SECONDS);
            assertThrows(java.io.IOException.class, () -> b.getOutputStream().write(4));
        } finally {
            workers.shutdownNow();
        }
    }
}
