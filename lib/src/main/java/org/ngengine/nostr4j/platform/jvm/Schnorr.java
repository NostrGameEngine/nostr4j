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
package org.ngengine.nostr4j.platform.jvm;

import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.Security;
import java.security.interfaces.ECPrivateKey;
import java.security.spec.ECGenParameterSpec;
import org.bouncycastle.jce.provider.BouncyCastleProvider;

// based on https://github.com/tcheeric/nostr-java/blob/main/nostr-java-crypto/src/main/java/nostr/crypto/schnorr/Schnorr.java#L19
// thread-safe
class Schnorr {

    private static final BigInteger ONE = BigInteger.ONE;
    private static final String TAG_AUX = "BIP0340/aux";
    private static final String TAG_NONCE = "BIP0340/nonce";
    private static final String TAG_CHALLENGE = "BIP0340/challenge";

    public static byte[] sign(byte[] msg, byte[] secKey, byte[] auxRand) throws Exception {
        if (msg == null || msg.length != 32) {
            throw new Exception("The message must be a 32-byte array.");
        }
        if (secKey == null) {
            throw new Exception("Secret key cannot be null");
        }

        // Convert secret key to BigInteger
        BigInteger secKey0 = Util.bigIntFromBytes(secKey);

        // Validate secret key range
        if (!(ONE.compareTo(secKey0) <= 0 && secKey0.compareTo(Point.getn().subtract(ONE)) <= 0)) {
            throw new Exception("The secret key must be an integer in the range 1..n-1.");
        }

        // Compute public key point
        Point P = Point.mul(Point.getG(), secKey0);

        // Negate secret key if the public key's Y coordinate is not even
        if (!P.hasEvenY()) {
            secKey0 = Point.getn().subtract(secKey0);
        }

        // Convert secret key to bytes (allocate only once)
        byte[] secKeyBytes = Util.bytesFromBigInteger(secKey0);

        // Get public key bytes
        byte[] pubKeyBytes = P.toBytes();

        byte[] t;
        if (auxRand == null) {
            t = secKeyBytes;
        } else {
            byte thash[] = Point.taggedHash(TAG_AUX, auxRand);
            t = Util.xor(secKeyBytes, thash, thash);
        }

        if (t == null) {
            throw new RuntimeException("Unexpected error. Null array");
        }

        // Create buffer for nonce generation (d || P || m)
        byte[] nonceBuffer = new byte[t.length + pubKeyBytes.length + msg.length];
        System.arraycopy(t, 0, nonceBuffer, 0, t.length);
        System.arraycopy(pubKeyBytes, 0, nonceBuffer, t.length, pubKeyBytes.length);
        System.arraycopy(msg, 0, nonceBuffer, t.length + pubKeyBytes.length, msg.length);

        // Generate nonce k0
        BigInteger k0 = Util.bigIntFromBytes(Point.taggedHash(TAG_NONCE, nonceBuffer)).mod(Point.getn());
        if (k0.compareTo(BigInteger.ZERO) == 0) {
            throw new Exception("Failure. This happens only with negligible probability.");
        }

        // Compute R = k0*G
        Point R = Point.mul(Point.getG(), k0);

        // Ensure R has even Y coordinate
        BigInteger k;
        if (!R.hasEvenY()) {
            k = Point.getn().subtract(k0);
        } else {
            k = k0;
        }

        // Get R's X coordinate as bytes
        byte[] rBytes = R.toBytes();

        // Create buffer for challenge generation (R || P || m)
        byte[] challengeBuffer = new byte[rBytes.length + pubKeyBytes.length + msg.length];
        System.arraycopy(rBytes, 0, challengeBuffer, 0, rBytes.length);
        System.arraycopy(pubKeyBytes, 0, challengeBuffer, rBytes.length, pubKeyBytes.length);
        System.arraycopy(msg, 0, challengeBuffer, rBytes.length + pubKeyBytes.length, msg.length);

        // Generate challenge e
        BigInteger e = Util.bigIntFromBytes(Point.taggedHash(TAG_CHALLENGE, challengeBuffer)).mod(Point.getn());

        // Compute s = k + e*secKey0 mod n
        BigInteger s = k.add(e.multiply(secKey0)).mod(Point.getn());

        // Convert s to bytes
        byte[] sBytes = Util.bytesFromBigInteger(s);

        // Create signature (R || s)
        byte[] sig = new byte[64]; // Always 64 bytes for a Schnorr signature
        System.arraycopy(rBytes, 0, sig, 0, 32);
        System.arraycopy(sBytes, 0, sig, 32, sBytes.length);

        // Verify signature before returning
        if (!verify(msg, pubKeyBytes, sig)) {
            throw new Exception("The signature does not pass verification.");
        }

        return sig;
    }

    /**
     * @param msg
     * @param pubkey
     * @param sig
     * @return
     * @throws Exception
     */
    public static boolean verify(byte[] msg, byte[] pubkey, byte[] sig) throws Exception {
        if (msg.length != 32) throw new Exception("The message must be a 32-byte array.");
        if (pubkey.length != 32) throw new Exception("The public key must be a 32-byte array.");
        if (sig.length != 64) throw new Exception("The signature must be a 64-byte array.");

        Point P = Point.liftX(pubkey);
        if (P == null) return false;

        BigInteger r = Util.bigIntFromBytes(sig, 0, 32);
        BigInteger s = Util.bigIntFromBytes(sig, 32, 32);
        if (r.compareTo(Point.getp()) >= 0 || s.compareTo(Point.getn()) >= 0) return false;

        byte[] challengeBuffer = new byte[32 + pubkey.length + msg.length];
        System.arraycopy(sig, 0, challengeBuffer, 0, 32);
        System.arraycopy(pubkey, 0, challengeBuffer, 32, pubkey.length);
        System.arraycopy(msg, 0, challengeBuffer, 32 + pubkey.length, msg.length);

        BigInteger e = Util.bigIntFromBytes(Point.taggedHash(TAG_CHALLENGE, challengeBuffer)).mod(Point.getn());

        Point R = Point.schnorrVerify(s, P, e);
        return R != null && R.hasEvenY() && R.getX().compareTo(r) == 0;
    }

    public static byte[] genPubKey(byte[] secKey) throws Exception {
        BigInteger x = Util.bigIntFromBytes(secKey);
        if (!(BigInteger.ONE.compareTo(x) <= 0 && x.compareTo(Point.getn().subtract(BigInteger.ONE)) <= 0)) {
            return null;
        }
        Point ret = Point.mul(Point.G, x);
        return Point.bytesFromPoint(ret);
    }

    public static byte[] generatePrivateKey() throws Exception {
        Security.addProvider(new BouncyCastleProvider());
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("ECDSA", "BC");
        kpg.initialize(new ECGenParameterSpec("secp256k1"), SecureRandom.getInstanceStrong());
        KeyPair processorKeyPair = kpg.genKeyPair();
        return Util.bytesFromBigInteger(((ECPrivateKey) processorKeyPair.getPrivate()).getS());
    }
}
