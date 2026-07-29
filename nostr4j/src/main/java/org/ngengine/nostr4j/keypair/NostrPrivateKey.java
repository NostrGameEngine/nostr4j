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
import java.nio.ReadOnlyBufferException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Objects;
import org.ngengine.bech32.Bech32;
import org.ngengine.nostr4j.nip49.Nip49;
import org.ngengine.nostr4j.nip49.Nip49FailedException;
import org.ngengine.nostr4j.utils.ByteBufferList;
import org.ngengine.platform.AsyncTask;
import org.ngengine.platform.NGEUtils;

public final class NostrPrivateKey implements NostrKey, AutoCloseable {

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
    private volatile NostrPublicKey publicKey;
    private KeySecurity keySecurity = KeySecurity.UNKNOWN;

    private transient volatile Collection<Byte> readOnlyData;
    private transient ByteBuffer data;
    private transient volatile byte[] array;
    private transient volatile boolean destroyed;

    /**
     * Creates a new NostrPrivateKey from the given byte array.
     *
     * @param data the byte array containing the public key data
     * @return a new NostrPrivateKey instance
     */
    public static NostrPrivateKey fromBytes(byte[] data) {
        Objects.requireNonNull(data);
        ByteBuffer bbf = NGEUtils.getPlatform().getNativeAllocator().malloc(data.length);
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
        Objects.requireNonNull(bbf);
        assert bbf.remaining() > 0 : "ByteBuffer should not be empty";
        ByteBuffer copy = NGEUtils.getPlatform().getNativeAllocator().malloc(bbf.remaining());
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
        Objects.requireNonNull(hex);
        return fromBytes(NGEUtils.hexToBytes(hex));
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
            NostrPrivateKey key = fromBytes(data);
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
        ByteBuffer data = NGEUtils.getPlatform().generatePrivateKeyBuffer();
        NostrPrivateKey key = new NostrPrivateKey(data);
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

    public synchronized Collection<Byte> asReadOnlyBytes() {
        requireUsable();
        if (readOnlyData != null) return readOnlyData;
        readOnlyData = Collections.unmodifiableList(new ByteBufferList(data));
        assert data.position() == 0 : "Data position must be 0";
        return readOnlyData;
    }

    @Override
    public synchronized ByteBuffer asReadOnlyBuffer() {
        requireUsable();
        ByteBuffer view = data.asReadOnlyBuffer();
        view.position(0);
        return view;
    }

    @Override
    public synchronized String asHex() {
        requireUsable();
        if (hex != null) return hex;
        hex = NGEUtils.bytesToHex(data);
        assert data.position() == 0 : "Data position must be 0";
        return hex;
    }

    @Override
    public synchronized byte[] _array() {
        requireUsable();
        if (this.array == null) {
            byte array[] = new byte[data.limit()];
            data.slice().get(array);
            this.array = array;
        }
        assert data.position() == 0 : "Data position must be 0";
        return this.array;
    }

    @Override
    public synchronized String asBech32() {
        requireUsable();
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
        if (obj == this) return true;
        if (destroyed) return false;
        if (obj == null || !(obj instanceof NostrPrivateKey)) {
            assert data.position() == 0 : "Data position must be 0";
            return false;
        }

        ByteBuffer b1 = this.data;
        NostrPrivateKey other = (NostrPrivateKey) obj;
        if (other.destroyed) return false;
        ByteBuffer b2 = other.data;

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
        if (destroyed || data == null) return 0;
        int hashcode = data.hashCode();
        assert data.position() == 0 : "Data position must be 0";
        return hashcode;
    }

    @Override
    public synchronized NostrPrivateKey clone() {
        requireUsable();
        NostrPrivateKey copy = fromBytes(data);
        copy.keySecurity = keySecurity;
        if (publicKey != null) {
            copy.publicKey = publicKey.clone();
        }
        return copy;
    }

    @Override
    public synchronized void preload() {
        requireUsable();
        asHex();
        asBech32();
        asReadOnlyBytes();
        _array();
        assert data.position() == 0 : "Data position must be 0";
    }

    public synchronized NostrPublicKey getPublicKey() {
        requireUsable();
        if (publicKey == null) {
            ByteBuffer publicKeyData = NGEUtils.getPlatform().genPubKey(asReadOnlyBuffer());
            publicKey = new NostrPublicKey(publicKeyData);
        }
        assert data.position() == 0 : "Data position must be 0";
        return publicKey;
    }

    private synchronized void writeObject(java.io.ObjectOutputStream out) throws IOException {
        if (destroyed) {
            throw new IOException("Cannot serialize a destroyed private key");
        }
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
        destroyed = false;
        assert data.position() == 0 : "Data position must be 0";
    }

    /**
     * Best-effort destruction of the in-memory private key material.
     * <p>
     * Any mutable backing storage and cached byte array are overwritten. Cached
     * textual encodings are released, but the JVM cannot guarantee immediate
     * removal of immutable {@link String} copies or copies made by native
     * cryptographic code.
     * </p>
     */
    public synchronized void destroy() {
        if (destroyed) return;
        destroyed = true;
        if (array != null) {
            Arrays.fill(array, (byte) 0);
            array = null;
        }
        if (data != null && !data.isReadOnly()) {
            try {
                for (int i = 0; i < data.limit(); i++) {
                    data.put(i, (byte) 0);
                }
            } catch (ReadOnlyBufferException ignored) {
                // Best effort: callers can provide externally owned read-only storage.
            }
        }
        readOnlyData = null;
        hex = null;
        bech32 = null;
        publicKey = null;
    }

    public boolean isDestroyed() {
        return destroyed;
    }

    @Override
    public void close() {
        destroy();
    }

    private void requireUsable() {
        if (destroyed) {
            throw new IllegalStateException("Private key has been destroyed");
        }
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
