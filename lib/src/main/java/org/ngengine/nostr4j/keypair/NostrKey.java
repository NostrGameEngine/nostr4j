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

import java.io.Serializable;
import java.util.Collection;

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
    String asHex();

    /**
     * Returns the bech32 string representation of the key
     *
     * This representation is lazily generated and cached after the first call.
     * Use {@link #preload()} if you want to generate the representation in advance.
     *
     * @return the bech32 string
     */
    String asBech32();

    String toString();

    /**
     * Returns a readonly bytes representation of the key
     * @return
     */
    Collection<Byte> asReadOnlyBytes();

    /**
     * Returns the internal bytes representation of the key.
     * WARNING: do not modify the returned array to avoid unexpected behaviors,
     * use asReadOnlyBytes whenever possible to avoid this issue.
     * @return the internal bytes representation of the key that should be treated as immutable
     */
    byte[] _array();

    /**
     * Preloads the key representations in various formats.
     * This method is useful when you want to generate the representations in advance
     * for performance reasons.
     */
    void preload();
}
