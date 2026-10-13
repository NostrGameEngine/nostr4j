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

package org.ngengine.nostr4j.proto.impl;

import java.util.Collection;
import java.util.List;
import org.ngengine.nostr4j.proto.NostrMessage;

/**
 * Represents the relay-to-client AUTH challenge envelope defined by NIP-42.
 * The challenge is an opaque non-empty string bounded to 4096 characters. This message
 * does not contain a signed authentication event and is not an ordinary EVENT publication.
 */
public final class NostrAuthChallenge extends NostrMessage {

    private final String challenge;

    /**
     * Creates a relay challenge after validating its local size bound.
     *
     * @param challenge opaque non-empty challenge of at most 4096 characters
     * @throws IllegalArgumentException if the challenge is null, empty or too long
     */
    public NostrAuthChallenge(String challenge) {
        if (challenge == null || challenge.isEmpty() || challenge.length() > 4096) throw new IllegalArgumentException(
            "Invalid AUTH challenge"
        );
        this.challenge = challenge;
    }

    public String getChallenge() {
        return challenge;
    }

    protected String getPrefix() {
        return "AUTH";
    }

    protected Collection<Object> getFragments() {
        return List.of(challenge);
    }

    /**
     * Parses a relay AUTH challenge with exactly one string argument.
     * Unrelated envelopes or argument types are ignored; a recognized but invalid
     * challenge is rejected by the constructor.
     *
     * @param data decoded wire-message elements
     * @return parsed challenge, or null if the envelope is not a relay AUTH challenge
     * @throws IllegalArgumentException if a recognized challenge violates its size bound
     */
    public static NostrAuthChallenge parse(List<Object> data) {
        if (data.size() != 2 || !"AUTH".equals(data.get(0)) || !(data.get(1) instanceof String)) return null;
        return new NostrAuthChallenge((String) data.get(1));
    }
}
