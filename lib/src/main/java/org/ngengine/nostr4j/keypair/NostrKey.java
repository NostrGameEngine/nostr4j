package org.ngengine.nostr4j.keypair;

import java.io.Serializable;
import java.util.Collection;

import org.ngengine.nostr4j.utils.Bech32.Bech32Exception;

/**
 * Represents a private or public key in the Nostr protocol.
 * This interface provides methods to obtain different representations of the
 * key.
 */
public interface NostrKey extends Serializable, Cloneable {

    /**
     * Returns the hex string representation of the key.
     * 
     * This representation is lazily generated and cached after the first call.
     * Use {@link #preload()} if you want to generate the representation in advance.
     * 
     * @return the hex string representation of the key
     */
    public String asHex();

    /**
     * Returns the bech32 string representation of the key
     *
     * This representation is lazily generated and cached after the first call.
     * Use {@link #preload()} if you want to generate the representation in advance.
     * 
     * @return the bech32 string
     */
    public String asBech32() throws Bech32Exception ;    

    public String toString();
    

    /**
     * Returns a readonly bytes representation of the key
     * @return
     */
    public Collection<Byte> asReadOnlyBytes();


    /**
     * Returns the internal bytes representation of the key.
     * WARNING: do not modify the returned array to avoid unexpected behaviors,
     * use asReadOnlyBytes whenever possible to avoid this issue.
     * @return the internal bytes representation of the key that should be treated as immutable
     */
    public byte[] _array();


    /**
     * Preloads the key representations in various formats.
     * This method is useful when you want to generate the representations in advance
     * for performance reasons.
     */
    public void preload() throws Bech32Exception;

}
