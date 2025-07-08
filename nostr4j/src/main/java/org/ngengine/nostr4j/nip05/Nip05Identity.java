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
package org.ngengine.nostr4j.nip05;

import java.io.Serializable;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import org.ngengine.nostr4j.keypair.NostrPublicKey;
import org.ngengine.platform.NGEUtils;

public class Nip05Identity implements Serializable {

    public final Map<String, Object> data;
    protected final String name;
    protected final String domain;
    protected final NostrPublicKey publicKey;
    protected Collection<String> recommendedRelays;
    protected Nip05Nip46Data nip46;

    public Nip05Identity(String name, String domain, Map<String, Object> nip05data) {
        this.data = nip05data;
        this.name = name;
        this.domain = domain;
        this.publicKey = extractPubkey(getName());
        if (this.publicKey == null) throw new IllegalArgumentException("Malformed nip05 data");
    }

    public Nip05Nip46Data getNip46Data() {
        if (nip46 != null) return nip46;
        Nip05Nip46Data nip46 = new Nip05Nip46Data(extractPubkey("_"));
        nip46.setUrl("https://" + getDomain());
        nip46.setName(getName());

        Object nip46o = data.get("nip46");

        if (nip46o == null || !(nip46o instanceof Map)) {
            this.nip46 = nip46;
            return nip46;
        }

        Map<String, Object> nip46data = (Map<String, Object>) nip46o;
        Object relayso = nip46data.get("relays");
        if (relayso != null) {
            String[] relays = NGEUtils.safeStringArray(relayso);
            if (relays.length > 0) {
                nip46.setRelays(Arrays.asList(relays));
            }
        }

        Object nostrconnect = nip46data.get("nostrconnect");
        if (nostrconnect != null) {
            String nostrconnectRedirect = NGEUtils.safeString(nostrconnect);
            if (!nostrconnectRedirect.isEmpty()) {
                nip46.setNostrconnectRedirectTemplate(nostrconnectRedirect);
            }
        }

        this.nip46 = nip46;
        return nip46;
    }

    public String getName() {
        return name;
    }

    public String getDomain() {
        return domain;
    }

    public String getIdentifier() {
        return name + "@" + domain;
    }

    public NostrPublicKey getPublicKey() {
        return publicKey;
    }

    protected Collection<String> getRecommendedRelays() {
        if (recommendedRelays != null) return recommendedRelays;
        Object o = data.get("relays");
        if (o == null || !(o instanceof Map)) return Collections.emptyList();
        Map<String, Object> relays = (Map<String, Object>) o;
        Object userRelays = relays.get(publicKey.asHex());
        if (userRelays == null || !(userRelays instanceof Collection)) return Collections.emptyList();
        recommendedRelays = Arrays.asList(NGEUtils.safeStringArray(userRelays));
        return recommendedRelays;
    }

    private NostrPublicKey extractPubkey(String name) {
        Object o = data.get("names");
        if (o == null || !(o instanceof Map)) {
            throw new IllegalArgumentException("Malformed nip05 data");
        }
        Map<String, Object> names = (Map<String, Object>) o;
        Object key = names.get(name);
        if (key == null || !(key instanceof String)) return null;
        String pubkey = (String) key;
        NostrPublicKey pk = NostrPublicKey.fromHex(pubkey);
        return pk;
    }
}
