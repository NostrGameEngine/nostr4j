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
package org.ngengine.nostr4j.nip46;

import java.io.Serializable;
import java.io.UnsupportedEncodingException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.ngengine.nostr4j.keypair.NostrPublicKey;

public class BunkerUrl implements Serializable, Cloneable {

    public final NostrPublicKey pubkey;
    public final String secret;
    public final List<String> relays;

    public BunkerUrl(NostrPublicKey pubkey, String secret, List<String> relays) {
        this.pubkey = pubkey;
        this.secret = secret;
        this.relays = relays;
    }

    public BunkerUrl(NostrPublicKey pubkey, List<String> relays) {
        this.pubkey = pubkey;
        this.relays = relays;
        this.secret = null;
    }

    @Override
    public BunkerUrl clone() {
        try {
            return (BunkerUrl) super.clone();
        } catch (CloneNotSupportedException e) {
            return new BunkerUrl(pubkey, secret, relays);
        }
    }

    @Override
    public String toString() {
        //bunker://<remote-signer-pubkey>?relay=<wss://relay-to-connect-on>&relay=<wss://another-relay-to-connect-on>&secret=<optional-secret-value>
        StringBuilder sb = new StringBuilder();
        sb.append("bunker://");
        sb.append(pubkey.asHex());

        for (int i = 0; i < relays.size(); i++) {
            if (i == 0) {
                sb.append("?relay=");
            } else {
                sb.append("&relay=");
            }
            sb.append(urlEncode(relays.get(i)));
        }

        if (secret != null) {
            sb.append("&secret=");
            sb.append(urlEncode(secret));
        }

        return sb.toString();
    }

    public static BunkerUrl parse(String bunkerUrl)
        throws MalformedURLException, URISyntaxException, UnsupportedEncodingException {
        bunkerUrl = bunkerUrl.replace("bunker://", "http://");
        List<String> relayUrls = new ArrayList<>();
        URL url = new URI(bunkerUrl).toURL();

        String pubkey = url.getHost();
        String secret = "";
        String query = url.getQuery();

        if (query != null && !query.isEmpty()) {
            String[] pairs = query.split("&");
            for (String pair : pairs) {
                int idx = pair.indexOf('=');
                if (idx > 0) {
                    String key = pair.substring(0, idx);
                    String value = urlDecode(pair.substring(idx + 1));
                    if (key.equals("secret")) {
                        secret = value;
                    } else if (key.equals("relay")) {
                        relayUrls.add(value);
                    }
                }
            }
        }

        return new BunkerUrl(NostrPublicKey.fromHex(pubkey), secret, Collections.unmodifiableList(relayUrls));
    }

    private static String urlEncode(String value) {
        try {
            return URLEncoder.encode(value, StandardCharsets.UTF_8.name());
        } catch (UnsupportedEncodingException e) {
            throw new RuntimeException(e);
        }
    }

    private static String urlDecode(String value) {
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8.name());
        } catch (UnsupportedEncodingException e) {
            throw new RuntimeException(e);
        }
    }
}
