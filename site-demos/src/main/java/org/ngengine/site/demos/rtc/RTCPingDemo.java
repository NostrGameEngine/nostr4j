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

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.ngengine.nostr4j.NostrPool;
import org.ngengine.nostr4j.NostrRelay;
import org.ngengine.nostr4j.RTCSettings;
import org.ngengine.nostr4j.keypair.NostrKeyPair;
import org.ngengine.nostr4j.rtc.NostrRTCChannel;
import org.ngengine.nostr4j.rtc.NostrRTCRoom;
import org.ngengine.nostr4j.rtc.NostrRTCSocket;
import org.ngengine.nostr4j.rtc.NostrTURNPool;
import org.ngengine.nostr4j.rtc.listeners.NostrRTCSocketListener;
import org.ngengine.nostr4j.rtc.signal.NostrRTCLocalPeer;
import org.ngengine.nostr4j.rtc.signal.NostrRTCPeer;
import org.ngengine.nostr4j.signer.NostrKeyPairSigner;
import org.ngengine.platform.NGEPlatform;
import org.ngengine.platform.NGEUtils;
import org.ngengine.platform.teavm.TeaVMPlatform;
import org.ngengine.site.demos.JsBridge;
import org.teavm.jso.JSBody;
import org.teavm.jso.JSFunctor;
import org.teavm.jso.JSObject;

/**
 * Demo 3: RTC ping/pong. Real Java compiled to JavaScript with TeaVM, running
 * 100% in the browser.
 *
 * <p>Installs {@code window.RTCDemo} with:
 * <ul>
 *   <li>{@code start(configJson, onLog, onEvent, onError)} — joins the room
 *       derived from {@code configJson.roomKeySeed} (default
 *       {@value PingProtocol#DEFAULT_ROOM_KEY_SEED}), over the given
 *       {@code relays} (default {@value PingProtocol#DEFAULT_RELAY}),
 *       in {@code mode} {@code "auto"} | {@code "direct"} | {@code "turn"}.</li>
 *   <li>{@code ping()} — sends a PING with a nano timestamp to the receiver.</li>
 *   <li>{@code stop()} — leaves the room and releases everything.</li>
 * </ul>
 *
 * <p>Events delivered to {@code onEvent} as JSON strings:
 * <ul>
 *   <li>{@code {"t":"peer","id":"…","state":"up"}} — a peer's socket is up.</li>
 *   <li>{@code {"t":"peer","id":"…","state":"down"}} — it disconnected.</li>
 *   <li>{@code {"t":"receiver","id":"…"}} — the receiver's HELLO was seen;
 *       its peer object is now the ping target.</li>
 *   <li>{@code {"t":"pong","rttMs":123,"viaTurn":false}} — a PONG arrived;
 *       {@code viaTurn} is the per-message {@code turn} flag observed by
 *       nostr4j for that exact message.</li>
 *   <li>{@code {"t":"transport","peer":"…","from":"NONE","to":"TURN","reason":"…"}} —
 *       from {@code NostrRTCSocketListener.onRTCSocketTransportSwitch}.</li>
 * </ul>
 *
 * <h2>Transport reporting (honesty contract)</h2>
 * <p>The card must only ever state the transport that was <em>observed</em>:
 * the per-message {@code turn} flag on each received PONG plus the
 * transport-switch events. "direct" mode only refrains from configuring TURN
 * (so no TURN fallback is possible); it cannot prove a negative — if nostr4j
 * ever reported {@code viaTurn:true} there, the card shows it.
 */
public final class RTCPingDemo {

    @JSBody(params = {}, script = "return (typeof window !== 'undefined');")
    static native boolean hasWindow();

    /**
     * Log/event/error callback. Must be {@code @JSFunctor} <em>and</em> extend
     * {@code JSObject}: that combination is the only one TeaVM 0.15 compiles
     * into a working two-way bridge — a plain {@code @JSFunctor} makes Java
     * emit a Java-style virtual call on the plain {@code {call: fn}} wrapper
     * (runtime {@code TypeError}), and a plain {@code JSObject} is never
     * converted to a callable at all. Defined locally on purpose so this demo
     * does not depend on concurrently-evolving shared bridge types.
     */
    @JSFunctor
    public interface StringCallback extends JSObject {
        void call(String message);
    }

    /** start(configJson, onLog, onEvent, onError). Must extend JSObject: that is what makes TeaVM compile the lambda into a real JS function. */
    @JSFunctor
    public interface StartFn extends JSObject {
        void call(String configJson, StringCallback onLog, StringCallback onEvent, StringCallback onError);
    }

    /** Zero-arg functor for ping/stop (same JSObject rule as above). */
    @JSFunctor
    public interface ActionFn extends JSObject {
        void call();
    }

    @JSBody(params = { "obj", "key", "fn" }, script = "obj[key] = fn;")
    static native void setStartFn(JSObject obj, String key, StartFn fn);

    @JSBody(params = { "obj", "key", "fn" }, script = "obj[key] = fn;")
    static native void setActionFn(JSObject obj, String key, ActionFn fn);

    public static void main(String[] args) {
        NGEPlatform.set(new TeaVMPlatform());
        if (!hasWindow()) {
            // Node smoke test: platform is up, but there is no DOM — install nothing.
            return;
        }
        Session session = new Session();
        JSObject api = JsBridge.newObject();
        // NB: the start functor itself must not suspend: Session.start()
        // awaits async results (signer keys, room startup), and awaiting
        // directly inside a JS->Java call throws TeaVM's "Suspension point
        // reached from non-threading context". Hop onto the platform executor
        // first; the functor stays non-suspending.
        setStartFn(
            api,
            "start",
            (configJson, onLog, onEvent, onError) ->
                NGEUtils
                    .getPlatform()
                    .newAsyncExecutor()
                    .run(() -> {
                        session.start(configJson, onLog, onEvent, onError);
                        return null;
                    })
        );
        setActionFn(api, "ping", session::ping);
        setActionFn(
            api,
            "stop",
            () -> {
                org.ngengine.platform.AsyncExecutor executor = NGEUtils.getPlatform().newAsyncExecutor();
                executor.run(() -> {
                    try {
                        session.stop();
                    } finally {
                        executor.close();
                    }
                    return null;
                });
            }
        );
        JsBridge.setGlobal("RTCDemo", api);
    }

    /** Parsed start() configuration. */
    static final class Config {

        String roomKeySeed = PingProtocol.DEFAULT_ROOM_KEY_SEED;
        List<String> relays = new ArrayList<>();
        String mode = "auto";
        String turnUri = PingProtocol.DEFAULT_TURN_URI;
        String receiverPubkey;

        Config() {
            relays.add(PingProtocol.DEFAULT_RELAY);
        }

        static Config parse(String json) {
            Config cfg = new Config();
            if (json == null || json.length() > 4096) throw new IllegalArgumentException("invalid configuration");
            @SuppressWarnings("unchecked")
            java.util.Map<String, Object> raw = (java.util.Map<String, Object>) NGEPlatform
                .get()
                .fromJSON(json, java.util.Map.class);
            String seed = NGEUtils.safeString(raw.get("roomKeySeed")).trim();
            if (!seed.isEmpty()) cfg.roomKeySeed = seed;
            if (cfg.roomKeySeed.length() > 128 || containsControl(cfg.roomKeySeed)) {
                throw new IllegalArgumentException("room seed must be at most 128 printable characters");
            }
            List<String> relays = NGEUtils.safeStringList(raw.get("relays"));
            if (relays != null && !relays.isEmpty()) {
                if (relays.size() > 4) throw new IllegalArgumentException("at most four relays are allowed");
                cfg.relays = new ArrayList<>();
                for (String relay : relays) cfg.relays.add(JsBridge.requireWssUrl(relay));
            }
            String mode = NGEUtils.safeString(raw.get("mode"));
            if ("auto".equals(mode) || "direct".equals(mode) || "turn".equals(mode)) cfg.mode = mode;
            cfg.turnUri = JsBridge.requireWssUrl(NGEUtils.safeString(raw.get("turnUri")));
            cfg.receiverPubkey = NGEUtils.safeString(raw.get("receiverPubkey"));
            if (!cfg.receiverPubkey.matches("[a-f0-9]{64}")) throw new IllegalArgumentException(
                "Backend receiver identity required"
            );
            return cfg;
        }

        private static boolean containsControl(String value) {
            for (int i = 0; i < value.length(); i++) if (Character.isISOControl(value.charAt(i))) return true;
            return false;
        }
    }

    /** One demo session; all state is touched on the JS thread only. */
    static final class Session {

        private volatile NostrRTCRoom room;
        private volatile NostrPool pool;
        private volatile NostrTURNPool turnPool;
        private volatile NostrKeyPair roomKeys;
        private NostrKeyPair identity;
        private String expectedReceiver;
        private long pendingPing;
        private volatile NostrRTCPeer receiverPeer;
        private volatile String receiverId = "";
        private volatile boolean running;

        private StringCallback onLog = s -> JsBridge.log(s);
        private StringCallback onEvent = s -> JsBridge.log(s);
        private StringCallback onError = s -> JsBridge.warn(s);

        void start(String configJson, StringCallback onLog, StringCallback onEvent, StringCallback onError) {
            if (onLog != null) this.onLog = onLog;
            if (onEvent != null) this.onEvent = onEvent;
            if (onError != null) this.onError = onError;
            stopQuiet();
            try {
                Config cfg = Config.parse(configJson == null ? "{}" : configJson);
                log("mode=" + cfg.mode + " · isolated visitor room");
                expectedReceiver = cfg.receiverPubkey;
                roomKeys = PingProtocol.deriveRoomKeys(cfg.roomKeySeed);
                log("room id: " + roomKeys.getPublicKey().asHex());

                // "direct" = no TURN configured at all (no fallback possible);
                // "auto"/"turn" configure the TURN server, "turn" additionally forces it.
                String turnUri = "direct".equals(cfg.mode) ? null : cfg.turnUri;
                identity = new NostrKeyPair();
                NostrRTCLocalPeer local = new NostrRTCLocalPeer(
                    new NostrKeyPairSigner(identity),
                    RTCSettings.PUBLIC_STUN_SERVERS,
                    PingProtocol.APPLICATION_ID,
                    PingProtocol.PROTOCOL_ID,
                    roomKeys,
                    turnUri
                );
                pool = new NostrPool();
                for (String relayUrl : cfg.relays) {
                    log("signaling relay: " + relayUrl);
                    pool.addRelay(new NostrRelay(relayUrl));
                }
                turnPool = turnUri != null ? new NostrTURNPool() : null;
                NostrRTCRoom r = new NostrRTCRoom(
                    RTCSettings.DEFAULT.withMaxDirectPeers(2),
                    local,
                    roomKeys,
                    pool,
                    turnUri,
                    turnPool
                );
                if ("turn".equals(cfg.mode)) {
                    r.setForceTURN(true);
                    log("TURN forced: all media will go through " + turnUri);
                } else {
                    // "auto": TURN is configured as a fallback; "direct": no
                    // TURN configured at all (direct-only). Neither forces TURN.
                    r.setForceTURN(false);
                    log(
                        "direct".equals(cfg.mode)
                            ? "TURN not configured: direct WebRTC only"
                            : "TURN " + turnUri + " available as fallback"
                    );
                }
                r.addPeerDiscoveryListener((peer, announce, state) -> {
                    log("discovered peer " + shortId(peer) + " (" + state + ")");
                    if (peer.getPubkey().asHex().equals(expectedReceiver)) {
                        emit("{\"t\":\"discovered\",\"id\":\"" + shortId(peer) + "\"}");
                    }
                });
                r.addPeerSocketAvailableListener(this::onSocketAvailable);
                r.addDisconnectionListener(this::onDisconnected);
                r.addMessageListener(this::onMessage);
                room = r;
                running = true;
                r
                    .start()
                    .then(ignored -> {
                        log("room started — waiting for the receiver's HELLO…");
                        return null;
                    })
                    .catchException(err -> onError.call("room start failed: " + err));
            } catch (Exception e) {
                onError.call("start failed: " + e);
                stopQuiet();
            }
        }

        void ping() {
            NostrRTCPeer target = receiverPeer;
            NostrRTCRoom r = room;
            if (!running || r == null || target == null) {
                onError.call("no receiver discovered yet — wait for its HELLO");
                return;
            }
            long now = System.nanoTime();
            pendingPing = now;
            try {
                r.createChannel(target, PingProtocol.CHANNEL);
                r
                    .send(
                        PingProtocol.CHANNEL,
                        target,
                        ByteBuffer.wrap(PingProtocol.pingFrame(now).getBytes(StandardCharsets.UTF_8))
                    )
                    .catchException(err -> onError.call("ping send failed: " + err));
                log("ping → " + receiverId);
            } catch (Exception e) {
                onError.call("ping failed: " + e);
            }
        }

        void stop() {
            stopQuiet();
            log("stopped");
        }

        private void stopQuiet() {
            running = false;
            pendingPing = 0;
            receiverPeer = null;
            receiverId = "";
            NostrRTCRoom r = room;
            room = null;
            if (r != null) {
                try {
                    r.close();
                } catch (Exception ignored) {}
            }
            NostrTURNPool tp = turnPool;
            turnPool = null;
            if (tp != null) {
                try {
                    tp.close();
                } catch (Exception ignored) {}
            }
            NostrPool p = pool;
            pool = null;
            if (p != null) {
                try {
                    p.close();
                } catch (Exception ignored) {}
            }
            NostrKeyPair rk = roomKeys;
            roomKeys = null;
            if (rk != null) {
                try {
                    rk.close();
                } catch (Exception ignored) {}
            }
            if (identity != null) {
                identity.close();
                identity = null;
            }
        }

        private void onSocketAvailable(NostrRTCPeer peer, NostrRTCSocket socket) {
            String id = shortId(peer);
            log("peer up: " + id);
            // Honest transport signal #1: socket-level transport switches.
            socket.addListener(
                new NostrRTCSocketListener() {
                    @Override
                    public void onRTCSocketRouteUpdate(
                        NostrRTCSocket s,
                        java.util.Collection<org.ngengine.platform.transport.RTCTransportIceCandidate> candidates,
                        String turnServer
                    ) {}

                    @Override
                    public void onRTCSocketClose(NostrRTCSocket s) {}

                    @Override
                    public void onRTCChannelReady(NostrRTCChannel channel) {}

                    @Override
                    public void onRTCChannel(NostrRTCChannel channel) {}

                    @Override
                    public void onRTCSocketTransportSwitch(
                        NostrRTCSocket s,
                        NostrRTCSocket.TransportPath from,
                        NostrRTCSocket.TransportPath to,
                        String reason
                    ) {
                        emit(
                            "{\"t\":\"transport\",\"peer\":\"" +
                            id +
                            "\",\"from\":\"" +
                            from +
                            "\",\"to\":\"" +
                            to +
                            "\",\"reason\":\"" +
                            JsBridge.esc(reason) +
                            "\"}"
                        );
                        log("transport " + id + ": " + from + " → " + to + " (" + reason + ")");
                    }
                }
            );
            try {
                room.createChannel(peer, PingProtocol.CHANNEL);
            } catch (Exception e) {
                onError.call("createChannel failed: " + e);
            }
            emit("{\"t\":\"peer\",\"id\":\"" + id + "\",\"state\":\"up\"}");
        }

        private void onDisconnected(NostrRTCPeer peer, NostrRTCSocket socket) {
            String id = shortId(peer);
            log("peer down: " + id);
            if (peer.equals(receiverPeer)) {
                receiverPeer = null;
                receiverId = "";
                log("receiver lost — waiting for its next HELLO…");
            }
            emit("{\"t\":\"peer\",\"id\":\"" + id + "\",\"state\":\"down\"}");
        }

        private void onMessage(
            NostrRTCPeer peer,
            NostrRTCSocket socket,
            NostrRTCChannel channel,
            ByteBuffer bbf,
            boolean turn
        ) {
            if (!running || !peer.getPubkey().asHex().equals(expectedReceiver)) return;
            if (!PingProtocol.CHANNEL.equals(channel.getName()) || bbf.remaining() > 128) return;
            String msg = decode(bbf);
            String id = shortId(peer);
            if (PingProtocol.HELLO.equals(msg)) {
                // RECEIVER DISCOVERY: the receiver broadcasts HELLO on the
                // control channel every 5s. nostr4j delivers broadcast frames
                // to addMessageListener with the true originator peer, so the
                // peer object seen here IS the receiver — keep it as the ping
                // target for room.send(channel, peer, ...).
                receiverPeer = peer;
                receiverId = id;
                try {
                    room.createChannel(peer, PingProtocol.CHANNEL);
                } catch (Exception e) {
                    onError.call("createChannel failed: " + e);
                }
                log("receiver HELLO from " + id + " — ping target acquired");
                emit("{\"t\":\"receiver\",\"id\":\"" + id + "\"}");
            } else if (msg.startsWith(PingProtocol.PONG + ":")) {
                long sentNanos = parseLong(msg.substring(PingProtocol.PONG.length() + 1));
                if (pendingPing == 0 || sentNanos != pendingPing) return;
                pendingPing = 0;
                long rttMs = sentNanos <= 0 ? -1 : (System.nanoTime() - sentNanos) / 1_000_000L;
                // Honest transport signal #2: the per-message `turn` flag
                // observed by nostr4j for this exact PONG.
                emit("{\"t\":\"pong\",\"rttMs\":" + rttMs + ",\"viaTurn\":" + turn + "}");
                log("pong from " + id + ": RTT " + rttMs + " ms · via " + (turn ? "TURN" : "direct WebRTC"));
            }
        }

        private void log(String msg) {
            try {
                onLog.call("[rtc] " + msg);
            } catch (Exception ignored) {}
        }

        private void emit(String json) {
            try {
                onEvent.call(json);
            } catch (Exception ignored) {}
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
    }
}
