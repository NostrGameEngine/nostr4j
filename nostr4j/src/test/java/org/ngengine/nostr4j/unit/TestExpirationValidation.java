package org.ngengine.nostr4j.unit;

import static org.junit.Assert.*;

import java.time.Instant;
import org.junit.Test;
import org.ngengine.nostr4j.event.*;
import org.ngengine.nostr4j.keypair.NostrKeyPair;
import org.ngengine.nostr4j.signer.NostrKeyPairSigner;

public class TestExpirationValidation {

    @Test
    public void currentEventsRespectExpirationAndFutureClockSkew() throws Exception {
        Instant now = Instant.ofEpochSecond(1_700_000_000);
        NostrKeyPairSigner signer = new NostrKeyPairSigner(new NostrKeyPair());
        UnsignedNostrEvent event = new UnsignedNostrEvent().createdAt(now.minusSeconds(60));
        assertTrue(event.isCurrent(now));
        assertTrue(signer.sign(event).await().isCurrent(now));
        event.createdAt(now.plusSeconds(30));
        assertTrue(event.isCurrent(now));
        event.createdAt(now.plusSeconds(31));
        assertFalse(event.isCurrent(now));
        assertFalse(signer.sign(event).await().isCurrent(now));
        event.createdAt(now).withExpiration(now.minusSeconds(1));
        assertFalse(event.isCurrent(now));
        event.withExpiration(now.plusSeconds(1));
        assertTrue(event.isCurrent(now));
        event.replaceTag("expiration", "invalid");
        assertFalse(event.isCurrent(now));
    }

    @Test
    public void malformedExpirationFailsClosedWithoutCrashing() throws Exception {
        NostrKeyPairSigner signer = new NostrKeyPairSigner(new NostrKeyPair());
        for (String value : new String[] { "expiration", "-1", "0", "9999999999999999999", "31556889864403200", "12.5" }) {
            UnsignedNostrEvent event = new UnsignedNostrEvent().withTag("expiration", value);
            assertEquals(Instant.EPOCH, event.getExpiration());
            assertEquals(Instant.EPOCH, signer.sign(event).await().getExpiration());
        }
        Instant expected = Instant.now().plusSeconds(120);
        UnsignedNostrEvent valid = new UnsignedNostrEvent().withExpiration(expected);
        assertEquals(Long.toString(expected.getEpochSecond()), valid.getFirstTagFirstValue("expiration"));
        assertEquals(Instant.ofEpochSecond(expected.getEpochSecond()), valid.getExpiration());
        assertFalse(valid.withExpiration(null).isExpired());
    }
}
