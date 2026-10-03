/**
 * BSD 3-Clause License
 *
 * Copyright (c) 2025, Riccardo Balbo
 */
package org.ngengine.nostr4j.unit;

import java.util.List;
import org.junit.Assert;
import org.junit.Test;
import org.ngengine.nostr4j.event.UnsignedNostrEvent;
import org.ngengine.nostr4j.nip01.Nip01UserMetadata;
import org.ngengine.nostr4j.nip39.ExternalIdentity;
import org.ngengine.nostr4j.nip39.Nip39ExternalIdentities;

public class TestNip39ExternalIdentities {

    @Test
    public void testEmptyMetadataWithoutSourceEvent() {
        Nip39ExternalIdentities identities = new Nip39ExternalIdentities(new Nip01UserMetadata());

        Assert.assertNull(identities.getSourceEvent());
        Assert.assertTrue(identities.getExternalIdentities().isEmpty());
        Assert.assertNull(identities.getExternalIdentity("gamertag"));
    }

    @Test
    public void testIdentityIsReadWithoutExplicitInitialization() {
        UnsignedNostrEvent event = new UnsignedNostrEvent();
        event.withKind(0);
        event.withContent("{}");
        event.withTag("i", List.of("gamertag:example", "proof"));

        Nip39ExternalIdentities identities = new Nip39ExternalIdentities(event);
        ExternalIdentity identity = identities.getExternalIdentity("gamertag");

        Assert.assertNotNull(identity);
        Assert.assertEquals("example", identity.getIdentity());
        Assert.assertEquals(List.of("proof"), identity.getProof());
    }

    @Test
    public void testAbsentAndMalformedIdentityTagsAreIgnored() {
        UnsignedNostrEvent event = new UnsignedNostrEvent().withKind(0).withContent("{}");
        Assert.assertTrue(new Nip39ExternalIdentities(event).getExternalIdentities().isEmpty());
        event.withTag("i", List.of("broken"));
        event.withTag("i", List.of(":empty"));
        event.withTag("i", List.of("github:"));
        event.withTag("i", List.of("github:alice:extra", "proof"));
        List<ExternalIdentity> identities = new Nip39ExternalIdentities(event).getExternalIdentities();
        Assert.assertEquals(1, identities.size());
        Assert.assertEquals("alice:extra", identities.get(0).getIdentity());
    }
}
