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

package org.ngengine.wallets.nip47;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.ngengine.nostr4j.keypair.NostrKeyPair;
import org.ngengine.nostr4j.keypair.NostrPrivateKey;
import org.ngengine.nostr4j.keypair.NostrPublicKey;
import org.ngengine.nostr4j.signer.NostrKeyPairSigner;

public class NWCUri {

    private static final String SCHEME = "nostr+walletconnect";

    private final NostrPublicKey pubkey;
    private final List<String> relays;
    private final String secret;
    private final String lud16;
    private transient URI uri;
    private transient NostrKeyPairSigner signer;

    public NWCUri(
        @Nonnull NostrPublicKey pubkey,
        @Nonnull List<String> relays,
        @Nonnull String secret,
        @Nullable String lud16
    ) {
        this.pubkey = pubkey;
        this.relays = relays;
        this.secret = secret;
        this.lud16 = lud16;
    }

    public NWCUri(String uriString) throws URISyntaxException {
        if (uriString == null || uriString.isEmpty()) {
            throw new IllegalArgumentException("URI string cannot be null or empty");
        }

        // Replace schema with http:// for compatibility with URI class
        if (uriString.startsWith(SCHEME + "://")) {
            uriString = "http://" + uriString.substring((SCHEME + "://").length());
        } else {
            throw new IllegalArgumentException("Invalid NWC URI scheme, expected: " + SCHEME);
        }

        URI uri = new URI(uriString);
        this.pubkey = NostrPublicKey.fromHex(uri.getHost());
        this.relays = new ArrayList<>();
        String secret = null;
        String lud16 = null;
        String query = uri.getQuery();

        if (query != null) {
            String[] pairs = query.split("&");
            for (String pair : pairs) {
                int idx = pair.indexOf("=");
                if (idx > 0) {
                    String key = URLDecoder.decode(pair.substring(0, idx), StandardCharsets.UTF_8);
                    String value = URLDecoder.decode(pair.substring(idx + 1), StandardCharsets.UTF_8);
                    switch (key) {
                        case "relay":
                            relays.add(value);
                            break;
                        case "secret":
                            secret = value;
                            break;
                        case "lud16":
                            lud16 = value;
                            break;
                    }
                }
            }
        }

        this.secret = Objects.requireNonNull(secret, "Secret cannot be null in NWC URI");
        this.lud16 = lud16;
        if (this.relays.size() == 0) {
            throw new IllegalArgumentException("At least one relay must be specified in NWC URI");
        }
    }

    public NostrKeyPairSigner getSigner() {
        if (signer == null) {
            signer = new NostrKeyPairSigner(new NostrKeyPair(NostrPrivateKey.fromHex(getSecret())));
        }
        return signer;
    }

    public NostrPublicKey getPubkey() {
        return pubkey;
    }

    public List<String> getRelays() {
        return relays;
    }

    @Nullable
    public String getLud16() {
        return lud16;
    }

    public String getSecret() {
        return secret;
    }

    public URI toURI() {
        if (uri != null) {
            return uri;
        }

        StringBuilder sb = new StringBuilder();
        sb.append(SCHEME).append("://").append(pubkey).append("?");

        boolean first = true;
        for (String relay : relays) {
            if (!first) {
                sb.append("&");
            }
            sb.append("relay=").append(URLEncoder.encode(relay, StandardCharsets.UTF_8));
            first = false;
        }

        sb.append("&secret=").append(URLEncoder.encode(secret, StandardCharsets.UTF_8));

        if (lud16 != null && !lud16.isEmpty()) {
            sb.append("&lud16=").append(URLEncoder.encode(lud16, StandardCharsets.UTF_8));
        }

        try {
            uri = new URI(sb.toString());
        } catch (URISyntaxException e) {
            throw new RuntimeException("Failed to create URI from NWCUri", e);
        }
        return uri;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof NWCUri)) return false;
        NWCUri other = (NWCUri) obj;
        return (
            pubkey.equals(other.pubkey) &&
            relays.equals(other.relays) &&
            secret.equals(other.secret) &&
            Objects.equals(lud16, other.lud16)
        );
    }

    @Override
    public int hashCode() {
        return Objects.hash(pubkey, relays, secret, lud16);
    }

    @Override
    public String toString() {
        return toURI().toString();
    }
}
