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
package org.ngengine.nostr4j;

import java.io.Serializable;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.ngengine.nostr4j.keypair.NostrPublicKey;
import org.ngengine.platform.AsyncTask;
import org.ngengine.platform.NGEPlatform;
import org.ngengine.platform.NGEUtils;

/**
 * Nip-11
 */
public class NostrRelayInfo implements Cloneable, Serializable {

    private static final Logger logger = Logger.getLogger(NostrRelayInfo.class.getName());

    private final Map<String, Object> map;
    private final String relayUrl;

    private transient String name;
    private transient String description;
    private transient String banner;
    private transient String icon;
    private transient NostrPublicKey pubkey;
    private transient String contact;
    private transient List<Integer> supportedNips;
    private transient String software;
    private transient String version;
    private transient String privacyPolicy;
    private transient String termsOfService;
    private transient Map<String, Object> mapRO;
    private transient String toStringCache;
    private transient Map<String, Object> limitation;
    private transient List<String> relayCountries;
    private transient List<String> languageTags;
    private transient List<String> tags;
    private transient String postingPolicy;

    public NostrRelayInfo(String url, Map<String, Object> map) {
        this.map = map;
        this.relayUrl = url;
    }

    public static AsyncTask<NostrRelayInfo> get(String relayUrl) {
        Map<String, String> headers = new HashMap<>();
        headers.put("Accept", "application/nostr+json");
        NGEPlatform platform = NGEUtils.getPlatform();
        String httpUrl = relayUrl.startsWith("wss://")
            ? relayUrl.replace("wss://", "https://")
            : relayUrl.replace("ws://", "http://");

        AsyncTask<String> task = platform.httpGet(httpUrl, Duration.ofSeconds(30), headers);
        return task.then(data -> {
            Map<String, Object> map = platform.fromJSON(data, Map.class);
            return new NostrRelayInfo(relayUrl, map);
        });
    }

    @Override
    public NostrRelayInfo clone() {
        try {
            NostrRelayInfo clone = (NostrRelayInfo) super.clone();
            clone.mapRO = null;
            return clone;
        } catch (CloneNotSupportedException e) {
            logger.log(Level.SEVERE, "Failed to clone NostrRelayInfo", e);
            return null;
        }
    }

    public Map<String, Object> get() {
        if (mapRO == null) {
            mapRO = Collections.unmodifiableMap(map);
        }
        return mapRO;
    }

    public String getName() {
        if (name == null) {
            name = NGEUtils.safeString(map.get("name"));
        }
        return name;
    }

    public String getDescription() {
        if (description == null) {
            description = NGEUtils.safeString(map.get("description"));
        }
        return description;
    }

    public String getBanner() {
        if (banner == null) {
            banner = NGEUtils.safeString(map.get("banner"));
        }
        return banner;
    }

    public String getIcon() {
        if (icon == null) {
            icon = NGEUtils.safeString(map.get("icon"));
        }
        return icon;
    }

    public NostrPublicKey getPubkey() {
        if (pubkey == null) {
            String k = NGEUtils.safeString(map.get("pubkey"));
            if (k.startsWith("npub")) {
                pubkey = NostrPublicKey.fromBech32(k);
            } else {
                pubkey = NostrPublicKey.fromHex(k);
            }
        }
        return pubkey;
    }

    public String getContact() {
        if (contact == null) {
            contact = NGEUtils.safeString(map.get("contact"));
        }
        return contact;
    }

    public List<Integer> getSupportedNips() {
        if (supportedNips == null) {
            supportedNips = Collections.unmodifiableList(NGEUtils.safeIntList(map.getOrDefault("supported_nips", List.of())));
        }
        return supportedNips;
    }

    public String getSoftware() {
        if (software == null) {
            software = NGEUtils.safeString(map.get("software"));
        }
        return software;
    }

    public String getVersion() {
        if (version == null) {
            version = NGEUtils.safeString(map.get("version"));
        }
        return version;
    }

    public String getPrivacyPolicy() {
        if (privacyPolicy == null) {
            privacyPolicy = NGEUtils.safeString(map.get("privacy_policy"));
        }
        return privacyPolicy;
    }

    public String getTermsOfService() {
        if (termsOfService == null) {
            termsOfService = NGEUtils.safeString(map.get("terms_of_service"));
        }
        return termsOfService;
    }

    public String getRelayUrl() {
        return relayUrl;
    }

    public boolean isNipSupported(int nip) {
        return getSupportedNips().contains(nip);
    }

    public boolean isNipSupported(String nip) {
        try {
            nip = nip.toLowerCase().trim();
            if (nip.startsWith("nip-")) {
                nip = nip.substring(4);
            }
            if (nip.startsWith("nip")) {
                nip = nip.substring(3);
            }
            return isNipSupported(NGEUtils.safeInt(nip));
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private Map<String, Object> getLimitations() {
        if (limitation == null) {
            limitation = Collections.unmodifiableMap((Map<String, Object>) map.getOrDefault("limitation", Map.of()));
            if (limitation == null) {
                limitation = Map.of();
            }
        }
        return limitation;
    }

    public int getLimitation(String key, int defaultValue) {
        if (getLimitations().containsKey(key)) {
            return NGEUtils.safeInt(getLimitations().get(key));
        }
        return defaultValue;
    }

    public boolean getLimitation(String key, boolean defaultValue) {
        if (getLimitations().containsKey(key)) {
            return NGEUtils.safeBool(getLimitations().get(key));
        }
        return defaultValue;
    }

    public List<String> getCountries() {
        if (relayCountries == null) {
            relayCountries = Collections.unmodifiableList(NGEUtils.safeStringList(map.getOrDefault("countries", List.of())));
        }
        return relayCountries;
    }

    public List<String> getLanguageTags() {
        if (languageTags == null) {
            List<String> tags = NGEUtils.safeStringList(map.getOrDefault("language_tags", List.of()));
            this.languageTags = Collections.unmodifiableList(tags);
        } else {
            this.languageTags = new ArrayList<>();
        }
        return this.languageTags;
    }

    public List<String> getTags() {
        if (tags == null) {
            tags = Collections.unmodifiableList(NGEUtils.safeStringList(map.getOrDefault("tags", List.of())));
        }
        return tags;
    }

    public String getPostingPolicy() {
        if (postingPolicy == null) {
            postingPolicy = NGEUtils.safeString(map.get("posting_policy"));
        }
        return postingPolicy;
    }

    // TODO: retention and pay

    @Override
    public String toString() {
        if (toStringCache == null) {
            toStringCache = NGEUtils.getPlatform().toJSON(get());
        }
        return toStringCache;
    }
}
