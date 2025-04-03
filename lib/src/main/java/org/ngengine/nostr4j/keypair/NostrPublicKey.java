package org.ngengine.nostr4j.keypair;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Collections;

import org.ngengine.nostr4j.utils.Bech32;
import org.ngengine.nostr4j.utils.ByteBufferList;
import org.ngengine.nostr4j.utils.NostrUtils;
import org.ngengine.nostr4j.utils.Bech32.Bech32Exception;

/**
 * Represents a Nostr public key.
 * <p>
 * This class provides methods to create a Nostr public key from various formats
 * such as byte arrays, hex strings, and Bech32 strings. It also provides
 * methods
 * to convert the public key to different formats.
 * </p>
 */
public class NostrPublicKey implements NostrKey {
    private static final byte[] BECH32_PREFIX = "npub".getBytes(StandardCharsets.UTF_8);

    private String bech32;
    private String hex; 

    private transient Collection<Byte> readOnlyData;
    private transient ByteBuffer data;
    private transient byte[] array;

    /**
     * Creates a new NostrPublicKey from the given byte array.
     * 
     * @param data the byte array containing the public key data
     * @return a new NostrPublicKey instance
     */
    public static NostrPublicKey fromBytes(byte[] data) {
        ByteBuffer bbf = ByteBuffer.allocate(data.length);
        bbf.put(data);
        bbf.rewind();
        return new NostrPublicKey(bbf);
    }

    /**
     * Creates a new NostrPublicKey from the given ByteBuffer.
     * <p>
     * This method copies the content of the provided ByteBuffer, use the constructor
     * if you want to directly use the provided ByteBuffer as an internal reference.
     * </p>
     * 
     * @param bbf the ByteBuffer containing the public key data
     * @return a new NostrPublicKey instance
     */
    public static NostrPublicKey fromBytes(ByteBuffer bbf){
        assert bbf.remaining() > 0 : "ByteBuffer should not be empty";
        ByteBuffer copy = ByteBuffer.allocate(bbf.remaining());
        copy.put(bbf.slice());
        copy.rewind();
        assert bbf.position() == 0 : "ByteBuffer should be at position 0";
        return new NostrPublicKey(copy);
    }

    /**
     * Creates a new NostrPublicKey from the given hex string.
     * 
     * @param hex the hex string containing the public key data
     * @return a new NostrPublicKey instance
     */
    public static NostrPublicKey fromHex(String hex) {
        NostrPublicKey key = new NostrPublicKey(NostrUtils.hexToBytes(hex));
        return key;
    }

 
    /**
     * Creates a new NostrPublicKey from the given Bech32 string.
     * 
     * @param bech32 the Bech32 string containing the public key data
     * @return a new NostrPublicKey instance
     * @throws Bech32Exception if the Bech32 string is invalid
     * @deprecated use {@link #fromBech32(String)} instead
     */
    @Deprecated
    public static NostrPublicKey fromNpub(String bech32) throws Bech32Exception {
        return fromBech32(bech32);
    }

    
    /**
     * Creates a new NostrPublicKey from the given Bech32 string.
     * 
     * @param bech32 the Bech32 string containing the public key data
     * @return a new NostrPublicKey instance
     * @throws Bech32Exception if the Bech32 string is invalid
     */
    public static NostrPublicKey fromBech32(String bech32) throws Bech32Exception {
        if(!bech32.startsWith("npub")){
            throw new IllegalArgumentException("Invalid npub key");
        }
        ByteBuffer data = Bech32.bech32Decode(bech32);
        NostrPublicKey key = new NostrPublicKey(data);
        return key;
    }


    /**
     * Creates a new NostrPublicKey from the given data.
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
    public NostrPublicKey(ByteBuffer data) {
        assert data.position() == 0 : "data should be at position 0";
        this.data = data;
    }

    public Collection<Byte> asReadOnlyBytes() {
        if (readOnlyData != null)
            return readOnlyData;
        readOnlyData = Collections.unmodifiableList(new ByteBufferList(data));
        assert data.position() == 0 : "Data position must be 0";
        return readOnlyData;
    }

    @Override
    public String asHex() {
        if(hex != null) return hex;
        hex = NostrUtils.bytesToHex(data);
        assert data.position() == 0 : "Data position must be 0";
        return hex;
    }

    @Override
    public byte[] _array() {
        if(this.array==null){
            this.array=new byte[data.limit()];
            data.slice().get(this.array);
        }
        assert data.position() == 0 : "Data position must be 0";
        return this.array;
    }

    @Override
    public String asBech32() throws Bech32Exception {
        if(bech32 != null)return bech32;
        bech32 = Bech32.bech32Encode(BECH32_PREFIX, this.data);
        assert data.position() == 0 : "Data position must be 0";
        return bech32;
    }

    @Override
    public String toString() {
        return asHex();
    }

    @Override
    public boolean equals(Object obj) {
        if(obj == null || !(obj instanceof NostrPublicKey)){
            assert data.position() == 0 : "Data position must be 0";
            return false;
        }
        if(obj == this) {
            assert data.position() == 0 : "Data position must be 0";
            return true;
        }

        ByteBuffer b1 = this.data;
        ByteBuffer b2 = ((NostrPublicKey)obj).data;
        if(b1 == b2){
            assert data.position() == 0 : "Data position must be 0";
            return true;
        }
        if(b1 == null || b2 == null){
            assert data.position() == 0 : "Data position must be 0";
            return false;
        }
        if(b1.limit() != b2.limit()){
            assert data.position() == 0 : "Data position must be 0";
            return false;
        }
        for(int i = 0; i < b1.limit(); i++){
            if(b1.get(i) != b2.get(i)){
                assert data.position() == 0 : "Data position must be 0";
                return false;
            }
        }
        assert data.position() == 0 : "Data position must be 0";
        return true;
    }

    @Override
    public NostrPublicKey clone() {
        return fromBytes(data);        
    }


    @Override
    public void preload() throws Bech32Exception {
        asHex();
        asBech32();
        asReadOnlyBytes();
        _array();
        assert data.position() == 0 : "Data position must be 0";
    }

    private void writeObject(java.io.ObjectOutputStream out) throws IOException{
        out.writeObject(this._array());
        out.writeObject(hex);
        out.writeObject(bech32);
        assert data.position() == 0 : "Data position must be 0";
    }

    private void readObject(java.io.ObjectInputStream in) throws IOException, ClassNotFoundException {
        byte array[] = (byte[])in.readObject();
        this.array = array;
        data = ByteBuffer.wrap(array);
        hex = (String)in.readObject();
        bech32 = (String)in.readObject();
        assert data.position() == 0 : "Data position must be 0";
    }
        
}
