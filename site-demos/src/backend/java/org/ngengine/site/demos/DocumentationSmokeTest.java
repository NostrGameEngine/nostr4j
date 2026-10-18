/* BSD 3-Clause License - Copyright (c) 2026, Riccardo Balbo */
package org.ngengine.site.demos;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import org.ngengine.nostr4j.NostrFilter;
import org.ngengine.nostr4j.store.NostrEventCache;
import org.ngengine.nostr4j.store.NostrInMemoryEventStore;
import org.ngengine.nostr4j.event.SignedNostrEvent;
import org.ngengine.nostr4j.event.UnsignedNostrEvent;
import org.ngengine.nostr4j.keypair.NostrKeyPair;
import org.ngengine.nostr4j.nip59.Nip59;
import org.ngengine.nostr4j.signer.NostrKeyPairSigner;
import org.ngengine.platform.NGEPlatform;
import org.ngengine.platform.jvm.JVMAsyncPlatform;

/** Runs the event/filter documentation's local operations without network access. */
public final class DocumentationSmokeTest {

    public static void main(String[] args) throws Exception {
        NGEPlatform.set(new JVMAsyncPlatform());
        try (NostrKeyPair keys = new NostrKeyPair()) {
            NostrKeyPairSigner signer = new NostrKeyPairSigner(keys);
            UnsignedNostrEvent draft = new UnsignedNostrEvent()
                .withKind(1)
                .createdAt(Instant.ofEpochSecond(1_700_000_000L))
                .withContent("Hello, Nostr 🌍\n\"quoted\" \\ path")
                .withTag("t", "java", "later-value");
            SignedNostrEvent event = signer.sign(draft).await();
            String json = event.toEventJSON();
            SignedNostrEvent parsed = SignedNostrEvent.fromJSON(json);
            if (!parsed.verify() || !event.getId().equals(parsed.getId())
                    || !event.getContent().equals(parsed.getContent())
                    || !event.getTagRows().equals(parsed.getTagRows())) {
                throw new AssertionError("Event JSON round trip changed signed data");
            }
            if (!json.startsWith("{") || !json.equals(event.toEventJSON())) {
                throw new AssertionError("Event JSON must be a stable object without a relay envelope");
            }
            draft.withContent("edited").withTag("t", "edited");
            if (!json.equals(event.toEventJSON()) || !event.verify()) {
                throw new AssertionError("Editing a draft changed its signed event");
            }
            SignedNostrEvent edited = signer.sign(draft).await();
            if (!edited.verify() || event.getId().equals(edited.getId())) {
                throw new AssertionError("Reusing the signer did not sign the edited data");
            }

            NostrFilter filter = new NostrFilter().withKind(1).withTag("t", "java").limit(1);
            Predicate<SignedNostrEvent> accepts = filter.prepare();
            filter.withTag("t", "other");
            if (!accepts.test(event) || !accepts.test(event) || filter.matches(event)) {
                throw new AssertionError("Prepared filter must snapshot criteria without counting matches");
            }
            if (new NostrFilter().withKind(1).limit(1).matches(event, 1)) {
                throw new AssertionError("Explicit local match count did not enforce the limit");
            }
            NostrFilter later = new NostrFilter().withTag("t", "later-value");
            if (later.prepare().test(event) || !later.prepare(true).test(event)) {
                throw new AssertionError("Local tag matching did not respect first-value semantics");
            }
            SignedNostrEvent tampered = SignedNostrEvent.fromJSON(json.replace("Hello, Nostr", "Changed"));
            if (tampered.verify()) {
                throw new AssertionError("Parsing JSON authenticated tampered data");
            }

            NostrEventCache cache = new NostrEventCache(new NostrInMemoryEventStore(4));
            cache.put(event).await();
            if (!event.getId().equals(cache.get(event.getId()).await().getId())
                    || cache.query(List.of(new NostrFilter().withKind(1).limit(1))).await().size() != 1) {
                throw new AssertionError("Documented cache insertion/query did not retain the signed event");
            }
            boolean rejected = false;
            try {
                cache.put(tampered).await();
            } catch (Exception invalid) {
                rejected = true;
            }
            if (!rejected) throw new AssertionError("Cache accepted a tampered event");
            cache.clear().await();
            if (cache.size().await() != 0) throw new AssertionError("Cache clear retained payloads");

            try (NostrKeyPair recipientKeys = new NostrKeyPair()) {
                NostrKeyPairSigner recipient = new NostrKeyPairSigner(recipientKeys);
                try {
                    UnsignedNostrEvent rumor = new UnsignedNostrEvent()
                        .withKind(14)
                        .withTag("p", recipientKeys.getPublicKey().asHex())
                        .withContent("Gift-wrap documentation check");
                    SignedNostrEvent wrap = Nip59.wrap(signer, rumor, recipientKeys.getPublicKey()).await();
                    Map<String, Object> opened = Nip59.unwrap(recipient, wrap).await();
                    if (!Nip59.isGiftWrap(wrap) || !wrap.verify()
                            || !rumor.getContent().equals(opened.get("content"))
                            || !keys.getPublicKey().asHex().equals(opened.get("pubkey"))) {
                        throw new AssertionError("Gift-wrap round trip lost the authenticated rumor");
                    }
                } finally {
                    recipient.close();
                }
            }
            signer.close();
        }
        System.out.println("DocumentationSmokeTest: ALL CHECKS PASSED");
    }
}
