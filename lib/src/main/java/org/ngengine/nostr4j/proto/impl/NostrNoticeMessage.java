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
import org.ngengine.nostr4j.utils.NostrUtils;

public class NostrNoticeMessage extends NostrMessage {

    private final String message;
    private final Throwable exception;

    public NostrNoticeMessage(String message, Throwable exception) {
        this.message = message;
        this.exception = exception;
    }

    public NostrNoticeMessage(String message) {
        this(message, null);
    }

    public String getMessage() {
        return this.message;
    }

    public Throwable getException() {
        return this.exception;
    }

    public void throwException() throws Throwable {
        if (this.exception != null) {
            throw this.exception;
        }
    }

    @Override
    protected String getPrefix() {
        return "NOTICE";
    }

    @Override
    protected Collection<Object> getFragments() {
        return List.of(this.message);
    }

    public static NostrClosedMessage parse(List<Object> data) {
        String prefix = NostrUtils.safeString(data.get(0));
        if (data.size() < 3 || !prefix.equals("NOTICE")) {
            return null;
        }
        String subId = NostrUtils.safeString(data.get(1));
        String reason = NostrUtils.safeString(data.get(2));
        return new NostrClosedMessage(subId, reason);
    }
}
