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
package org.ngengine.nostr4j.proto;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import org.ngengine.nostr4j.NostrRelay;
import org.ngengine.platform.NGEPlatform;
import org.ngengine.platform.NGEUtils;

public abstract class NostrMessage extends NostrMessageFragment {

    protected abstract String getPrefix();

    protected abstract Collection<Object> getFragments();

    protected Map<String, Object> toMap() {
        throw new UnsupportedOperationException("toMap() not implemented");
    }

    private transient volatile String jsonCache = null;

    protected List<Object> toSerial() {
        Collection<Object> fragments = getFragments();
        List<Object> serial = new ArrayList<>(fragments.size() + 1);
        serial.add(getPrefix());
        for (Object fragment : fragments) {
            if (fragment instanceof NostrMessageFragment) {
                serial.add(((NostrMessageFragment) fragment).toMap());
            } else {
                serial.add(fragment);
            }
        }
        return serial;
    }

    protected String toJSON() {
        if (jsonCache == null) {
            NGEPlatform platform = NGEUtils.getPlatform();
            Collection<Object> serial = toSerial();
            jsonCache = platform.toJSON(serial);
        }
        return jsonCache;
    }

    @Override
    public final String toString() {
        try {
            return toJSON();
        } catch (Exception e) {
            return (this.getClass().getName() + "@" + Integer.toHexString(this.hashCode()));
        }
    }

    public static String toJSON(NostrMessage message) {
        return message.toJSON();
    }

    public static List<Object> toSerial(NostrMessage message) {
        return message.toSerial();
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        NostrMessage that = (NostrMessage) obj;
        return toSerial().equals(that.toSerial());
    }

    @Override
    public int hashCode() {
        return toSerial().hashCode();
    }

    public static NostrMessageAck ack(
        NostrRelay relay,
        String id,
        Instant sentAt,
        BiConsumer<NostrMessageAck, String> successCallback,
        BiConsumer<NostrMessageAck, String> failureCallback
    ) {
        return new NostrMessageAck(relay, id, sentAt, successCallback, failureCallback);
    }
}
