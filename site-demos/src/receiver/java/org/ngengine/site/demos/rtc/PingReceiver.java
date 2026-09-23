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

package org.ngengine.site.demos.rtc;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.ngengine.nostr4j.NostrPool;
import org.ngengine.nostr4j.NostrRelay;
import org.ngengine.nostr4j.RTCSettings;
import org.ngengine.nostr4j.keypair.NostrKeyPair;
import org.ngengine.nostr4j.rtc.NostrRTCRoom;
import org.ngengine.nostr4j.rtc.NostrTURNPool;
import org.ngengine.nostr4j.rtc.signal.NostrRTCLocalPeer;
import org.ngengine.nostr4j.rtc.signal.NostrRTCPeer;
import org.ngengine.nostr4j.signer.NostrKeyPairSigner;
import org.ngengine.platform.NGEPlatform;
import org.ngengine.platform.jvm.JVMAsyncPlatform;

/**
 * Demo 3 receiver: plain JVM program, hosted by the site operator (see
 * {@code site-demos/src/main/docker/}).
 *
 * <p>Joins the same public room as the browser demo (room key derived from
 * the same seed, see {@link PingProtocol#deriveRoomKeys}), then:
 * <ul>
 *   <li>broadcasts {@code HELLO} on the control channel every
 *       {@value PingProtocol#HELLO_INTERVAL_MS} ms, so browser demos can
 *       discover it and learn its {@code NostrRTCPeer} identity;</li>
 *   <li>answers every {@code PING:&lt;nanos&gt;} with
 *       {@code PONG:&lt;nanos&gt;}, echoing the sender's timestamp so the
 *       browser can measure round-trip time.</li>
 * </ul>
 *
 * <p>Configuration via environment:
 * <ul>
 *   <li>{@code ROOM_KEY_SEED} — default {@value PingProtocol#DEFAULT_ROOM_KEY_SEED}</li>
 *   <li>{@code RELAYS} — comma-separated signaling relays, default
 *       {@value PingProtocol#DEFAULT_RELAY}</li>
 *   <li>{@code TURN_URI} — TURN server URI, default
 *       {@value PingProtocol#DEFAULT_TURN_URI}</li>
 * </ul>
 *
 * <p>Only needs outbound TCP (relay WebSockets + TURN over
 * {@code wss://}); it listens on no ports.
 */
public final class PingReceiver {

    private static final int MAX_FRAME_BYTES = 128;
    private static final long MIN_PING_INTERVAL_NANOS = TimeUnit.MILLISECONDS.toNanos(250);

    public static void main(String[] args) throws Exception {
        NGEPlatform.set(new JVMAsyncPlatform());

        String seed = requirePrintable(env("ROOM_KEY_SEED", PingProtocol.DEFAULT_ROOM_KEY_SEED), "ROOM_KEY_SEED", 128);
        List<String> relays = splitList(env("RELAYS", PingProtocol.DEFAULT_RELAY));
        if (relays.isEmpty() || relays.size() > 8) throw new IllegalArgumentException("RELAYS must contain 1-8 entries");
        for (int i = 0; i < relays.size(); i++) relays.set(i, requireWssUrl(relays.get(i), "RELAYS"));
        String turnUri = requireWssUrl(env("TURN_URI", PingProtocol.DEFAULT_TURN_URI), "TURN_URI");

        NostrKeyPair roomKeys = PingProtocol.deriveRoomKeys(seed);
        log("room id   : " + roomKeys.getPublicKey().asHex());

        NostrKeyPair identity = new NostrKeyPair();
        log("peer id   : " + identity.getPublicKey().asHex());
        NostrRTCLocalPeer local = new NostrRTCLocalPeer(
            new NostrKeyPairSigner(identity),
            RTCSettings.PUBLIC_STUN_SERVERS,
            PingProtocol.APPLICATION_ID,
            PingProtocol.PROTOCOL_ID,
            roomKeys,
            turnUri
        );

        NostrPool pool = new NostrPool();
        for (String relayUrl : relays) {
            log("signaling relay: " + relayUrl);
            pool.addRelay(new NostrRelay(relayUrl));
        }
        NostrTURNPool turnPool = new NostrTURNPool();
        NostrRTCRoom room = new NostrRTCRoom(RTCSettings.DEFAULT, local, roomKeys, pool, turnUri, turnPool);

        room.addPeerSocketAvailableListener((peer, socket) -> {
            log("peer up: " + shortId(peer));
            room.createChannel(peer, PingProtocol.CHANNEL);
            // Direct HELLO as well: the periodic broadcast skips sockets whose
            // channel is not ready yet, so greet newcomers explicitly.
            sendHello(room, peer);
        });
        ConcurrentHashMap<String, Long> lastPingByPeer = new ConcurrentHashMap<>();
        room.addDisconnectionListener((peer, socket) -> {
            lastPingByPeer.remove(peer.getPubkey().asHex());
            log("peer down: " + shortId(peer));
        });
        room.addMessageListener((peer, socket, channel, bbf, turn) -> {
            if (!PingProtocol.CHANNEL.equals(channel.getName())) return;
            if (bbf.remaining() <= 0 || bbf.remaining() > MAX_FRAME_BYTES) return;
            String msg = decode(bbf);
            if (msg.startsWith(PingProtocol.PING + ":")) {
                long sentNanos = parseLong(msg.substring(PingProtocol.PING.length() + 1));
                if (sentNanos <= 0) return;
                String peerId = peer.getPubkey().asHex();
                long now = System.nanoTime();
                Long previous = lastPingByPeer.put(peerId, now);
                if (previous != null && now - previous < MIN_PING_INTERVAL_NANOS) return;
                room
                    .send(
                        PingProtocol.CHANNEL,
                        peer,
                        ByteBuffer.wrap(PingProtocol.pongFrame(sentNanos).getBytes(StandardCharsets.UTF_8))
                    )
                    .catchException(err -> log("PONG send failed: " + err));
                log("PING from " + shortId(peer) + " via " + (turn ? "TURN" : "direct WebRTC") + " → PONG");
            }
        });

        room.start().await();
        log("room started, broadcasting HELLO every " + PingProtocol.HELLO_INTERVAL_MS + " ms");

        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ping-hello");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(
            () ->
                room
                    .broadcast(PingProtocol.CHANNEL, ByteBuffer.wrap(PingProtocol.HELLO.getBytes(StandardCharsets.UTF_8)))
                    .catchException(err -> log("HELLO broadcast failed: " + err)),
            0L,
            PingProtocol.HELLO_INTERVAL_MS,
            TimeUnit.MILLISECONDS
        );

        Runtime
            .getRuntime()
            .addShutdownHook(
                new Thread(
                    () -> {
                        log("shutting down");
                        scheduler.shutdownNow();
                        try {
                            room.close();
                        } catch (Exception ignored) {}
                        try {
                            turnPool.close();
                        } catch (Exception ignored) {}
                        try {
                            pool.clean();
                        } catch (Exception ignored) {}
                        try {
                            roomKeys.close();
                        } catch (Exception ignored) {}
                        try {
                            identity.close();
                        } catch (Exception ignored) {}
                    },
                    "ping-receiver-shutdown"
                )
            );

        new CountDownLatch(1).await();
    }

    private static void sendHello(NostrRTCRoom room, NostrRTCPeer peer) {
        room
            .send(PingProtocol.CHANNEL, peer, ByteBuffer.wrap(PingProtocol.HELLO.getBytes(StandardCharsets.UTF_8)))
            .catchException(err -> log("direct HELLO failed: " + err));
    }

    private static String env(String name, String def) {
        String v = System.getenv(name);
        return v == null || v.isEmpty() ? def : v;
    }

    private static List<String> splitList(String csv) {
        List<String> out = new ArrayList<>();
        for (String part : csv.split(",")) {
            String t = part.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    private static String requirePrintable(String value, String label, int maxLength) {
        String checked = value == null ? "" : value.trim();
        if (checked.isEmpty() || checked.length() > maxLength) {
            throw new IllegalArgumentException(label + " has an invalid length");
        }
        for (int i = 0; i < checked.length(); i++) {
            if (Character.isISOControl(checked.charAt(i))) throw new IllegalArgumentException(
                label + " contains control characters"
            );
        }
        return checked;
    }

    private static String requireWssUrl(String value, String label) {
        String url = requirePrintable(value, label, 2048);
        try {
            URI parsed = new URI(url);
            if (
                !"wss".equalsIgnoreCase(parsed.getScheme()) ||
                parsed.getHost() == null ||
                parsed.getUserInfo() != null ||
                parsed.getFragment() != null
            ) throw new IllegalArgumentException(label + " must be a wss:// URL without credentials or a fragment");
            return parsed.toASCIIString();
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException(label + " is not a valid URL");
        }
    }

    private static String shortId(NostrRTCPeer peer) {
        try {
            String hex = peer.getPubkey().asHex();
            return hex.substring(0, Math.min(12, hex.length()));
        } catch (Exception e) {
            return "?";
        }
    }

    private static String decode(ByteBuffer bbf) {
        ByteBuffer dup = bbf.duplicate();
        byte[] bytes = new byte[dup.remaining()];
        dup.get(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static long parseLong(String s) {
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return -1L;
        }
    }

    private static void log(String msg) {
        System.out.println("[ping-receiver] " + msg);
    }
}
