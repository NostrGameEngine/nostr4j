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

package org.ngengine.site.demos.game;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.ngengine.nostr4j.NostrPool;
import org.ngengine.nostr4j.NostrRelay;
import org.ngengine.nostr4j.RTCSettings;
import org.ngengine.nostr4j.keypair.NostrKeyPair;
import org.ngengine.nostr4j.keypair.NostrPrivateKey;
import org.ngengine.nostr4j.keypair.NostrPublicKey;
import org.ngengine.nostr4j.rtc.NostrRTCRoom;
import org.ngengine.nostr4j.rtc.NostrTURNPool;
import org.ngengine.nostr4j.rtc.TopologyJsonBridge;
import org.ngengine.nostr4j.rtc.routing.NodeId;
import org.ngengine.nostr4j.rtc.routing.RoutingScope;
import org.ngengine.nostr4j.rtc.signal.NostrRTCLocalPeer;
import org.ngengine.nostr4j.rtc.signal.NostrRTCPeer;
import org.ngengine.nostr4j.signer.NostrKeyPairSigner;
import org.ngengine.platform.AsyncTask;
import org.ngengine.platform.NGEPlatform;
import org.ngengine.platform.teavm.TeaVMPlatform;
import org.ngengine.site.demos.JsBridge;
import org.teavm.jso.JSBody;
import org.teavm.jso.JSFunctor;
import org.teavm.jso.JSObject;

/**
 * TeaVM entry point for the Sats Seas demo (demo 4).
 *
 * <p>Installs {@code window.SatsSeasNet} with a tiny JSON-string API:</p>
 * <ul>
 *   <li>{@code join(configJson, onEvent)} — {@code config} is
 *       {@code {"name","roomKeySeed","relays":[...],"identityNsec"?}}.
 *       Returns the local identity pubkey hex. Progress arrives as events.</li>
 *   <li>{@code newIdentity()} — fresh demo-only {@code nsec} for this browser tab.</li>
 *   <li>{@code broadcastPos(jsonStr)} — tree broadcast on {@code sats-pos}.</li>
 *   <li>{@code broadcastGame(jsonStr)} — tree broadcast on {@code sats-game}.</li>
 *   <li>{@code sendTo(peerIdHex, channel, jsonStr)} — onion-routed unicast
 *       ({@code channel} is {@code sats-pos} or {@code sats-game}).</li>
 *   <li>{@code getTopology()} — pushes a {@code {"t":"topo",...}} event.</li>
 *   <li>{@code ban(peerIdHex, reason)} — cooperative local isolation.</li>
 *   <li>{@code myId()} — local identity pubkey hex ("" when offline).</li>
 *   <li>{@code leave()} — despawn + close.</li>
 * </ul>
 *
 * <p>Events pushed to {@code onEvent(json)} are documented in
 * {@code PROTOCOL.md} ({@code peer}, {@code msg}, {@code topo},
 * {@code ban}, {@code log}, {@code ready}).</p>
 *
 * <p>Browser threading: this code never blocks the JS thread — every async
 * step uses {@code then(...)} chains, never {@code await()}.</p>
 */
public final class SatsSeasNet {

    private static final String APP_ID = "sats-seas";
    private static final String PROTOCOL_ID = "sats-seas-v1";

    /** Application RTC channels created on every logical socket. */
    public static final String POS_CHANNEL = "sats-pos";
    public static final String GAME_CHANNEL = "sats-game";

    /** Bounded-degree room: ~2-3 direct neighbors, everything else onion-routed. */
    private static final int MAX_DIRECT_PEERS = 2;

    private static final List<String> DEFAULT_RELAYS = Arrays.asList("wss://relay.ngengine.org", "wss://relay2.ngengine.org");

    private SatsSeasNet() {}

    // ------------------------------------------------------- local JS interop
    // These live here (not in the shared JsBridge) so this demo's commits stay
    // inside the allowed path site-demos/.../game/.

    /** Page callback receiving event JSON. Works in both directions. */
    @JSFunctor
    private interface JsEventCallback extends JSObject {
        void call(String json);
    }

    /** join(configJson, onEvent) -> local pubkey hex. */
    @JSFunctor
    private interface JsJoinFn extends JSObject {
        String call(String configJson, JsEventCallback onEvent);
    }

    /** A zero-arg JS callback. */
    @JSFunctor
    private interface JsAction extends JSObject {
        void call();
    }

    /** A zero-arg JS callback returning a string. */
    @JSFunctor
    private interface JsSupplier extends JSObject {
        String call();
    }

    /** A JS callback receiving two strings. */
    @JSFunctor
    private interface Js2StringCallback extends JSObject {
        void call(String a, String b);
    }

    /** A JS callback receiving three strings. */
    @JSFunctor
    private interface Js3StringCallback extends JSObject {
        void call(String a, String b, String c);
    }

    /** A JS callback receiving one string. */
    @JSFunctor
    private interface JsStringFn extends JSObject {
        void call(String json);
    }

    @JSBody(params = { "obj", "key", "fn" }, script = "obj[key] = fn;")
    private static native void installJoinFn(JSObject obj, String key, JsJoinFn fn);

    @JSBody(params = { "obj", "key", "fn" }, script = "obj[key] = fn;")
    private static native void installAction(JSObject obj, String key, JsAction fn);

    @JSBody(params = { "obj", "key", "fn" }, script = "obj[key] = fn;")
    private static native void installSupplier(JSObject obj, String key, JsSupplier fn);

    @JSBody(params = { "obj", "key", "fn" }, script = "obj[key] = fn;")
    private static native void installStringFn(JSObject obj, String key, JsStringFn fn);

    @JSBody(params = { "obj", "key", "fn" }, script = "obj[key] = fn;")
    private static native void install2StringFn(JSObject obj, String key, Js2StringCallback fn);

    @JSBody(params = { "obj", "key", "fn" }, script = "obj[key] = fn;")
    private static native void install3StringFn(JSObject obj, String key, Js3StringCallback fn);

    // ------------------------------------------------------------------ state

    private static NostrRTCRoom room;
    private static NostrPool pool;
    private static NostrTURNPool turnPool;
    private static NostrKeyPair roomKeyPair;
    private static NostrKeyPair identity;
    private static NostrRTCLocalPeer localPeer;
    private static String localNodeHex;
    private static JsEventCallback onEvent;
    private static volatile boolean joined;
    private static int generation;
    private static String turnUri;

    // ------------------------------------------------------------------ entry

    public static void main(String[] args) {
        NGEPlatform.set(new TeaVMPlatform());
        if (!hasWindow()) {
            // node smoke-run: must not crash, must not touch the DOM.
            System.out.println("SatsSeasNet: no window object; net API not installed (node run).");
            return;
        }
        JSObject api = JsBridge.newObject();
        installJoinFn(
            api,
            "join",
            new JsJoinFn() {
                @Override
                public String call(String configJson, JsEventCallback onEventFn) {
                    final int attempt = ++generation;
                    org.ngengine.platform.AsyncExecutor executor = NGEPlatform.get().newAsyncExecutor();
                    executor.run(() -> {
                        try {
                            if (attempt != generation) return null;
                            leave();
                            onEvent = onEventFn;
                            join(configJson);
                        } catch (Throwable error) {
                            emitLog("Connection failed: " + error.getMessage());
                        } finally {
                            executor.close();
                        }
                        return null;
                    });
                    return "";
                }
            }
        );
        installSupplier(
            api,
            "newIdentity",
            new JsSupplier() {
                @Override
                public String call() {
                    return newIdentity();
                }
            }
        );
        installSupplier(
            api,
            "myId",
            new JsSupplier() {
                @Override
                public String call() {
                    return myId();
                }
            }
        );
        installStringFn(
            api,
            "broadcastPos",
            new JsStringFn() {
                @Override
                public void call(String json) {
                    broadcastPos(json);
                }
            }
        );
        installStringFn(
            api,
            "broadcastGame",
            new JsStringFn() {
                @Override
                public void call(String json) {
                    broadcastGame(json);
                }
            }
        );
        install3StringFn(
            api,
            "sendTo",
            new Js3StringCallback() {
                @Override
                public void call(String peerId, String channel, String json) {
                    sendTo(peerId, channel, json);
                }
            }
        );
        installAction(
            api,
            "getTopology",
            new JsAction() {
                @Override
                public void call() {
                    getTopology();
                }
            }
        );
        install2StringFn(
            api,
            "ban",
            new Js2StringCallback() {
                @Override
                public void call(String peerId, String reason) {
                    ban(peerId, reason);
                }
            }
        );
        installAction(
            api,
            "leave",
            new JsAction() {
                @Override
                public void call() {
                    generation++;
                    org.ngengine.platform.AsyncExecutor executor = NGEPlatform.get().newAsyncExecutor();
                    executor.run(() -> {
                        try {
                            leave();
                        } finally {
                            executor.close();
                        }
                        return null;
                    });
                }
            }
        );
        JsBridge.setGlobal("SatsSeasNet", api);
        JsBridge.log("SatsSeasNet: API installed");
    }

    @JSBody(params = {}, script = "return (typeof window !== 'undefined');")
    static native boolean hasWindow();

    // ------------------------------------------------------------------ API

    /** Generate a fresh identity; the page retains it in per-tab sessionStorage. */
    public static String newIdentity() {
        try {
            try (NostrKeyPair keys = new NostrKeyPair()) {
                return keys.getPrivateKey().asBech32();
            }
        } catch (Throwable t) {
            emitLog("newIdentity failed: " + t);
            return "";
        }
    }

    public static String myId() {
        return identity == null ? "" : identity.getPublicKey().asHex();
    }

    /**
     * Join the public room. {@code configJson}:
     * {@code {"name":"Anne","roomKeySeed":"lobby-1","relays":["wss://..."],"identityNsec":"nsec1..."?}}.
     *
     * @return local identity pubkey hex, or "" on immediate failure
     */
    public static String join(String configJson) {
        if (joined) {
            emitLog("already joined");
            return myId();
        }
        if (onEvent == null) {
            JsBridge.log("SatsSeasNet.join: onEvent callback not registered");
            return "";
        }
        Map<String, Object> config;
        try {
            config = parseConfig(configJson);
        } catch (Throwable t) {
            emitLog("bad config JSON: " + t.getMessage());
            return "";
        }
        String name = str(config, "name", "sailor");
        String seed = str(config, "roomKeySeed", "sats-seas-lobby");
        @SuppressWarnings("unchecked")
        List<String> relays = (List<String>) config.get("relays");
        if (relays == null || relays.isEmpty()) relays = DEFAULT_RELAYS;

        try {
            name = requirePrintable(name, "name", 24);
            turnUri = str(config, "turnUri", "wss://turn.ngengine.org/turn").trim();
            turnUri = turnUri.isEmpty() ? null : JsBridge.requireWssUrl(turnUri);
            seed = requirePrintable(seed, "room seed", 64);
            if (relays.size() > 4) throw new IllegalArgumentException("at most four relays are allowed");
            List<String> checkedRelays = new ArrayList<String>();
            for (String relay : relays) checkedRelays.add(JsBridge.requireWssUrl(relay));
            relays = checkedRelays;
            // Deterministic public room key: everyone typing the same seed
            // joins the same room. Intentionally public (see GAME_DESIGN.md §15).
            byte[] roomSecret = NGEPlatform.get().sha256(("sats-seas-room-v1|" + seed).getBytes(StandardCharsets.UTF_8));
            roomKeyPair = new NostrKeyPair(NostrPrivateKey.fromBytes(roomSecret));

            String nsec = str(config, "identityNsec", null);
            if (nsec != null && (nsec.length() > 128 || !nsec.startsWith("nsec1"))) {
                throw new IllegalArgumentException("invalid demo identity");
            }
            identity = (nsec == null || nsec.isEmpty()) ? new NostrKeyPair() : new NostrKeyPair(NostrPrivateKey.fromNsec(nsec));
        } catch (Throwable t) {
            emitLog("key setup failed: " + t);
            return "";
        }

        emitLog("connecting to " + relays.size() + " relay(s) as " + name + " ...");
        pool = new NostrPool();
        final List<String> relayUrls = new ArrayList<String>(relays);
        final String playerName = name;
        List<AsyncTask<NostrRelay>> connects = new ArrayList<AsyncTask<NostrRelay>>();
        for (String url : relayUrls) {
            connects.add(pool.ensureRelay(url));
        }
        final int attempt = generation;
        AsyncTask
            .any(connects)
            .then(ignored -> {
                if (attempt != generation) return null;
                try {
                    startRoom(playerName);
                } catch (Throwable t) {
                    emitLog("room start failed: " + t);
                }
                return null;
            })
            .catchException(err -> emitLog("relay connect failed: " + err));
        return myId();
    }

    private static void startRoom(String playerName) {
        RTCSettings settings = RTCSettings.DEFAULT.withMaxDirectPeers(MAX_DIRECT_PEERS);
        turnPool = new NostrTURNPool();
        localPeer =
            new NostrRTCLocalPeer(
                new NostrKeyPairSigner(identity),
                RTCSettings.PUBLIC_STUN_SERVERS,
                APP_ID,
                PROTOCOL_ID,
                roomKeyPair,
                turnUri
            );
        room = new NostrRTCRoom(settings, localPeer, roomKeyPair, pool, turnUri, turnPool);

        RoutingScope scope = new RoutingScope(roomKeyPair.getPublicKey(), PROTOCOL_ID, APP_ID);
        localNodeHex = NodeId.derive(scope, identity.getPublicKey(), localPeer.getSessionId()).asHex();

        room.addPeerSocketAvailableListener((peer, socket) -> {
            try {
                room.createChannel(peer, POS_CHANNEL);
            } catch (Throwable ignored) {}
            try {
                room.createChannel(peer, GAME_CHANNEL);
            } catch (Throwable ignored) {}
        });
        room.addPeerDiscoveryListener((peer, announce, state) -> {
            String node = NodeId.derive(scope, peer.getPubkey(), peer.getSessionId()).asHex();
            emit(
                "{\"t\":\"peer\",\"id\":\"" +
                peer.getPubkey().asHex() +
                "\",\"node\":\"" +
                node +
                "\",\"state\":\"" +
                state.name().toLowerCase(Locale.ROOT) +
                "\"}"
            );
            pushTopology();
        });
        room.addDisconnectionListener((peer, socket) ->
            emit("{\"t\":\"peer\",\"id\":\"" + peer.getPubkey().asHex() + "\",\"state\":\"disconnected\"}")
        );
        room.addMessageListener((peer, socket, channel, bbf, turn) -> {
            String from = peer.getPubkey().asHex();
            String chan = channel.getName();
            for (String frame : WireCodec.decodeAll(bbf)) {
                emit(
                    "{\"t\":\"msg\",\"from\":\"" +
                    from +
                    "\",\"channel\":\"" +
                    JsBridge.esc(chan) +
                    "\",\"body\":" +
                    quoteJson(frame) +
                    "}"
                );
            }
        });

        final int attempt = generation;
        room
            .start()
            .then(ignored -> {
                if (attempt != generation) return null;
                joined = true;
                emit(
                    "{\"t\":\"ready\",\"id\":\"" +
                    myId() +
                    "\",\"session\":\"" +
                    JsBridge.esc(localPeer.getSessionId()) +
                    "\",\"name\":\"" +
                    JsBridge.esc(playerName) +
                    "\"}"
                );
                pushTopology();
                return null;
            })
            .catchException(err -> emitLog("room start failed: " + err));
    }

    /** Tree broadcast of a position JSON string on {@code sats-pos}. */
    public static void broadcastPos(String json) {
        NostrRTCRoom r = room;
        if (r == null || !joined) return;
        try {
            r.broadcast(POS_CHANNEL, WireCodec.encode(json)).catchException(err -> sendPositionsIndividually(r, json));
        } catch (Throwable t) {
            sendPositionsIndividually(r, json);
        }
    }

    private static void sendPositionsIndividually(NostrRTCRoom r, String json) {
        for (NostrRTCPeer peer : r.getPeers()) {
            try {
                r.createChannel(peer, POS_CHANNEL);
                r.send(POS_CHANNEL, peer, WireCodec.encode(json));
            } catch (Throwable ignored) {
                // An individual route may not be ready yet; the next position pulse retries it.
            }
        }
    }

    /** Tree broadcast of a game JSON string on {@code sats-game}. */
    public static void broadcastGame(String json) {
        NostrRTCRoom r = room;
        if (r == null || !joined) return;
        try {
            r.broadcast(GAME_CHANNEL, WireCodec.encode(json)).catchException(err -> emitLog("broadcastGame failed: " + err));
        } catch (Throwable t) {
            emitLog("broadcastGame failed: " + t);
        }
    }

    /** Onion-routed unicast to one peer on {@code sats-pos} or {@code sats-game}. */
    public static void sendTo(String peerIdHex, String channel, String json) {
        NostrRTCRoom r = room;
        if (r == null || !joined) {
            emitLog("sendTo: not joined");
            return;
        }
        if (!POS_CHANNEL.equals(channel) && !GAME_CHANNEL.equals(channel)) {
            emitLog("sendTo: unknown channel " + channel);
            return;
        }
        NostrRTCPeer target = null;
        for (NostrRTCPeer p : r.getPeers()) {
            if (p.getPubkey() != null && peerIdHex.equals(p.getPubkey().asHex())) {
                target = p;
                break;
            }
        }
        if (target == null) {
            emitLog("sendTo: no route to peer " + shortId(peerIdHex));
            return;
        }
        try {
            r.createChannel(target, channel); // idempotent
            final String pid = peerIdHex;
            r
                .send(channel, target, WireCodec.encode(json))
                .catchException(err -> emitLog("sendTo " + shortId(pid) + " failed: " + err));
        } catch (Throwable t) {
            emitLog("sendTo " + shortId(peerIdHex) + " failed: " + t);
        }
    }

    /** Push a {@code {"t":"topo",...}} snapshot event. */
    public static void getTopology() {
        pushTopology();
    }

    private static void pushTopology() {
        if (room == null || onEvent == null) return;
        try {
            emit(TopologyJsonBridge.snapshot(room, localNodeHex));
        } catch (Throwable t) {
            emitLog("topology snapshot failed: " + t);
        }
    }

    /** Cooperative isolation: drop a peer locally (see PROTOCOL.md §ban). */
    public static void ban(String peerIdHex, String reason) {
        NostrRTCRoom r = room;
        if (r == null) return;
        try {
            r.ban(NostrPublicKey.fromHex(peerIdHex));
            emit(
                "{\"t\":\"ban\",\"peer\":\"" +
                JsBridge.esc(peerIdHex) +
                "\",\"reason\":\"" +
                JsBridge.esc(reason == null ? "" : reason) +
                "\"}"
            );
        } catch (Throwable t) {
            emitLog("ban failed: " + t);
        }
    }

    /** Leave the room and release everything. */
    public static void leave() {
        generation++;
        joined = false;
        try {
            if (room != null) room.close();
        } catch (Throwable ignored) {}
        try {
            if (turnPool != null) turnPool.close();
        } catch (Throwable ignored) {}
        try {
            if (pool != null) pool.close();
        } catch (Throwable ignored) {}
        try {
            if (roomKeyPair != null) roomKeyPair.close();
        } catch (Throwable ignored) {}
        try {
            if (identity != null) identity.close();
        } catch (Throwable ignored) {}
        room = null;
        turnPool = null;
        pool = null;
        roomKeyPair = null;
        identity = null;
        localPeer = null;
        localNodeHex = null;
        emitLog("left the room");
    }

    // ------------------------------------------------------------------ utils

    private static void emit(String json) {
        JsEventCallback cb = onEvent;
        if (cb != null) {
            try {
                cb.call(json);
            } catch (Throwable t) {
                JsBridge.log("SatsSeasNet event callback failed: " + t);
            }
        }
    }

    private static void emitLog(String msg) {
        emit("{\"t\":\"log\",\"msg\":\"" + JsBridge.esc(msg) + "\"}");
    }

    private static String shortId(String hex) {
        return hex == null ? "?" : hex.substring(0, Math.min(8, hex.length()));
    }

    /** Embed an already-JSON string as a JSON string value (quote + escape). */
    private static String quoteJson(String json) {
        return "\"" + JsBridge.esc(json) + "\"";
    }

    private static String str(Map<String, Object> config, String key, String dflt) {
        Object v = config.get(key);
        return v instanceof String ? (String) v : dflt;
    }

    private static String requirePrintable(String value, String label, int maxLength) {
        if (value == null) throw new IllegalArgumentException(label + " is required");
        String checked = value.trim();
        if (checked.isEmpty() || checked.length() > maxLength) {
            throw new IllegalArgumentException(label + " must be 1-" + maxLength + " characters");
        }
        for (int i = 0; i < checked.length(); i++) {
            if (Character.isISOControl(checked.charAt(i))) throw new IllegalArgumentException("invalid " + label);
        }
        return checked;
    }

    // ------------------------------------------------------- tiny JSON parser

    /**
     * Parse the join-config object. Supports exactly what the page sends:
     * string values (with escapes), one string array, booleans/numbers as
     * opaque strings. Anything else throws.
     */
    static Map<String, Object> parseConfig(String json) {
        if (json == null || json.length() > 8192) throw new IllegalArgumentException("configuration is too large");
        Parser p = new Parser(json);
        p.ws();
        if (p.next() != '{') throw new IllegalArgumentException("config must be an object");
        Map<String, Object> out = new HashMap<String, Object>();
        p.ws();
        if (p.peek() == '}') {
            p.next();
            return out;
        }
        for (;;) {
            p.ws();
            String key = p.string();
            p.ws();
            if (p.next() != ':') throw new IllegalArgumentException("expected ':'");
            p.ws();
            out.put(key, p.value());
            p.ws();
            char c = p.next();
            if (c == ',') continue;
            if (c == '}') break;
            throw new IllegalArgumentException("expected ',' or '}'");
        }
        return out;
    }

    private static final class Parser {

        private final String s;
        private int i;

        Parser(String s) {
            this.s = s;
        }

        void ws() {
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') i++; else break;
            }
        }

        char peek() {
            if (i >= s.length()) throw new IllegalArgumentException("unexpected end of JSON");
            return s.charAt(i);
        }

        char next() {
            char c = peek();
            i++;
            return c;
        }

        Object value() {
            char c = peek();
            if (c == '"') return string();
            if (c == '[') return array();
            if (c == 't' && s.startsWith("true", i)) {
                i += 4;
                return Boolean.TRUE;
            }
            if (c == 'f' && s.startsWith("false", i)) {
                i += 5;
                return Boolean.FALSE;
            }
            // numbers / null: keep opaque
            int start = i;
            while (i < s.length() && ",}] \t\n\r".indexOf(s.charAt(i)) < 0) i++;
            return s.substring(start, i);
        }

        List<String> array() {
            if (next() != '[') throw new IllegalArgumentException("expected '['");
            List<String> out = new ArrayList<String>();
            ws();
            if (peek() == ']') {
                next();
                return out;
            }
            for (;;) {
                ws();
                out.add(string());
                ws();
                char c = next();
                if (c == ',') continue;
                if (c == ']') break;
                throw new IllegalArgumentException("expected ',' or ']'");
            }
            return out;
        }

        String string() {
            if (next() != '"') throw new IllegalArgumentException("expected string");
            StringBuilder b = new StringBuilder();
            for (;;) {
                char c = next();
                if (c == '"') break;
                if (c == '\\') {
                    char e = next();
                    switch (e) {
                        case '"':
                            b.append('"');
                            break;
                        case '\\':
                            b.append('\\');
                            break;
                        case '/':
                            b.append('/');
                            break;
                        case 'n':
                            b.append('\n');
                            break;
                        case 'r':
                            b.append('\r');
                            break;
                        case 't':
                            b.append('\t');
                            break;
                        case 'u':
                            if (i + 4 > s.length()) throw new IllegalArgumentException("bad \\u escape");
                            b.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                            i += 4;
                            break;
                        default:
                            throw new IllegalArgumentException("bad escape \\" + e);
                    }
                } else {
                    b.append(c);
                }
            }
            return b.toString();
        }
    }
}
