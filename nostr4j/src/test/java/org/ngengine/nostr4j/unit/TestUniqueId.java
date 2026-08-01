package org.ngengine.nostr4j.unit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.HashSet;
import java.util.Set;
import org.junit.Test;
import org.ngengine.nostr4j.utils.UniqueId;

public class TestUniqueId {

    @Test
    public void identifiersUseFreshPlatformEntropy() {
        Set<String> identifiers = new HashSet<>();

        for (int i = 0; i < 1024; i++) {
            String identifier = UniqueId.getNext();
            assertTrue(identifier.matches("nostr4j[0-9]+j[0-9a-f]{32}"));
            assertTrue("identifier collision", identifiers.add(identifier));
        }

        assertEquals(1024, identifiers.size());
    }
}
