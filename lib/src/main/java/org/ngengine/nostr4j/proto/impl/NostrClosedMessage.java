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
import org.ngengine.platform.NGEUtils;

public class NostrClosedMessage extends NostrMessage {

    private final String subId;
    private final String reason;

    public NostrClosedMessage(String subId, String reason) {
        this.subId = subId;
        this.reason = reason;
    }

    public String getSubId() {
        return this.subId;
    }

    public String getReason() {
        return this.reason;
    }

    @Override
    protected String getPrefix() {
        return "CLOSED";
    }

    @Override
    protected Collection<Object> getFragments() {
        return List.of(this.subId, this.reason);
    }

    public static NostrClosedMessage parse(List<Object> doc) {
        String prefix = NGEUtils.safeString(doc.get(0));
        if (doc.size() < 2 || !prefix.equals("CLOSED")) {
            return null;
        }
        String subId = NGEUtils.safeString(doc.get(1));
        String reason = doc.size() > 2 ? NGEUtils.safeString(doc.get(2)) : "";
        return new NostrClosedMessage(subId, reason);
    }
}
