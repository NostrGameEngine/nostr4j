/**
 * BSD 3-Clause License
 *
 * Copyright (c) 2026, Riccardo Balbo
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

package org.ngengine.nostr4j.rtc;

import static org.junit.Assert.*;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Test;
import org.ngengine.nostr4j.keypair.NostrKeyPair;
import org.ngengine.nostr4j.rtc.signal.NostrRTCOfferSignal;
import org.ngengine.nostr4j.rtc.signal.NostrRTCPeer;
import org.ngengine.nostr4j.rtc.signal.NostrRTCRouteSignal;
import org.ngengine.nostr4j.signer.NostrKeyPairSigner;
import org.ngengine.platform.NGEUtils;
import org.ngengine.platform.transport.RTCTransportIceCandidate;

public class TestProvisionalPeerSignals {

    @Test
    public void configuredProvisionalQuotasAndFirstSeenLifetimeAreEnforced() {
        try (NostrKeyPair keys = new NostrKeyPair()) {
            AtomicLong clock = new AtomicLong();
            RTCSessionLimits limits = new RTCSessionLimits(2, 1, 12, 32, java.time.Duration.ofMillis(10));
            ProvisionalPeerSignals registry = new ProvisionalPeerSignals(clock::get, limits);
            NostrKeyPairSigner signer = NostrKeyPairSigner.generate();
            NostrRTCPeer first = peer(signer, keys, "first");
            assertTrue(registry.retain(new NostrRTCOfferSignal(signer, keys, first, "offer")));
            assertFalse(registry.retain(new NostrRTCOfferSignal(signer, keys, peer(signer, keys, "second"), "offer")));
            NostrKeyPairSigner other = NostrKeyPairSigner.generate();
            assertTrue(registry.retain(new NostrRTCOfferSignal(other, keys, peer(other, keys, "other"), "offer")));
            NostrKeyPairSigner third = NostrKeyPairSigner.generate();
            assertFalse(registry.retain(new NostrRTCOfferSignal(third, keys, peer(third, keys, "third"), "offer")));
            clock.set(9L);
            assertTrue(registry.retain(new NostrRTCOfferSignal(signer, keys, first, "duplicate")));
            clock.set(10L);
            registry.expire();
            assertEquals(0, registry.size());
        }
    }

    @Test
    public void duplicateAndChangedSignalsDoNotRenewTheFirstSeenLease() {
        try (NostrKeyPair keys = new NostrKeyPair()) {
            AtomicLong clock = new AtomicLong();
            ProvisionalPeerSignals registry = new ProvisionalPeerSignals(clock::get);
            NostrKeyPairSigner signer = NostrKeyPairSigner.generate();
            NostrRTCPeer peer = peer(signer, keys, "one");
            NostrRTCOfferSignal first = new NostrRTCOfferSignal(signer, keys, peer, "first");
            assertTrue(registry.retain(first));
            clock.set(RTCSessionLimits.PROVISIONAL_LIFETIME_MILLIS - 1L);
            assertTrue(registry.retain(new NostrRTCOfferSignal(signer, keys, peer, "changed")));
            assertEquals(1, registry.size());
            clock.incrementAndGet();
            assertNull(registry.take(peer));
            assertEquals(0, registry.size());
        }
    }

    @Test
    public void oneIdentityCannotFillTheProvisionalDirectoryAndExpiryReopensAdmission() {
        try (NostrKeyPair keys = new NostrKeyPair()) {
            AtomicLong clock = new AtomicLong();
            ProvisionalPeerSignals registry = new ProvisionalPeerSignals(clock::get);
            NostrKeyPairSigner signer = NostrKeyPairSigner.generate();
            for (int index = 0; index < 2048; index++) {
                NostrRTCPeer peer = peer(signer, keys, "ghost-" + index);
                assertEquals(
                    index < RTCSessionLimits.MAX_PROVISIONAL_SESSIONS_PER_IDENTITY,
                    registry.retain(new NostrRTCOfferSignal(signer, keys, peer, "offer"))
                );
            }
            assertEquals(RTCSessionLimits.MAX_PROVISIONAL_SESSIONS_PER_IDENTITY, registry.size());
            clock.set(RTCSessionLimits.PROVISIONAL_LIFETIME_MILLIS);
            registry.expire();
            assertEquals(0, registry.size());
            assertTrue(registry.retain(new NostrRTCOfferSignal(signer, keys, peer(signer, keys, "fresh"), "offer")));
        }
    }

    @Test
    public void globalProvisionalBoundDoesNotEvictAnExistingPendingPeer() {
        try (NostrKeyPair keys = new NostrKeyPair()) {
            ProvisionalPeerSignals registry = new ProvisionalPeerSignals(() -> 0L);
            NostrRTCPeer first = null;
            for (int index = 0; index <= RTCSessionLimits.MAX_PROVISIONAL_PEERS; index++) {
                NostrKeyPairSigner signer = NostrKeyPairSigner.generate();
                NostrRTCPeer peer = peer(signer, keys, "session");
                if (first == null) first = peer;
                assertEquals(
                    index < RTCSessionLimits.MAX_PROVISIONAL_PEERS,
                    registry.retain(new NostrRTCOfferSignal(signer, keys, peer, "offer"))
                );
            }
            assertEquals(RTCSessionLimits.MAX_PROVISIONAL_PEERS, registry.size());
            assertNotNull(registry.take(first));
        }
    }

    @Test
    public void promotionTakesTheFirstOfferAndBoundedRoutesOnlyOnce() {
        try (NostrKeyPair keys = new NostrKeyPair()) {
            ProvisionalPeerSignals registry = new ProvisionalPeerSignals(() -> 0L);
            NostrKeyPairSigner signer = NostrKeyPairSigner.generate();
            NostrRTCPeer peer = peer(signer, keys, "session");
            NostrRTCOfferSignal first = new NostrRTCOfferSignal(signer, keys, peer, "first");
            registry.retain(first);
            registry.retain(new NostrRTCOfferSignal(signer, keys, peer, "second"));
            for (int index = 0; index < RTCSessionLimits.MAX_PROVISIONAL_ROUTES + 2; index++) {
                assertTrue(
                    registry.retain(
                        new NostrRTCRouteSignal(signer, keys, peer, List.of(), "ws://offline-" + index + ".invalid")
                    )
                );
            }
            ProvisionalPeerSignals.Pending pending = registry.take(peer);
            assertSame(first, pending.offer);
            assertEquals(RTCSessionLimits.MAX_PROVISIONAL_ROUTES, pending.routes.size());
            assertEquals("ws://offline-2.invalid", pending.routes.get(0).getTurnServer());
            assertNull(registry.take(peer));
            assertEquals(0, registry.size());
        }
    }

    @Test
    public void oversizedProvisionalPayloadsAndSessionIdsAreRejectedBeforeRetention() {
        try (NostrKeyPair keys = new NostrKeyPair()) {
            NostrKeyPairSigner signer = NostrKeyPairSigner.generate();
            NostrRTCPeer peer = peer(signer, keys, "session");
            assertFalse(
                ProvisionalPeerSignals.retainable(
                    new NostrRTCOfferSignal(signer, keys, peer, "x".repeat(RTCSessionLimits.MAX_PROVISIONAL_OFFER_CHARS + 1))
                )
            );
            assertFalse(
                ProvisionalPeerSignals.retainable(
                    new NostrRTCOfferSignal(
                        signer,
                        keys,
                        peer(signer, keys, "x".repeat(RTCSessionLimits.MAX_SESSION_ID_CHARS + 1)),
                        "offer"
                    )
                )
            );
            assertFalse(
                ProvisionalPeerSignals.retainable(
                    new NostrRTCRouteSignal(
                        signer,
                        keys,
                        peer,
                        List.of(
                            new RTCTransportIceCandidate(
                                "x".repeat(RTCSessionLimits.MAX_PROVISIONAL_CANDIDATE_CHARS + 1),
                                "mid"
                            )
                        ),
                        null
                    )
                )
            );
            java.util.ArrayList<RTCTransportIceCandidate> aggregate = new java.util.ArrayList<>();
            for (int index = 0; index < 17; index++) aggregate.add(new RTCTransportIceCandidate("x".repeat(4096), "mid"));
            assertFalse(ProvisionalPeerSignals.retainable(new NostrRTCRouteSignal(signer, keys, peer, aggregate, null)));
            assertTrue(ProvisionalPeerSignals.retainable(new NostrRTCRouteSignal(signer, keys, peer, List.of(), null)));
        }
    }

    static NostrRTCPeer peer(NostrKeyPairSigner signer, NostrKeyPair keys, String session) {
        return new NostrRTCPeer(
            NGEUtils.awaitNoThrow(signer.getPublicKey()),
            "provisional-app",
            "provisional-protocol",
            session,
            keys.getPublicKey(),
            null
        );
    }
}
