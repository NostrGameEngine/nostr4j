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
package org.ngengine.nostr4j.nip39;

import java.util.List;

public abstract class ExternalIdentity {

    private final String platform;
    private final String identity;
    private final List<String> proof;

    protected ExternalIdentity(String platform, String identity, List<String> proof) {
        if (!Nip39.isValidPlatform(platform)) {
            throw new IllegalArgumentException("Invalid platform: " + platform);
        }
        this.platform = platform;
        this.identity = identity;
        this.proof = proof != null ? proof : List.of();
    }

    public String getPlatform() {
        return platform;
    }

    public String getIdentity() {
        return identity;
    }

    public List<String> getProof() {
        return proof;
    }

    @Override
    public String toString() {
        return (
            "ExternalIdentity{" + "platform='" + platform + '\'' + ", identity='" + identity + '\'' + ", proof=" + proof + '}'
        );
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;

        ExternalIdentity that = (ExternalIdentity) obj;

        if (!platform.equals(that.platform)) return false;
        if (!identity.equals(that.identity)) return false;
        return proof.equals(that.proof);
    }

    @Override
    public int hashCode() {
        int result = platform.hashCode();
        result = 31 * result + identity.hashCode();
        result = 31 * result + proof.hashCode();
        return result;
    }
}
