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

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.ngengine.nostr4j.listeners.NostrRelayComponent;
import org.ngengine.nostr4j.platform.AsyncTask;
import org.ngengine.nostr4j.transport.NostrMessage;
import org.ngengine.nostr4j.utils.NostrUtils;

public class NostrRelaySubManager implements NostrRelayComponent {

    private static class SubAttachment {

        boolean eose;
    }

    private final Map<String, SubAttachment> subTracker = new HashMap<>();

    @Override
    public boolean onRelayConnectRequest(NostrRelay relay) {
        return true;
    }

    @Override
    public boolean onRelayConnect(NostrRelay relay) {
        return true;
    }

    @Override
    public boolean onRelayMessage(NostrRelay relay, List<Object> data) {
        String prefix = NostrUtils.safeString(data.get(0));
        switch (prefix) {
            case "CLOSED":
                {
                    String subId = NostrUtils.safeString(data.get(1));

                    this.subTracker.remove(subId);
                    break;
                }
            case "EOSE":
                {
                    String subId = NostrUtils.safeString(data.get(1));
                    SubAttachment attachment = this.subTracker.get(subId);
                    if (attachment != null) {
                        attachment.eose = true;
                    }
                    break;
                }
        }
        return true;
    }

    public boolean isActive(NostrSubscription sub) {
        return this.subTracker.containsKey(sub.getSubId());
    }

    public boolean isEose(NostrSubscription sub) {
        SubAttachment attachment = this.subTracker.get(sub.getSubId());
        return attachment != null && attachment.eose;
    }

    @Override
    public boolean onRelayError(NostrRelay relay, Throwable error) {
        return true;
    }

    @Override
    public boolean onRelayLoop(NostrRelay relay, Instant nowInstant) {
        return true;
    }

    @Override
    public boolean onRelayDisconnect(
        NostrRelay relay,
        String reason,
        boolean byClient
    ) {
        return true;
    }

    @Override
    public boolean onRelaySend(NostrRelay relay, NostrMessage message) {
        if (message instanceof NostrSubscription) {
            String subId = ((NostrSubscription) message).getSubId();
            subTracker.computeIfAbsent(subId, k -> new SubAttachment());
        } else if (message instanceof NostrSubscription.NostrSubCloseMessage) {
            String subId =
                ((NostrSubscription.NostrSubCloseMessage) message).getId();
            subTracker.remove(subId);
        }
        return true;
    }

    @Override
    public boolean onRelayDisconnectRequest(NostrRelay relay, String reason) {
        return true;
    }
}
