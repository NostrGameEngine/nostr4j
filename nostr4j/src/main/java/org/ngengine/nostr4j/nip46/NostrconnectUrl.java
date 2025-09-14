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
import java.util.List;
import org.ngengine.nostr4j.keypair.NostrPublicKey;

public class NostrconnectUrl implements Serializable, Cloneable {

    public final List<String> relays;
    public final String secret;
    public final Nip46AppMetadata metadata;
    public final NostrPublicKey clientPubKey;

    public NostrconnectUrl(NostrPublicKey clientPubKey, List<String> relays, String secret, Nip46AppMetadata appMetadata) {
        this.relays = relays;
        this.secret = secret;

        this.clientPubKey = clientPubKey;
        this.metadata = appMetadata;
    }

    @Override
    public String toString() {
        /*
         * client provides a connection token using nostrconnect:// as the protocol, and
         * client-pubkey as the origin. Additional information should be passed as query
         * parameters:
         *
         * relay (required) - one or more relay urls on which the client is listening
         * for responses from the remote-signer.
         * secret (required) - a short random string that the remote-signer should
         * return as the result field of its response.
         * perms (optional) - a comma-separated list of permissions the client is
         * requesting be approved by the remote-signer
         * name (optional) - the name of the client application
         * url (optional) - the canonical url of the client application
         * image (optional) - a small image representing the client application
         *
         */
        StringBuilder sb = new StringBuilder();
        sb.append("nostrconnect://");
        sb.append(clientPubKey.asHex());
        sb.append("?");
        for (String relay : relays) {
            sb.append("relay=");
            sb.append(urlEncode(relay));
            sb.append("&");
        }
        sb.append("secret=");
        sb.append(urlEncode(secret));
        if (this.metadata.getPerms() != null) {
            sb.append("&perms=");
            sb.append(urlEncode(String.join(",", this.metadata.getPerms())));
        }
        if (this.metadata.getName() != null) {
            sb.append("&name=");
            sb.append(urlEncode(this.metadata.getName()));
        }
        if (this.metadata.getUrl() != null) {
            sb.append("&url=");
            sb.append(urlEncode(this.metadata.getUrl()));
        }
        if (this.metadata.getImage() != null) {
            sb.append("&image=");
            sb.append(urlEncode(this.metadata.getImage()));
        }
        return sb.toString();
    }

    @Override
    public NostrconnectUrl clone() {
        try {
            return (NostrconnectUrl) super.clone();
        } catch (CloneNotSupportedException e) {
            return new NostrconnectUrl(clientPubKey, relays, secret, metadata);
        }
    }

    public static NostrconnectUrl parse(String url)
        throws MalformedURLException, URISyntaxException, UnsupportedEncodingException {
        url = url.replace("nostrconnect://", "http://");

        URL u = new URI(url).toURL();

        List<String> relays = new ArrayList<>();
        List<String> appPerms = null;
        String secret = null;
        NostrPublicKey clientPubKey = null;
        String appName = null;
        String appUrl = null;
        String appImage = null;

        clientPubKey = NostrPublicKey.fromHex(u.getHost());

        String query = u.getQuery();
        if (query != null && !query.isEmpty()) {
            String[] pairs = query.split("&");
            for (String pair : pairs) {
                int idx = pair.indexOf('=');
                if (idx > 0) {
                    String key = pair.substring(0, idx);
                    String value = URLDecoder.decode(pair.substring(idx + 1), "UTF-8");
                    if (key.equals("secret")) {
                        secret = urlDecode(value);
                    } else if (key.equals("relay")) {
                        relays.add(urlDecode(value));
                    } else if (key.equals("name")) {
                        appName = urlDecode(value);
                    } else if (key.equals("url")) {
                        appUrl = urlDecode(value);
                    } else if (key.equals("image")) {
                        appImage = urlDecode(value);
                    } else if (key.equals("perms")) {
                        if (appPerms == null) appPerms = new ArrayList<>();
                        appPerms.addAll(List.of(urlDecode(value).split(",")));
                    }
                }
            }
        }
        if (relays.isEmpty()) {
            throw new MalformedURLException("No relay urls found");
        }
        if (secret == null) {
            throw new MalformedURLException("No secret found");
        }
        if (clientPubKey == null) {
            throw new MalformedURLException("No client pubkey found");
        }

        return new NostrconnectUrl(clientPubKey, relays, secret, new Nip46AppMetadata(appName, appUrl, appImage, appPerms));
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
