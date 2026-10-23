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

package org.ngengine.nostr4j.nip53;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.ngengine.nostr4j.event.NostrEvent;
import org.ngengine.nostr4j.event.SignedNostrEvent;
import org.ngengine.nostr4j.event.UnsignedNostrEvent;
import org.ngengine.nostr4j.keypair.NostrPublicKey;
import org.ngengine.platform.AsyncTask;
import org.ngengine.platform.NGEUtils;

/**
 * Parse NIP-53 live activity announcements and construct public chat and presence events.
 * Callers must verify remote event signatures before parsing their contents.
 */
public final class Nip53 {

    /**
     * Verify that the named participant signed the activity coordinate in its participation tag.
     * A participant tag without a valid proof does not establish participation.
     *
     * @param activity the live activity carrying the participant tag
     * @param participant the participant public key in hexadecimal form
     * @return a task indicating whether the participation proof is valid
     */
    public static AsyncTask<Boolean> participationProof(SignedNostrEvent activity, String participant) {
        try {
            String coordinate = coordinate(activity);
            for (List<String> row : activity.getTagRows()) if (
                row.size() > 4 &&
                row.get(0).equals("p") &&
                row.get(1).equals(participant) &&
                row.get(4).matches("[a-f0-9]{128}")
            ) return NGEUtils
                .getPlatform()
                .schnorrVerifyAsync(
                    NGEUtils.getPlatform().sha256(coordinate),
                    row.get(4),
                    NostrPublicKey.fromHex(participant).asReadOnlyBuffer()
                );
            return AsyncTask.completed(false);
        } catch (Exception invalid) {
            return AsyncTask.completed(false);
        }
    }

    public static final int STREAM = 30311, SPACE = 30312, MEETING = 30313, CHAT = 1311, PRESENCE = 10312;

    private Nip53() {}

    public static final class Activity {

        public final int kind;
        public final String coordinate, identifier, author, title, summary, image, status;
        public final String streaming, recording, service, parent, interactiveServer;
        public final long starts, participants;
        public final List<String> relays;

        private Activity(SignedNostrEvent event) {
            kind = event.getKind();
            if (kind != STREAM && kind != SPACE && kind != MEETING) throw new IllegalArgumentException("Not a live activity");
            author = event.getPubkey().asHex();
            identifier = value(event, "d");
            if (identifier.isBlank() || identifier.length() > 1024) throw new IllegalArgumentException(
                "Missing live identifier"
            );
            coordinate = kind + ":" + author + ":" + identifier;
            title = value(event, kind == SPACE ? "room" : "title");
            summary = value(event, "summary");
            image = web(value(event, "image"));
            String playback = "", roomServer = "";
            for (List<String> row : event.getTagRows()) if (row.size() > 1 && row.get(0).equals("streaming")) {
                String stream = stream(row.get(1));
                if (stream.startsWith("livekit+")) roomServer = stream.substring(8); else if (playback.isEmpty()) playback =
                    stream;
            }
            streaming = playback;
            interactiveServer = roomServer;
            recording = web(value(event, "recording"));
            service = web(value(event, "service"));
            parent = value(event, "a");
            String advertised = value(event, "status");
            status =
                advertised.equals("live") && event.getCreatedAt().isBefore(Instant.now().minusSeconds(3600))
                    ? "ended"
                    : advertised;
            starts = number(value(event, "starts"));
            participants = number(value(event, "current_participants"));
            List<String> hints = new ArrayList<>();
            for (List<String> row : event.getTagRows()) if (!row.isEmpty() && row.get(0).equals("relays")) for (
                int i = 1;
                i < row.size() && hints.size() < 8;
                i++
            ) {
                String relay = relay(row.get(i));
                if (!relay.isEmpty() && !hints.contains(relay)) hints.add(relay);
            }
            relays = List.copyOf(hints);
        }

        public boolean interactive() {
            return kind == SPACE || !interactiveServer.isEmpty();
        }

        public String playback() {
            return status.equals("ended") ? recording : streaming;
        }
    }

    public static Activity activity(SignedNostrEvent event) {
        return new Activity(event);
    }

    public static String coordinate(SignedNostrEvent event) {
        return activity(event).coordinate;
    }

    public static UnsignedNostrEvent chat(String coordinate, String relay, String message) {
        requireCoordinate(coordinate);
        if (message == null || message.isBlank() || message.length() > 8000) throw new IllegalArgumentException(
            "Enter a chat message (up to 8000 characters)"
        );
        return new UnsignedNostrEvent().withKind(CHAT).withContent(message).withTag("a", coordinate, relay(relay), "root");
    }

    /**
     * Create a public listener presence event that expires after two minutes.
     * Use a stable session identity to sign successive presence updates.
     *
     * @param coordinate the live activity coordinate
     * @param relay the relay hint for the activity
     * @param raisedHand whether to include a raised-hand tag
     * @return the unsigned presence event
     */
    public static UnsignedNostrEvent presence(String coordinate, String relay, boolean raisedHand) {
        requireCoordinate(coordinate);
        UnsignedNostrEvent event = new UnsignedNostrEvent()
            .withKind(PRESENCE)
            .withTag("a", coordinate, relay(relay), "root")
            .withExpiration(Instant.now().plusSeconds(120));
        if (raisedHand) event.withTag("hand", "1");
        return event;
    }

    public static void requireCoordinate(String coordinate) {
        String[] parts = coordinate == null ? new String[0] : coordinate.split(":", 3);
        if (
            parts.length != 3 ||
            !List.of("30311", "30312", "30313").contains(parts[0]) ||
            !parts[1].matches("[a-f0-9]{64}") ||
            parts[2].isBlank() ||
            parts[2].length() > 1024
        ) throw new IllegalArgumentException("Invalid live activity coordinate");
    }

    /**
     * Validate a web URL before offering it to a browser.
     * The caller remains responsible for obtaining consent before opening the resource.
     *
     * @param value the URL to validate
     * @return the validated URL, or an empty string when it is rejected
     */
    public static String web(String value) {
        return url(value, false);
    }

    public static String relay(String value) {
        return url(value, true);
    }

    private static String stream(String value) {
        if (value.startsWith("wss+livekit://")) value = "livekit+wss://" + value.substring(14);
        if (value.startsWith("livekit+")) {
            String endpoint = relay(value.substring(8));
            return endpoint.isEmpty() ? "" : "livekit+" + endpoint;
        }
        return web(value);
    }

    private static String url(String value, boolean socket) {
        if (
            value == null || value.isBlank() || value.length() > 4096 || value.chars().anyMatch(c -> c <= 32 || c == '\\')
        ) return "";
        try {
            URI uri = URI.create(value);
            String scheme = uri.getScheme();
            boolean local = List.of("localhost", "127.0.0.1", "[::1]").contains(uri.getHost() == null ? "" : uri.getHost());
            if (
                uri.getHost() == null ||
                uri.getUserInfo() != null ||
                uri.getFragment() != null ||
                !(
                    socket
                        ? ("wss".equals(scheme) || local && "ws".equals(scheme))
                        : ("https".equals(scheme) || local && "http".equals(scheme))
                )
            ) return "";
            return uri.toString();
        } catch (IllegalArgumentException invalid) {
            return "";
        }
    }

    private static String value(NostrEvent event, String name) {
        return NGEUtils.safeString(event.getFirstTagFirstValue(name));
    }

    private static long number(String value) {
        try {
            return Math.max(0, NGEUtils.safeLong(value));
        } catch (IllegalArgumentException invalid) {
            return 0;
        }
    }
}
