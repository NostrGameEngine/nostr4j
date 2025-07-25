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
import java.nio.ByteBuffer;
import org.junit.Test;
import org.ngengine.nostr4j.keypair.NostrKeyPair;
import org.ngengine.nostr4j.keypair.NostrPrivateKey;
import org.ngengine.nostr4j.keypair.NostrPublicKey;
import org.ngengine.platform.NGEUtils;

public class TestNostrKeyPair {

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
