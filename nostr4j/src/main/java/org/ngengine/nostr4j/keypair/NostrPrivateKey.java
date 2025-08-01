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
package org.ngengine.nostr4j.keypair;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Collections;
import org.ngengine.bech32.Bech32;
import org.ngengine.nostr4j.nip49.Nip49;
import org.ngengine.nostr4j.nip49.Nip49FailedException;
import org.ngengine.nostr4j.utils.ByteBufferList;
import org.ngengine.platform.AsyncTask;
import org.ngengine.platform.NGEUtils;

public final class NostrPrivateKey implements NostrKey {

    // nip-49
    public enum KeySecurity {
        /**
         * If the key has been known to have been handled insecurely (stored
         * unencrypted, cut and paste unencrypted, etc) (0x00)
         */
        UNTRUSTED,
        /**
         * if the key has NOT been known to have been handled insecurely (stored
         * unencrypted, cut and paste unencrypted, etc) (0x01)
         */
        NORMAL,
        /**
         * if the client does not track this data (0x02)
         * (default)
         */
        UNKNOWN,
    }

    private static final long serialVersionUID = 1L;

    private static final byte[] BECH32_PREFIX = "nsec".getBytes(StandardCharsets.UTF_8);

    private String bech32;
    private String hex;
    private NostrPublicKey publicKey;
    private KeySecurity keySecurity = KeySecurity.UNKNOWN;

    private transient Collection<Byte> readOnlyData;
    private transient ByteBuffer data;
    private transient byte[] array;

    /**
     * Creates a new NostrPrivateKey from the given byte array.
     *
     * @param data the byte array containing the public key data
     * @return a new NostrPrivateKey instance
     */
    public static NostrPrivateKey fromBytes(byte[] data) {
        ByteBuffer bbf = ByteBuffer.allocate(data.length);
        bbf.put(data);
        bbf.rewind();
        return new NostrPrivateKey(bbf);
    }

    /**
     * Creates a new NostrPrivateKey from the given ByteBuffer.
     * <p>
     * This method copies the content of the provided ByteBuffer, use the
     * constructor
     * if you want to directly use the provided ByteBuffer as an internal reference.
     * </p>
     *
     * @param bbf the ByteBuffer containing the public key data
     * @return a new NostrPrivateKey instance
     */
    public static NostrPrivateKey fromBytes(ByteBuffer bbf) {
        assert bbf.remaining() > 0 : "ByteBuffer should not be empty";
        ByteBuffer copy = ByteBuffer.allocate(bbf.remaining());
        copy.put(bbf.slice());
        copy.rewind();
        assert bbf.position() == 0 : "Data position must be 0";
        return new NostrPrivateKey(copy);
    }

    /**
     * Creates a new NostrPrivateKey from the given hex string.
     *
     * @param hex the hex string containing the public key data
     * @return a new NostrPrivateKey instance
     */
    public static NostrPrivateKey fromHex(String hex) {
        NostrPrivateKey key = new NostrPrivateKey(NGEUtils.hexToBytes(hex));
        return key;
    }

    /**
     * Creates a new NostrPrivateKey from the given Bech32 string.
     *
     * @param bech32 the Bech32 string containing the public key data
     * @return a new NostrPrivateKey instance
     * @deprecated use {@link #fromBech32(String)} instead
     */
    @Deprecated
    public static NostrPrivateKey fromNsec(String bech32) {
        return fromBech32(bech32);
    }

    /**
     * Creates a new NostrPrivateKey from the given Bech32 string.
     *
     * @param bech32 the Bech32 string containing the public key data
     * @return a new NostrPrivateKey instance
     */
    public static NostrPrivateKey fromBech32(String bech32) {
        try {
            if (!bech32.startsWith("nsec")) {
                throw new IllegalArgumentException("Invalid npub key");
            }
            ByteBuffer data = Bech32.bech32Decode(bech32);
            NostrPrivateKey key = new NostrPrivateKey(data);
            assert data.position() == 0;
            return key;
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid nsec key", e);
        }
    }

    /**
     * Creates a new NostrPrivateKey from the given ncryptsec string encrypted with
     * nip-49 and a passphrase.
     *
     * @param ncryptsec  the ncryptsec string containing the public key data
     * @param passphrase the password used to encrypt the ncryptsec string
     * @return a new unencrypted NostrPrivateKey instance
     * @throws Nip49FailedException
     */
    public static AsyncTask<NostrPrivateKey> fromNcryptsec(String ncryptsec, String passphrase) throws Nip49FailedException {
        return Nip49.decrypt(ncryptsec, passphrase);
    }

    public static NostrPrivateKey generate() {
        byte[] data = NGEUtils.getPlatform().generatePrivateKey();
        NostrPrivateKey key = new NostrPrivateKey(ByteBuffer.wrap(data));
        return key;
    }

    /**
     * Creates a new NostrPrivateKey from the given data.
     * <p>
     * Note: This constructor directly stores the provided {@link ByteBuffer} as an
     * internal reference.
     * Modifying the {@link ByteBuffer} externally after passing it to this
     * constructor may lead to unexpected behavior.
     * </p>
     * <p>
     * If you don't want to worry about this, use one of the static factory methods
     * such as {@link #fromBytes(byte[])} or {@link #fromHex(String)} that copy the
     * data.
     * </p>
     *
     * @param data the {@link ByteBuffer} containing the public key data
     */
    protected NostrPrivateKey(ByteBuffer data) {
        assert data.position() == 0 : "Data position must be 0";
        this.data = data;
    }

    public Collection<Byte> asReadOnlyBytes() {
        if (readOnlyData != null) return readOnlyData;
        readOnlyData = Collections.unmodifiableList(new ByteBufferList(data));
        assert data.position() == 0 : "Data position must be 0";
        return readOnlyData;
    }

    @Override
    public String asHex() {
        if (hex != null) return hex;
        hex = NGEUtils.bytesToHex(data);
        assert data.position() == 0 : "Data position must be 0";
        return hex;
    }

    @Override
    public byte[] _array() {
        if (this.array == null) {
            this.array = new byte[data.limit()];
            data.slice().get(this.array);
        }
        assert data.position() == 0 : "Data position must be 0";
        return this.array;
    }

    @Override
    public String asBech32() {
        try {
            if (bech32 != null) return bech32;
            bech32 = Bech32.bech32Encode(BECH32_PREFIX, this.data);
            assert data.position() == 0 : "Data position must be 0";
            return bech32;
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid nsec key", e);
        }
    }

    @Override
    public String toString() {
        return asHex();
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == null || !(obj instanceof NostrPrivateKey)) {
            assert data.position() == 0 : "Data position must be 0";
            return false;
        }
        if (obj == this) {
            assert data.position() == 0 : "Data position must be 0";
            return true;
        }

        ByteBuffer b1 = this.data;
        ByteBuffer b2 = ((NostrPrivateKey) obj).data;

        if (b1 == null || b2 == null) {
            return false;
        }
        if (b1 == b2) {
            assert data.position() == 0 : "Data position must be 0";
            return true;
        }
        if (b1.limit() != b2.limit()) {
            assert data.position() == 0 : "Data position must be 0";
            return false;
        }
        for (int i = 0; i < b1.limit(); i++) {
            if (b1.get(i) != b2.get(i)) {
                assert data.position() == 0 : "Data position must be 0";
                return false;
            }
        }
        assert data.position() == 0 : "Data position must be 0";
        return true;
    }

    @Override
    public int hashCode() {
        if (data == null) return 0;
        int hashcode = data.hashCode();
        assert data.position() == 0 : "Data position must be 0";
        return hashcode;
    }

    @Override
    public NostrPrivateKey clone() {
        try {
            return (NostrPrivateKey) super.clone();
        } catch (Exception e) {
            return fromBytes(data);
        }
    }

    @Override
    public void preload() {
        asHex();
        asBech32();
        asReadOnlyBytes();
        _array();
        assert data.position() == 0 : "Data position must be 0";
    }

    public NostrPublicKey getPublicKey() {
        if (publicKey == null) {
            byte bdata[] = this._array();
            bdata = NGEUtils.getPlatform().genPubKey(bdata);
            publicKey = new NostrPublicKey(ByteBuffer.wrap(bdata));
        }
        assert data.position() == 0 : "Data position must be 0";
        return publicKey;
    }

    private void writeObject(java.io.ObjectOutputStream out) throws IOException {
        out.writeObject(this._array());
        out.writeObject(hex);
        out.writeObject(bech32);
        out.writeObject(publicKey);
        assert data.position() == 0 : "Data position must be 0";
    }

    private void readObject(java.io.ObjectInputStream in) throws IOException, ClassNotFoundException {
        byte array[] = (byte[]) in.readObject();
        this.array = array;
        data = ByteBuffer.wrap(array);
        hex = (String) in.readObject();
        bech32 = (String) in.readObject();
        publicKey = (NostrPublicKey) in.readObject();
        assert data.position() == 0 : "Data position must be 0";
    }

    /**
     * Set how much the key security is trusted. (defined in nip-49)
     * @param keySecurity the key security
     */
    public void setKeySecurity(KeySecurity keySecurity) {
        this.keySecurity = keySecurity;
    }

    /**
     * Get how much the key security is trusted. (defined in nip-49)
     * @return the key security (default is UNKNOWN)
     */
    public KeySecurity getKeySecurity() {
        return keySecurity;
    }

    /**
     * Encrypt the private key using nip-49 and a passphrase.
     * @param passphrase the password used to encrypt the private key
     * @return the encrypted private key as a bech32 string
     * @throws Nip49FailedException
     */
    public AsyncTask<String> asNcryptsec(String passphrase) throws Nip49FailedException {
        return Nip49.encrypt(this, passphrase);
    }
}
