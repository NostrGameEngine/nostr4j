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
package org.ngengine.nostr4j.unit;

import static org.junit.Assert.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import org.junit.Test;
import org.ngengine.nostr4j.keypair.NostrKeyPair;
import org.ngengine.nostr4j.keypair.NostrPrivateKey;
import org.ngengine.nostr4j.keypair.NostrPublicKey;
import org.ngengine.platform.NGEUtils;
import org.ngengine.platform.SafeFlag;

public class TestNostrKeyPair {

    private static final String INVALID_PUBLIC_KEY_HEX = "1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef";

    @Test
    public void testHexKeys() throws Exception {
        String privhex = "c4bce2353ae83bd2f1ea31f75c18317d383ac63072085231d7f370582ed7a651";
        String pubhex = "f115b2e070b81abed59186904bca89415edc8aee806087c0fe4cbf7997d98ca9";
        String privbech32 = "nsec1cj7wydf6aqaa9u02x8m4cxp305ur433swgy9yvwh7dc9stkh5egs5x9s2k";
        String pubbech32 = "npub17y2m9crshqdta4v3s6gyhj5fg90dezhwspsg0s87fjlhn97e3j5svczpjj";
        String privBytes =
            "-60 -68 -30 53 58 -24 59 -46 -15 -22 49 -9 92 24 49 125 56 58 -58 48 114 8 82 49 -41 -13 112 88 46 -41 -90 81";
        String pubBytes =
            "-15 21 -78 -32 112 -72 26 -66 -43 -111 -122 -112 75 -54 -119 65 94 -36 -118 -18 -128 96 -121 -64 -2 76 -65 121 -105 -39 -116 -87";

        NostrPrivateKey privKey = NostrPrivateKey.fromHex(privhex);
        NostrPublicKey pubKey = NostrPublicKey.fromHex(pubhex);

        assertNotNull(privKey);
        assertNotNull(pubKey);
        assertEquals(privKey.asHex(), privhex);
        assertEquals(pubKey.asHex(), pubhex);
        assertEquals(bytesString(privKey._array()), privBytes);
        assertEquals(bytesString(pubKey._array()), pubBytes);

        assertEquals(privKey.asBech32(), privbech32);
        assertEquals(pubKey.asBech32(), pubbech32);

        NostrPublicKey derivedPubKey = privKey.getPublicKey();
        assertEquals(derivedPubKey.asHex(), pubhex);
        assertEquals(derivedPubKey.asBech32(), pubbech32);
        assertEquals(bytesString(derivedPubKey._array()), pubBytes);

        byte arrayFromInternal[] = privKey._array();
        byte arrayFromHex[] = NGEUtils.hexToByteArray(privKey.asHex());
        assertArrayEquals(arrayFromInternal, arrayFromHex);
    }

    @Test
    public void testBech32Keys() throws Exception {
        String privhex = "c4bce2353ae83bd2f1ea31f75c18317d383ac63072085231d7f370582ed7a651";
        String pubhex = "f115b2e070b81abed59186904bca89415edc8aee806087c0fe4cbf7997d98ca9";
        String privbech32 = "nsec1cj7wydf6aqaa9u02x8m4cxp305ur433swgy9yvwh7dc9stkh5egs5x9s2k";
        String pubbech32 = "npub17y2m9crshqdta4v3s6gyhj5fg90dezhwspsg0s87fjlhn97e3j5svczpjj";

        NostrPrivateKey privKey = NostrPrivateKey.fromBech32(privbech32);
        NostrPublicKey pubKey = NostrPublicKey.fromBech32(pubbech32);

        assertNotNull(privKey);
        assertNotNull(pubKey);

        assertEquals(privKey.asBech32(), privbech32);
        assertEquals(pubKey.asBech32(), pubbech32);

        assertEquals(privKey.asHex(), privhex);
        assertEquals(pubKey.asHex(), pubhex);

        NostrPublicKey derivedPubKey = privKey.getPublicKey();

        assertEquals(derivedPubKey.asBech32(), pubbech32);
    }

    @Test
    public void testSerialization() throws Exception {
        String privhex = "c4bce2353ae83bd2f1ea31f75c18317d383ac63072085231d7f370582ed7a651";
        String pubhex = "f115b2e070b81abed59186904bca89415edc8aee806087c0fe4cbf7997d98ca9";
        String privbech32 = "nsec1cj7wydf6aqaa9u02x8m4cxp305ur433swgy9yvwh7dc9stkh5egs5x9s2k";
        String pubbech32 = "npub17y2m9crshqdta4v3s6gyhj5fg90dezhwspsg0s87fjlhn97e3j5svczpjj";

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ObjectOutputStream oos = new ObjectOutputStream(baos);

        // serialize
        NostrPrivateKey privKey = NostrPrivateKey.fromHex(privhex);
        NostrPublicKey pubKey = NostrPublicKey.fromHex(pubhex);
        NostrKeyPair kp = new NostrKeyPair(privKey, pubKey);
        oos.writeObject(kp);
        oos.close();
        byte[] serialized = baos.toByteArray();
        assertNotNull(serialized);
        assertTrue(serialized.length > 0);

        // deserialize
        ByteArrayInputStream bais = new ByteArrayInputStream(serialized);
        ObjectInputStream ois = new ObjectInputStream(bais);
        NostrKeyPair kp2 = (NostrKeyPair) ois.readObject();
        assertNotNull(kp2);
        ois.close();

        // checks
        assertEquals(kp.getPrivateKey().asHex(), privhex);
        assertEquals(kp.getPublicKey().asHex(), pubhex);
        assertEquals(kp.getPrivateKey().asBech32(), privbech32);
        assertEquals(kp.getPublicKey().asBech32(), pubbech32);

        assertEquals(kp2.getPrivateKey().asHex(), privhex);
        assertEquals(kp2.getPublicKey().asHex(), pubhex);
        assertEquals(kp2.getPrivateKey().asBech32(), privbech32);
        assertEquals(kp2.getPublicKey().asBech32(), pubbech32);

        assertEquals(kp, kp2);
        assertEquals(kp.getPrivateKey(), kp2.getPrivateKey());
        assertEquals(kp.getPublicKey(), kp2.getPublicKey());
    }

    @Test
    public void testReadOnlyBufferViewsAreIndependent() {
        NostrPrivateKey privateKey = NostrPrivateKey.fromHex(
            "c4bce2353ae83bd2f1ea31f75c18317d383ac63072085231d7f370582ed7a651"
        );
        NostrPublicKey publicKey = privateKey.getPublicKey();

        ByteBuffer privateView = privateKey.asReadOnlyBuffer();
        ByteBuffer publicView = publicKey.asReadOnlyBuffer();
        assertTrue(privateView.isReadOnly());
        assertTrue(publicView.isReadOnly());
        assertArrayEquals(privateKey._array(), bytes(privateView));
        assertArrayEquals(publicKey._array(), bytes(publicView));

        privateView.position(7);
        publicView.position(11);
        assertEquals(0, privateKey.asReadOnlyBuffer().position());
        assertEquals(0, publicKey.asReadOnlyBuffer().position());
    }

    @Test
    public void testReadOnlyBufferPreservesDirectStorage() {
        ByteBuffer direct = ByteBuffer.allocateDirect(32);
        for (int i = 0; i < direct.capacity(); i++) {
            direct.put((byte) i);
        }
        direct.flip();

        NostrPublicKey publicKey = new NostrPublicKey(direct, false);
        ByteBuffer view = publicKey.asReadOnlyBuffer();
        assertTrue(view.isDirect());
        assertTrue(view.isReadOnly());
        assertEquals(32, view.remaining());
    }

    @Test
    public void testPublicKeyVerificationDefaultsToTrue() {
        byte[] invalid = NGEUtils.hexToByteArray(INVALID_PUBLIC_KEY_HEX);
        String invalidNpub = NostrPublicKey.fromHex(INVALID_PUBLIC_KEY_HEX, false).asBech32();

        expectInvalidPublicKey(() -> NostrPublicKey.fromHex(INVALID_PUBLIC_KEY_HEX));
        expectInvalidPublicKey(() -> NostrPublicKey.fromBytes(invalid));
        expectInvalidPublicKey(() -> NostrPublicKey.fromBytes(ByteBuffer.wrap(invalid)));
        expectInvalidPublicKey(() -> new NostrPublicKey(ByteBuffer.wrap(invalid)));
        expectInvalidPublicKey(() -> NostrPublicKey.fromBech32(invalidNpub));
        expectInvalidPublicKey(() -> NostrPublicKey.fromNpub(invalidNpub));
    }

    @Test
    public void testPublicKeyVerificationCanBeDisabled() {
        byte[] invalid = NGEUtils.hexToByteArray(INVALID_PUBLIC_KEY_HEX);
        String invalidNpub = NostrPublicKey.fromHex(INVALID_PUBLIC_KEY_HEX, false).asBech32();

        assertEquals(INVALID_PUBLIC_KEY_HEX, NostrPublicKey.fromHex(INVALID_PUBLIC_KEY_HEX, false).asHex());
        assertEquals(INVALID_PUBLIC_KEY_HEX, NostrPublicKey.fromBytes(invalid, false).asHex());
        assertEquals(INVALID_PUBLIC_KEY_HEX, NostrPublicKey.fromBytes(ByteBuffer.wrap(invalid), false).asHex());
        assertEquals(INVALID_PUBLIC_KEY_HEX, new NostrPublicKey(ByteBuffer.wrap(invalid), false).asHex());
        assertEquals(INVALID_PUBLIC_KEY_HEX, NostrPublicKey.fromBech32(invalidNpub, false).asHex());
        assertEquals(INVALID_PUBLIC_KEY_HEX, NostrPublicKey.fromNpub(invalidNpub, false).asHex());
    }

    @Test
    public void testPublicKeyVerificationIsLazyAndCached() throws Exception {
        byte[] valid = NGEUtils.hexToByteArray("79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798");
        byte[] invalid = NGEUtils.hexToByteArray(INVALID_PUBLIC_KEY_HEX);
        NostrPublicKey validPublicKey = NostrPublicKey.fromBytes(valid, false);
        NostrPublicKey invalidPublicKey = NostrPublicKey.fromBytes(invalid, false);
        Field cachedField = NostrPublicKey.class.getDeclaredField("verificationCached");
        Field resultField = NostrPublicKey.class.getDeclaredField("verificationResult");
        cachedField.setAccessible(true);
        resultField.setAccessible(true);
        SafeFlag validCached = (SafeFlag) cachedField.get(validPublicKey);
        SafeFlag validResult = (SafeFlag) resultField.get(validPublicKey);
        SafeFlag invalidCached = (SafeFlag) cachedField.get(invalidPublicKey);
        SafeFlag invalidResult = (SafeFlag) resultField.get(invalidPublicKey);

        assertFalse(validCached.get());
        assertTrue(validPublicKey.verify());
        assertTrue(validCached.get());
        assertTrue(validResult.get());
        assertTrue(validPublicKey.verify());

        assertFalse(invalidCached.get());
        assertFalse(invalidPublicKey.verify());
        assertTrue(invalidCached.get());
        assertFalse(invalidResult.get());
        assertFalse(invalidPublicKey.verify());
    }

    private static void expectInvalidPublicKey(Runnable operation) {
        try {
            operation.run();
            fail("Expected invalid Nostr public key rejection");
        } catch (IllegalArgumentException expected) {}
    }

    private byte[] bytes(ByteBuffer source) {
        ByteBuffer view = source.slice();
        byte[] result = new byte[view.remaining()];
        view.get(result);
        return result;
    }

    private String byteBufferString(ByteBuffer b) {
        String s = "";
        for (int i = 0; i < b.limit(); i++) {
            s += b.get(i) + " ";
        }
        return s.trim();
    }

    private String bytesString(byte[] b) {
        String s = "";
        for (int i = 0; i < b.length; i++) {
            s += b[i] + " ";
        }
        return s.trim();
    }
}
