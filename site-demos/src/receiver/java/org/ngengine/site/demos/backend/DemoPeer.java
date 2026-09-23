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

package org.ngengine.site.demos.backend;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.ngengine.nostr4j.NostrPool;
import org.ngengine.nostr4j.NostrRelay;
import org.ngengine.nostr4j.RTCSettings;
import org.ngengine.nostr4j.keypair.NostrKeyPair;
import org.ngengine.nostr4j.keypair.NostrPrivateKey;
import org.ngengine.nostr4j.rtc.NostrRTCRoom;
import org.ngengine.nostr4j.rtc.NostrTURNPool;
import org.ngengine.nostr4j.rtc.signal.NostrRTCLocalPeer;
import org.ngengine.nostr4j.rtc.signal.NostrRTCPeer;
import org.ngengine.nostr4j.signer.NostrKeyPairSigner;
import org.ngengine.platform.NGEPlatform;
import org.ngengine.site.demos.game.IslandPlacer;
import org.ngengine.site.demos.game.WireCodec;
import org.ngengine.site.demos.rtc.PingProtocol;

/** A bounded, disposable JVM peer. No visitor-controlled network destinations. */
final class DemoPeer implements AutoCloseable {

    private final NostrKeyPair identity = new NostrKeyPair();
    private final NostrKeyPair roomKeys;
    private final NostrPool pool = new NostrPool();
    private final NostrTURNPool turns = new NostrTURNPool();
    private final NostrRTCRoom room;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Map<String, Long> lastReply = new ConcurrentHashMap<>();
    private final String name;
    private final int bot;
    private final String seed;
    private final AtomicInteger health = new AtomicInteger(100);
    private volatile PracticeAnchor practiceAnchor;
    private volatile long respawnAtNanos;
    private volatile double lastX, lastY;
    private volatile boolean treasureAvailable = true;
    private ScheduledFuture<?> pulse;
    private ScheduledFuture<?> positionPulse;

    private record PracticeAnchor(double x, double y) {}

    DemoPeer(String seed, List<String> relays, String turnUri, int bot, boolean forceTurn) {
        this.seed = seed;
        this.bot = bot;
        this.name = bot == 0 ? "Cita" : bot >= 3 ? "Practice captain " + (bot - 2) : "Harbor bot " + bot;
        boolean game = bot >= 0;
        roomKeys =
            game
                ? new NostrKeyPair(
                    NostrPrivateKey.fromBytes(
                        NGEPlatform.get().sha256(("sats-seas-room-v1|" + seed).getBytes(StandardCharsets.UTF_8))
                    )
                )
                : PingProtocol.deriveRoomKeys(seed);
        NostrRTCLocalPeer local = new NostrRTCLocalPeer(
            new NostrKeyPairSigner(identity),
            RTCSettings.PUBLIC_STUN_SERVERS,
            game ? "sats-seas" : PingProtocol.APPLICATION_ID,
            game ? "sats-seas-v1" : PingProtocol.PROTOCOL_ID,
            roomKeys,
            turnUri
        );
        for (String url : relays) pool.addRelay(new NostrRelay(url));
        room = new NostrRTCRoom(RTCSettings.DEFAULT.withMaxDirectPeers(2), local, roomKeys, pool, turnUri, turns);
        room.setForceTURN(forceTurn);
        room.addPeerSocketAvailableListener((peer, socket) -> {
            trace("RTC socket available for " + peer.getPubkey().asHex().substring(0, 12));
            room.createChannel(peer, game ? "sats-game" : PingProtocol.CHANNEL);
            if (game) room.createChannel(peer, "sats-pos");
        });
        room.addDisconnectionListener((peer, socket) -> {
            trace("RTC socket closed for " + peer.getPubkey().asHex().substring(0, 12));
            String prefix = peer.getPubkey().asHex() + ":";
            lastReply.keySet().removeIf(key -> key.startsWith(prefix));
        });
        room.addMessageListener((peer, socket, channel, data, turn) -> {
            if (closed.get()) return;
            try {
                if (game && "sats-game".equals(channel.getName())) {
                    for (String frame : WireCodec.decodeAll(data)) onGame(peer, frame);
                } else if (!game && PingProtocol.CHANNEL.equals(channel.getName()) && data.remaining() <= 128) {
                    byte[] bytes = new byte[data.remaining()];
                    data.duplicate().get(bytes);
                    String frame = new String(bytes, StandardCharsets.UTF_8);
                    if (!frame.matches("PING:[0-9]{1,19}") || !allowReply(peer, "ping", 250)) return;
                    long sent = Long.parseLong(frame.substring(5));
                    if (sent <= 0) return;
                    trace("PING received over " + (turn ? "TURN" : "RTC"));
                    room
                        .send(PingProtocol.CHANNEL, peer, bytes(PingProtocol.pongFrame(sent)))
                        .then(ignored -> {
                            trace("PONG sent");
                            return null;
                        })
                        .catchException(error -> trace("PONG send failed: " + error.getClass().getSimpleName()));
                }
            } catch (RuntimeException ignored) {
                /* Malformed remote data is discarded. */
            }
        });
    }

    void start(ScheduledExecutorService scheduler) throws Exception {
        room.start().await();
        trace("room ready");
        pulse =
            scheduler.scheduleWithFixedDelay(
                () -> {
                    if (closed.get()) return;
                    try {
                        if (bot < 0) {
                            // Unicast HELLO also establishes the channel on a newly discovered route.
                            for (NostrRTCPeer peer : room.getPeers()) {
                                room.createChannel(peer, PingProtocol.CHANNEL);
                                room.send(PingProtocol.CHANNEL, peer, bytes(PingProtocol.HELLO));
                            }
                        } else {
                            PracticeAnchor anchor = practiceAnchor;
                            if (bot >= 3 && anchor == null) return;
                            double[] island = bot >= 3 ? practiceIsland(anchor) : sharedIsland(bot);
                            double islandX = island[0], islandY = island[1];
                            int hp = health.get();
                            room.broadcast(
                                "sats-game",
                                encode(
                                    Map.of(
                                        "type",
                                        "hello",
                                        "name",
                                        name,
                                        "pub",
                                        publicKey(),
                                        "island",
                                        Map.of("x", islandX, "y", islandY),
                                        "hp",
                                        hp
                                    )
                                )
                            );
                        }
                    } catch (RuntimeException error) {
                        System.err.println("Demo peer pulse: " + error.getClass().getSimpleName());
                    }
                },
                1,
                3,
                TimeUnit.SECONDS
            );
        if (bot >= 0) {
            positionPulse =
                scheduler.scheduleWithFixedDelay(
                    () -> {
                        if (closed.get()) return;
                        PracticeAnchor anchor = practiceAnchor;
                        if (bot >= 3 && anchor == null) return;
                        long revive = respawnAtNanos;
                        if (health.get() == 0) {
                            if (revive == 0 || System.nanoTime() < revive) return;
                            health.set(100);
                            treasureAvailable = true;
                            respawnAtNanos = 0;
                        }
                        double[] island = bot >= 3 ? practiceIsland(anchor) : sharedIsland(bot);
                        double angle = ((System.currentTimeMillis() / 1000.0) * (bot >= 3 ? 0.9 : 0.42) + bot) % (Math.PI * 2);
                        double centerX = island[0];
                        double centerY = island[1];
                        double orbit = 165;
                        double x = centerX + Math.cos(angle) * orbit;
                        double y = centerY + Math.sin(angle) * orbit;
                        lastX = x;
                        lastY = y;
                        ByteBuffer frame = encode(
                            Map.of(
                                "type",
                                "pos",
                                "x",
                                x,
                                "y",
                                y,
                                "a",
                                (angle + Math.PI / 2) % (Math.PI * 2),
                                "hp",
                                health.get(),
                                "pub",
                                publicKey()
                            )
                        );
                        try {
                            room.broadcast("sats-pos", frame.duplicate());
                        } catch (RuntimeException ignored) {
                            // A room can carry unicast before its full attested mesh is ready.
                            for (NostrRTCPeer peer : room.getPeers()) {
                                try {
                                    room.send("sats-pos", peer, frame.duplicate());
                                } catch (RuntimeException unavailable) {
                                    // The route will be retried on the next pulse.
                                }
                            }
                        }
                    },
                    1,
                    150,
                    TimeUnit.MILLISECONDS
                );
        }
    }

    private boolean allowReply(NostrRTCPeer peer, String type, long interval) {
        String id = peer.getPubkey().asHex() + ":" + type;
        long now = System.nanoTime();
        Long previous = lastReply.get(id);
        if (previous != null && now - previous < TimeUnit.MILLISECONDS.toNanos(interval)) return false;
        if (previous == null && lastReply.size() >= 64) return false;
        lastReply.put(id, now);
        return true;
    }

    private static long islandSeed(String value) {
        int hash = 0x811c9dc5;
        for (int i = 0; i < value.length(); i++) hash = (hash ^ value.charAt(i)) * 0x01000193;
        return Integer.toUnsignedLong(hash);
    }

    private double[] sharedIsland(int index) {
        double[] xs = new double[index], ys = new double[index];
        for (int i = 0; i < index; i++) {
            double[] previous = IslandPlacer.placeIsland(
                islandSeed("bot-island:" + seed + ":" + i),
                java.util.Arrays.copyOf(xs, i),
                java.util.Arrays.copyOf(ys, i),
                4096,
                120,
                760
            );
            xs[i] = previous[0];
            ys[i] = previous[1];
        }
        return IslandPlacer.placeIsland(islandSeed("bot-island:" + seed + ":" + index), xs, ys, 4096, 120, 760);
    }

    private double[] practiceIsland(PracticeAnchor anchor) {
        double[] first = bot == 4 ? placePracticeIsland(anchor, 3, null) : null;
        return placePracticeIsland(anchor, bot, first);
    }

    private double[] placePracticeIsland(PracticeAnchor anchor, int index, double[] first) {
        SplittableRandom random = new SplittableRandom(islandSeed("practice-island:" + seed + ":" + index));
        for (int attempt = 0; attempt < 32; attempt++) {
            double angle = random.nextDouble(0, Math.PI * 2);
            double distance = random.nextDouble(1150, 1500);
            double x = anchor.x() + Math.cos(angle) * distance;
            double y = anchor.y() + Math.sin(angle) * distance;
            if (
                x >= 380 &&
                x <= 3716 &&
                y >= 380 &&
                y <= 3716 &&
                (first == null || Math.hypot(x - first[0], y - first[1]) >= 1000)
            ) return new double[] { x, y };
        }
        double[] xs = first == null ? new double[] { anchor.x() } : new double[] { anchor.x(), first[0] };
        double[] ys = first == null ? new double[] { anchor.y() } : new double[] { anchor.y(), first[1] };
        return IslandPlacer.placeIsland(islandSeed("practice-island:" + seed + ":" + index), xs, ys, 4096, 120, 760);
    }

    private void onGame(NostrRTCPeer peer, String frame) {
        if (frame.length() > 2048) return;
        Map<?, ?> message = NGEPlatform.get().fromJSON(frame, Map.class);
        if (!"ss1".equals(message.get("v"))) return;
        Object type = message.get("type");
        if (
            !(
                "hello".equals(type) ||
                "chat".equals(type) ||
                "probe".equals(type) ||
                "hit".equals(type) ||
                "dig".equals(type) ||
                "claim".equals(type)
            ) ||
            !allowReply(peer, (String) type, "hello".equals(type) ? 15000 : 1000)
        ) return;
        if (bot >= 3 && "hello".equals(type) && practiceAnchor == null && message.get("island") instanceof Map<?, ?> island) {
            Object x = island.get("x");
            Object y = island.get("y");
            if (
                x instanceof Number nx &&
                y instanceof Number ny &&
                nx.doubleValue() >= 0 && nx.doubleValue() <= 4096 && ny.doubleValue() >= 0 && ny.doubleValue() <= 4096
            ) {
                practiceAnchor = new PracticeAnchor(nx.doubleValue(), ny.doubleValue());
            }
        }
        room.createChannel(peer, "sats-game");
        if ("hit".equals(type)) {
            if (publicKey().equals(message.get("target")) && message.get("dmg") instanceof Number damage) {
                int amount = damage.intValue();
                if (amount > 0 && amount <= 30) {
                    int remaining;
                    synchronized (this) {
                        int current = health.get();
                        if (current == 0) return;
                        remaining = Math.max(0, current - amount);
                        health.set(remaining);
                        if (remaining == 0) respawnAtNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
                    }
                    room.send("sats-game", peer, encode(Map.of("type", "hit_ack", "hp", remaining)));
                    if (remaining == 0) {
                        double[] island = bot >= 3 ? practiceIsland(practiceAnchor) : sharedIsland(bot);
                        room.broadcast("sats-game", encode(Map.of("type", "sink", "pub", publicKey(), "x", lastX, "y", lastY)));
                        if (treasureAvailable) {
                            Map<String, Object> drop = Map.of(
                                "type",
                                "scroll",
                                "id",
                                "map-" + publicKey().substring(0, 8) + "-" + Long.toHexString(System.nanoTime()),
                                "x",
                                lastX,
                                "y",
                                lastY,
                                "victim",
                                publicKey(),
                                "victimName",
                                name,
                                "ix",
                                island[0],
                                "iy",
                                island[1]
                            );
                            room.broadcast("sats-game", encode(drop));
                            room.send("sats-game", peer, encode(drop));
                        }
                    }
                }
            }
            return;
        }
        if ("dig".equals(type) || "claim".equals(type)) {
            if (
                !(message.get("nonce") instanceof String nonce) || nonce.length() > 32 || !nonce.matches("[a-zA-Z0-9]+")
            ) return;
            if (!(message.get("x") instanceof Number nx) || !(message.get("y") instanceof Number ny)) return;
            double x = nx.doubleValue(), y = ny.doubleValue();
            if (!Double.isFinite(x) || !Double.isFinite(y) || x < 0 || x > 4096 || y < 0 || y > 4096) return;
            double[] island = bot >= 3 ? practiceIsland(practiceAnchor) : sharedIsland(bot);
            double angle = (islandSeed("treasure:" + seed + ":" + bot) % 6283) / 1000.0;
            double tileX = island[0] + Math.cos(angle) * 100;
            double tileY = island[1] + Math.sin(angle) * 100;
            double distance = Math.hypot(x - tileX, y - tileY);
            if ("dig".equals(type)) {
                double heat = treasureAvailable ? Math.max(0, 1 - distance / 600) : 0;
                room.send("sats-game", peer, encode(Map.of("type", "ping", "heat", heat, "nonce", nonce)));
            } else {
                boolean won;
                synchronized (this) {
                    won = treasureAvailable && distance < 30;
                    if (won) treasureAvailable = false;
                }
                room.send(
                    "sats-game",
                    peer,
                    encode(Map.of("type", "digResult", "ok", won, "sats", won ? 5 : 0, "nonce", nonce))
                );
                if (won) room.broadcast(
                    "sats-game",
                    encode(Map.of("type", "claimed", "islandId", publicKey(), "by", peer.getPubkey().asHex(), "sats", 5))
                );
            }
            return;
        }
        Object probe = message.get("probe");
        if ("probe".equals(type)) {
            if (probe instanceof String value && value.matches("[a-f0-9]{16}")) {
                room.send("sats-game", peer, encode(Map.of("type", "probe_ack", "from", publicKey(), "probe", value)));
            }
            return;
        }
        if (!"chat".equals(type)) return;
        Map<String, Object> reply = new java.util.HashMap<>(
            Map.of("type", "chat", "from", publicKey(), "name", name, "text", "Ahoy!")
        );
        if (probe instanceof String value && value.matches("[a-f0-9]{16}")) reply.put("probe", value);
        room.send("sats-game", peer, encode(reply));
    }

    private static ByteBuffer encode(Map<String, Object> fields) {
        Map<String, Object> message = new java.util.HashMap<>(fields);
        message.put("v", "ss1");
        return WireCodec.encode(NGEPlatform.get().toJSON(message));
    }

    private static ByteBuffer bytes(String value) {
        return ByteBuffer.wrap(value.getBytes(StandardCharsets.UTF_8));
    }

    String publicKey() {
        return identity.getPublicKey().asHex();
    }

    private void trace(String message) {
        if (bot < 0) System.out.println("Ping peer " + publicKey().substring(0, 12) + ": " + message);
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        if (pulse != null) pulse.cancel(false);
        if (positionPulse != null) positionPulse.cancel(false);
        try {
            room.close();
        } finally {
            try {
                turns.close();
            } finally {
                try {
                    pool.close();
                } finally {
                    roomKeys.close();
                    identity.close();
                }
            }
        }
    }
}
