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
package org.ngengine.nostr4j.event;

import static org.ngengine.platform.NGEUtils.dbg;

import java.io.Serializable;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;
import org.ngengine.platform.NGEUtils;

public interface NostrEvent extends Cloneable, Serializable {
    class TagValue {

        private final List<String> values;

        public TagValue(List<String> values) {
            this.values = Collections.unmodifiableList(values);
        }

        public TagValue(String... values) {
            this.values = Arrays.asList(values);
        }

        public TagValue(String value) {
            this.values = Arrays.asList(value);
        }

        public int size() {
            return values.size();
        }

        /**
         * Get the value at the specified index.
         * If the index is out of bounds, null is returned.
         * @param index the index of the value to get
         * @return the value at the specified index, or null if the index is out of bounds
         */
        public String get(int index) {
            if (index < 0 || index >= values.size()) {
                return null;
            }
            return values.get(index);
        }

        public List<String> getAll() {
            return values;
        }
    }

    Instant getCreatedAt();
    int getKind();
    String getContent();

    Collection<TagValue> getTag(String key);

    TagValue getFirstTag(String key);

    Set<String> listTagKeys();

    List<List<String>> getTagRows();

    boolean hasTag(String tag);

    static String computeEventId(String pubkey, NostrEvent event) {
        try {
            Collection<Object> serial = Arrays.asList(
                0,
                pubkey,
                event.getCreatedAt().getEpochSecond(),
                event.getKind(),
                event.getTagRows(),
                event.getContent()
            );
            assert dbg(() -> {
                Logger logger = Logger.getLogger(NostrEvent.class.getName());
                logger.finest("Serializing event: " + serial);
            });
            String json = NGEUtils.getPlatform().toJSON(serial);
            assert dbg(() -> {
                Logger logger = Logger.getLogger(NostrEvent.class.getName());
                logger.finest("Serialized event: " + json);
            });
            String id = NGEUtils.getPlatform().sha256(json);
            return id;
        } catch (Exception e) {
            return null;
        }
    }

    // nip40 expiration
    default Instant getExpiration() {
        String tag = getFirstTag("expiration").get(0);
        Instant expiresAt = null;
        if (tag != null) {
            long expires = NGEUtils.safeLong(tag);
            expiresAt = Instant.ofEpochSecond(expires);
        } else {
            expiresAt = Instant.now().plusSeconds(60 * 60 * 24 * 365 * 2100);
        }
        return expiresAt;
    }
}
