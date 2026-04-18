/**
 * BSD 3-Clause License
 * Copyright (c) 2025, Riccardo Balbo
 */
package org.ngengine.nostr4j.rtc.turn;

import jakarta.annotation.Nullable;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.ngengine.nostr4j.event.SignedNostrEvent;
import org.ngengine.nostr4j.event.UnsignedNostrEvent;
import org.ngengine.nostr4j.keypair.NostrPrivateKey;
import org.ngengine.nostr4j.rtc.signal.NostrRTCLocalPeer;
import org.ngengine.platform.AsyncTask;
import org.ngengine.platform.NGEPlatform;
import org.ngengine.platform.NGEUtils;

/**
 * TURN challenge event (`t=challenge`).
 */
@SuppressWarnings("unchecked")
public final class NostrTURNChallengeEvent extends NostrTURNEvent {

    private final String challenge;
    private final int requiredDifficulty;
    private final String redirect;

    public static NostrTURNChallengeEvent createChallenge(
        NostrRTCLocalPeer localPeer,
        int difficulty,
        String redirectUrl
    ) {
        return new NostrTURNChallengeEvent(localPeer, difficulty, redirectUrl);
    }

    private NostrTURNChallengeEvent(NostrRTCLocalPeer localPeer, int difficulty,String redirectUrl) {
        // we don't know all the channel info yet, so we just set them to null for this event
        super("challenge", localPeer, null, null, null);
        this.challenge = NostrPrivateKey.generate().asHex();
        this.requiredDifficulty = NGEUtils.safeInt(difficulty);
        this.redirect = normalizeRedirect(redirectUrl);
    }

    public static NostrTURNChallengeEvent parseIncoming(SignedNostrEvent event, NostrRTCLocalPeer localPeer, int maxDiff) {
        return new NostrTURNChallengeEvent(event, localPeer, maxDiff);
    }

    private NostrTURNChallengeEvent(SignedNostrEvent event, NostrRTCLocalPeer localPeer, int maxDiff) {
        // we don't know all the channel info yet, so we just set them to null for this event
        super("challenge", event, localPeer, null, null, null);
        Map<String, Object> content = NGEPlatform.get().fromJSON(event.getContent(), Map.class);
        this.requiredDifficulty = NGEUtils.safeInt(content.get("difficulty"));
        if (this.requiredDifficulty > maxDiff) {
            throw new IllegalArgumentException(
                "Challenge difficulty " + this.requiredDifficulty + " exceeds maximum accepted difficulty of " + maxDiff
            );
        }
        this.challenge = NGEUtils.safeString(content.get("challenge"));
        if (this.challenge.isEmpty()) {
            throw new IllegalArgumentException("Invalid TURN challenge event: missing challenge token");
        }
        this.redirect = normalizeRedirect(event.getFirstTagFirstValue("r"));
    }

    private static String normalizeRedirect(@Nullable String redirectUrl) {
        if (redirectUrl == null || redirectUrl.trim().isEmpty()) {
            return "";
        }
        return NGEUtils.safeURI(redirectUrl).toString().trim();
    }

    public String getRedirect() {
        return redirect;
    }

    public String getChallenge() {
        return challenge;
    }

    public int getRequiredDifficulty() {
        return requiredDifficulty;
    }

    @Override
    protected AsyncTask<UnsignedNostrEvent> computeEvent(UnsignedNostrEvent event) {
        Map<String, Object> content = new HashMap<>();
        content.put("challenge", challenge);
        content.put("difficulty", requiredDifficulty);
        event.withContent(NGEPlatform.get().toJSON(content));
        if (redirect != null && !redirect.isEmpty()) {
            event.withTag("r", redirect);
        }
        return AsyncTask.completed(event);
    }
}
