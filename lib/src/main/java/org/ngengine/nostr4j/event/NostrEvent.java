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

import static org.ngengine.nostr4j.utils.NostrUtils.dbg;

import java.time.Instant;
import java.util.Arrays;
import java.util.Collection;
import java.util.logging.Logger;
import org.ngengine.nostr4j.utils.NostrUtils;

public interface NostrEvent extends Cloneable {
    Logger logger = Logger.getLogger(NostrEvent.class.getName());
    byte[] BECH32_PREVIX = "note".getBytes();
    Instant getCreatedAt();
    int getKind();
    String getContent();
    Collection<String[]> listTags();
    String[] getTag(String key);

    static String computeEventId(String pubkey, NostrEvent event) {
        try {
            Collection<Object> serial = Arrays.asList(
                0,
                pubkey,
                event.getCreatedAt().getEpochSecond(),
                event.getKind(),
                event.listTags(),
                event.getContent()
            );
            assert dbg(() -> logger.finest("Serializing event: " + serial));
            String json = NostrUtils.getPlatform().toJSON(serial);
            assert dbg(() -> logger.finest("Serialized event: " + json));
            String id = NostrUtils.getPlatform().sha256(json);
            return id;
        } catch (Exception e) {
            return null;
        }
    }
}
