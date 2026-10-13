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

package org.ngengine.nostr4j.nip59;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.ngengine.nostr4j.event.NostrEvent;
import org.ngengine.nostr4j.event.SignedNostrEvent;
import org.ngengine.nostr4j.event.UnsignedNostrEvent;
import org.ngengine.nostr4j.keypair.NostrKeyPair;
import org.ngengine.nostr4j.keypair.NostrPublicKey;
import org.ngengine.nostr4j.signer.NostrKeyPairSigner;
import org.ngengine.nostr4j.signer.NostrSigner;
import org.ngengine.platform.AsyncTask;
import org.ngengine.platform.NGEUtils;

/**
 * Creates and opens NIP-59 gift wraps containing unsigned rumor events.
 * The author signs an encrypted seal; a fresh disposable key signs the outer envelope.
 * Unwrapping verifies both signatures and the rumor's author and event ID binding.
 * Publishing, relay selection, inbox filtering and retention remain the caller's responsibility.
 */
public final class Nip59 {

    public static final int SEAL_KIND = 13;
    public static final int GIFT_WRAP_KIND = 1059;
    public static final int EPHEMERAL_GIFT_WRAP_KIND = 21059;
    private static final int MAX_ENVELOPE_LENGTH = 100_000;

    private Nip59() {}

    /**
     * Identifies regular and ephemeral gift-wrap envelopes by their event kind.
     * This does not verify signatures, recipient tags or encrypted contents.
     * Use unwrap() to validate and decrypt an envelope before trusting its rumor.
     *
     * @param event event to classify, or null
     * @return whether the event has a supported gift-wrap kind
     */
    public static boolean isGiftWrap(NostrEvent event) {
        return event != null && (event.getKind() == GIFT_WRAP_KIND || event.getKind() == EPHEMERAL_GIFT_WRAP_KIND);
    }

    /**
     * Encrypts an unsigned event for one recipient, using a fresh outer key.
     * To retain sent messages, wrap the same event independently for the sender.
     * The input event is cloned before asynchronous work starts. Outer keys are destroyed
     * after success or failure, and no event is published by this method.
     *
     * @param sender caller-owned signer for the rumor author and its seal
     * @param event unsigned rumor whose content and tags are retained
     * @param recipient public key that can decrypt the gift wrap
     * @return signed kind-1059 envelope ready for caller-controlled publication
     */
    public static AsyncTask<SignedNostrEvent> wrap(NostrSigner sender, UnsignedNostrEvent event, NostrPublicKey recipient) {
        Objects.requireNonNull(sender, "sender");
        Objects.requireNonNull(recipient, "recipient");
        UnsignedNostrEvent snapshot = Objects.requireNonNull(event, "event").clone();
        return sender
            .getPublicKey()
            .compose(author -> {
                Map<String, Object> rumor = new LinkedHashMap<>();
                rumor.put("pubkey", author.asHex());
                rumor.put("kind", snapshot.getKind());
                rumor.put("created_at", snapshot.getCreatedAt().getEpochSecond());
                rumor.put("content", snapshot.getContent());
                rumor.put("tags", snapshot.getTagRows());
                rumor.put("id", Objects.requireNonNull(NostrEvent.computeEventId(author.asHex(), snapshot)));
                String json = NGEUtils.getPlatform().toJSON(rumor);
                if (json.getBytes(StandardCharsets.UTF_8).length > 48_000) {
                    throw new IllegalArgumentException("Rumor exceeds the encrypted envelope limit");
                }
                return sender.encrypt(json, recipient, NostrSigner.EncryptAlgo.NIP44);
            })
            .compose(content ->
                sender.sign(new UnsignedNostrEvent().withKind(SEAL_KIND).withContent(content).createdAt(randomPastTime()))
            )
            .compose(seal -> {
                NostrKeyPair ephemeralKey = new NostrKeyPair();
                NostrKeyPairSigner ephemeral = new NostrKeyPairSigner(ephemeralKey);
                AsyncTask<SignedNostrEvent> wrapped = ephemeral
                    .encrypt(NGEUtils.getPlatform().toJSON(seal.toMap()), recipient, NostrSigner.EncryptAlgo.NIP44)
                    .compose(content ->
                        ephemeral.sign(
                            new UnsignedNostrEvent()
                                .withKind(GIFT_WRAP_KIND)
                                .withTag("p", recipient.asHex())
                                .withContent(content)
                                .createdAt(randomPastTime())
                        )
                    )
                    .then(result -> {
                        ephemeralKey.destroy();
                        return result;
                    });
                wrapped.catchException(error -> ephemeralKey.destroy());
                return wrapped;
            });
    }

    /**
     * Verifies both signatures, recipient, inner event ID and author binding.
     * The returned immutable rumor deliberately has no signature. Never publish it.
     * Both regular and ephemeral gift wraps are accepted. The recipient tag must match
     * exactly once; malformed envelopes and failed bindings reject the returned task.
     *
     * @param recipient caller-owned signer that decrypts the envelope and seal
     * @param giftWrap signed outer envelope addressed to the recipient
     * @return immutable unsigned rumor map after all validation has completed
     */
    @SuppressWarnings("unchecked")
    public static AsyncTask<Map<String, Object>> unwrap(NostrSigner recipient, SignedNostrEvent giftWrap) {
        Objects.requireNonNull(recipient, "recipient");
        Objects.requireNonNull(giftWrap, "giftWrap");
        if (
            !isGiftWrap(giftWrap) ||
            giftWrap.getContent().length() > MAX_ENVELOPE_LENGTH
        ) {
            return AsyncTask.failed(new IllegalArgumentException("Invalid gift wrap envelope"));
        }
        return recipient
            .getPublicKey()
            .compose(publicKey -> {
                long matches = giftWrap
                    .getTagRows()
                    .stream()
                    .filter(tag -> tag.size() >= 2 && "p".equals(tag.get(0)) && publicKey.asHex().equals(tag.get(1)))
                    .count();
                if (matches != 1) throw new IllegalArgumentException("Gift wrap is not addressed to this recipient");
                return giftWrap.verifyAsync();
            })
            .compose(valid -> {
                if (!valid) throw new IllegalArgumentException("Invalid gift wrap signature");
                return recipient.decrypt(giftWrap.getContent(), giftWrap.getPubkey(), NostrSigner.EncryptAlgo.NIP44);
            })
            .compose(json -> {
                if (json.length() > MAX_ENVELOPE_LENGTH) throw new IllegalArgumentException("Seal exceeds limit");
                SignedNostrEvent seal = new SignedNostrEvent(NGEUtils.getPlatform().fromJSON(json, Map.class));
                if (seal.getKind() != SEAL_KIND || !seal.getTagRows().isEmpty()) {
                    throw new IllegalArgumentException("Invalid seal kind or tags");
                }
                return seal
                    .verifyAsync()
                    .compose(valid -> {
                        if (!valid) throw new IllegalArgumentException("Invalid seal signature");
                        return recipient.decrypt(seal.getContent(), seal.getPubkey(), NostrSigner.EncryptAlgo.NIP44);
                    })
                    .then(rumorJson -> {
                        Map<String, Object> raw = NGEUtils.getPlatform().fromJSON(rumorJson, Map.class);
                        if (raw.containsKey("sig")) throw new IllegalArgumentException("A rumor must be unsigned");
                        // The parser retains original tag order, including interleaved names.
                        SignedNostrEvent rumor = new SignedNostrEvent(raw);
                        if (!rumor.getPubkey().equals(seal.getPubkey())) {
                            throw new IllegalArgumentException("Rumor author does not match its seal");
                        }
                        if (!rumor.getId().equals(NostrEvent.computeEventId(rumor.getPubkey().asHex(), rumor))) {
                            throw new IllegalArgumentException("Invalid rumor event ID");
                        }
                        Map<String, Object> result = new LinkedHashMap<>(rumor.toMap());
                        result.remove("sig");
                        return Collections.unmodifiableMap(result);
                    });
            });
    }

    private static Instant randomPastTime() {
        byte[] random = NGEUtils.getPlatform().randomBytes(4);
        long value = 0;
        for (byte b : random) value = (value << 8) | (b & 255);
        return Instant.now().minusSeconds(value % (2 * 24 * 60 * 60));
    }
}
