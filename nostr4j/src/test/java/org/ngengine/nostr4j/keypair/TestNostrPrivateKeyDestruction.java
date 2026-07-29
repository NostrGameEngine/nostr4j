/**
 * BSD 3-Clause License
 *
 * Copyright (c) 2025, Riccardo Balbo
 */
package org.ngengine.nostr4j.keypair;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.Arrays;
import org.junit.Test;

public class TestNostrPrivateKeyDestruction {

    @Test
    public void testDestroyOverwritesExposedBytesAndInvalidatesPrivateOperations() {
        NostrPrivateKey key = NostrPrivateKey.generate();
        byte[] exposed = key._array();
        key.preload();

        key.destroy();
        key.destroy();

        assertTrue(key.isDestroyed());
        assertTrue("equals must remain reflexive after destruction", key.equals(key));
        assertArrayEquals(new byte[exposed.length], exposed);
        assertDestroyed(() -> key.asHex());
        assertDestroyed(() -> key.asBech32());
        assertDestroyed(() -> key._array());
        assertDestroyed(() -> key.getPublicKey());
    }

    @Test
    public void testCloneOwnsIndependentPrivateStorage() {
        NostrPrivateKey original = NostrPrivateKey.generate();
        byte[] expected = Arrays.copyOf(original._array(), original._array().length);
        NostrPrivateKey copy = original.clone();

        copy.destroy();

        assertTrue(copy.isDestroyed());
        assertFalse(original.isDestroyed());
        assertArrayEquals(expected, original._array());
        original.destroy();
    }

    @Test
    public void testKeyPairRetainsPublicKeyAfterPrivateDestruction() {
        NostrKeyPair pair = new NostrKeyPair();
        NostrPublicKey publicKey = pair.getPublicKey();

        pair.close();

        assertTrue(pair.isDestroyed());
        assertTrue(publicKey.equals(pair.getPublicKey()));
        assertTrue(pair.toString().contains("<destroyed>"));
    }

    private static void assertDestroyed(Runnable operation) {
        try {
            operation.run();
            fail("destroyed private-key operation must fail");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("destroyed"));
        }
    }
}
