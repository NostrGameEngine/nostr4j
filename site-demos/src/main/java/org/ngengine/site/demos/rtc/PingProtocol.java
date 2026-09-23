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

import java.nio.charset.StandardCharsets;
import org.ngengine.nostr4j.keypair.NostrKeyPair;
import org.ngengine.nostr4j.keypair.NostrPrivateKey;
import org.ngengine.platform.NGEUtils;

/**
 * Shared protocol constants for the RTC ping/pong site demo (demo 3).
 *
 * <p>The browser demo ({@link RTCPingDemo}, compiled to JS with TeaVM) and the
 * operator-hosted receiver ({@link PingReceiver}, plain JVM) both build on
 * these constants, so they always agree on the room, the control channel and
 * the frame formats.
 *
 * <h2>Room identity</h2>
 * <p>Nostr4J rooms are identified by a shared room key pair: peers that hold
 * the same {@code NostrKeyPair} discover each other over the signaling relays
 * and can open WebRTC (or TURN) data channels. Both sides derive that key
 * pair deterministically from the room-key <em>seed</em> string:
 * {@code SHA-256(seed)} becomes the 32-byte private key. Same seed on both
 * sides → same room.
 *
 * <h2>Receiver discovery</h2>
 * <p>The receiver periodically broadcasts a {@code HELLO} frame on the
 * control channel ({@value #CHANNEL}). The browser demo learns the receiver's
 * {@code NostrRTCPeer} identity from the {@code peer} argument of its
 * {@link org.ngengine.nostr4j.rtc.NostrRTCRoom#addMessageListener
 * addMessageListener} callback: for broadcast frames nostr4j delivers the
 * message with the true originator peer (see
 * {@code NostrRTCRoom.deliverBroadcast} → the channel bound to the origin
 * peer's socket), so the browser can then address pings with
 * {@code room.send(channel, thatPeer, ...)}.
 */
public final class PingProtocol {

    private PingProtocol() {}

    /** Control channel carrying HELLO / PING / PONG frames. */
    public static final String CHANNEL = "ping-v1";

    /** Default room-key seed; both sides must use the same seed. */
    public static final String DEFAULT_ROOM_KEY_SEED = "nostr4j-demo-ping-v1";

    public static final String APPLICATION_ID = "nostr4j-site-rtc-ping";
    public static final String PROTOCOL_ID = "ping-v1";

    /** Default signaling relay used when none is configured. */
    public static final String DEFAULT_RELAY = "wss://relay.ngengine.org";

    /**
     * Default TURN server, used for the TURN fallback / forced-TURN modes.
     * (Same URI the nostr4j test suite uses; see
     * {@code TestNostrRTCTurn} in {@code nostr4j-demo}.)
     */
    public static final String DEFAULT_TURN_URI = "wss://turn.ngengine.org/turn";

    /** How often the receiver broadcasts HELLO on the control channel. */
    public static final long HELLO_INTERVAL_MS = 5_000L;

    /** Frame kinds, sent as {@code KIND} or {@code KIND:payload}. */
    public static final String HELLO = "HELLO";
    public static final String PING = "PING";
    public static final String PONG = "PONG";

    /**
     * Derives the shared room key pair from a seed string.
     *
     * <p>Uses the platform SHA-256 ({@code NGEUtils.getPlatform().sha256}),
     * which is implemented natively on every platform (TeaVM binds in the
     * browser, {@code java.security} on the JVM), so the browser and the
     * receiver compute byte-identical keys from the same seed.
     */
    public static NostrKeyPair deriveRoomKeys(String seed) {
        byte[] digest = NGEUtils.getPlatform().sha256(seed.getBytes(StandardCharsets.UTF_8));
        return new NostrKeyPair(NostrPrivateKey.fromBytes(digest));
    }

    /** Builds a PING frame carrying the sender's {@code System.nanoTime()}. */
    public static String pingFrame(long sendNanos) {
        return PING + ":" + sendNanos;
    }

    /** Builds a PONG frame echoing the PING's timestamp back. */
    public static String pongFrame(long sendNanos) {
        return PONG + ":" + sendNanos;
    }
}
