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

package org.ngengine.nostr4j.nip42;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import org.ngengine.nostr4j.NostrRelay;
import org.ngengine.nostr4j.NostrSubscription;
import org.ngengine.nostr4j.event.SignedNostrEvent;
import org.ngengine.nostr4j.event.UnsignedNostrEvent;
import org.ngengine.nostr4j.listeners.NostrRelayComponent;
import org.ngengine.nostr4j.proto.NostrMessage;
import org.ngengine.nostr4j.proto.NostrMessageAck;
import org.ngengine.nostr4j.proto.impl.NostrAuthChallenge;
import org.ngengine.nostr4j.proto.impl.NostrClosedMessage;
import org.ngengine.nostr4j.signer.NostrSigner;

/**
 * Authenticates to one explicitly trusted relay using a caller-owned signer.
 * Attach this component before adding the relay to a pool so authentication-required
 * closures can be intercepted before ordinary subscription handling. Challenges are
 * limited to three per connection, and tracked subscriptions are bounded at 128.
 * Authentication events use the AUTH envelope and are not published as ordinary events.
 */
public final class Nip42RelayAuth implements NostrRelayComponent {

    private final String allowedRelay;
    private final NostrSigner signer;
    private final Map<String, NostrSubscription> subscriptions = new LinkedHashMap<>();
    private final Map<String, NostrSubscription> retry = new LinkedHashMap<>();
    private String challenge;
    private int challenges;
    private boolean authenticated;

    private static final class AuthEvent extends SignedNostrEvent {

        AuthEvent(SignedNostrEvent event) {
            super(event.toMap());
        }

        @Override
        protected String getPrefix() {
            return "AUTH";
        }
    }

    /**
     * Creates authentication support for one relay URL.
     * The URL is compared exactly before handling relay challenges. The signer remains
     * owned by the caller and may ask the user to approve an authentication signature.
     *
     * @param allowedRelay exact URL of the relay trusted to request authentication
     * @param signer caller-owned identity used to sign kind-22242 authentication events
     */
    public Nip42RelayAuth(String allowedRelay, NostrSigner signer) {
        this.allowedRelay = allowedRelay;
        this.signer = signer;
    }

    public boolean onRelayMessage(NostrRelay relay, NostrMessage message) {
        if (!allowedRelay.equals(relay.getUrl())) return true;
        if (!(message instanceof NostrAuthChallenge) && !(message instanceof NostrClosedMessage)) return true;
        synchronized (this) {
            if (message instanceof NostrAuthChallenge) {
                String next = ((NostrAuthChallenge) message).getChallenge();
                if (next.equals(challenge) || ++challenges > 3) return false;
                challenge = next;
                authenticated = false;
                signer
                    .sign(new UnsignedNostrEvent().withKind(22242).withTag("relay", relay.getUrl()).withTag("challenge", next))
                    .compose(signed -> relay.sendMessage(new AuthEvent(signed)))
                    .then(ack -> {
                        synchronized (this) {
                            if (!next.equals(challenge) || ack.getStatus() != NostrMessageAck.Status.SUCCESS) return null;
                            authenticated = true;
                            for (NostrSubscription subscription : new ArrayList<>(retry.values())) {
                                if (subscription.isOpened()) relay.sendMessage(subscription);
                            }
                            retry.clear();
                        }
                        return null;
                    })
                    .catchException(error -> {
                        /*
                         * Authentication is optional; normal request timeouts report failure.
                         */
                    });
                return false;
            }
            if (message instanceof NostrClosedMessage) {
                NostrClosedMessage closed = (NostrClosedMessage) message;
                NostrSubscription subscription = subscriptions.get(closed.getSubId());
                if (subscription != null && closed.getReason().startsWith("auth-required:") && challenges <= 3) {
                    if (!retry.containsKey(closed.getSubId())) {
                        retry.put(closed.getSubId(), subscription);
                        if (authenticated && subscription.isOpened()) relay.sendMessage(subscription);
                        return false;
                    }
                }
            }
            return true;
        }
    }

    public boolean onRelaySend(NostrRelay relay, NostrMessage message) {
        if (!(message instanceof NostrSubscription)) return true;
        synchronized (this) {
            if (message instanceof NostrSubscription) {
                NostrSubscription subscription = (NostrSubscription) message;
                subscriptions.entrySet().removeIf(entry -> !entry.getValue().isOpened());
                if (subscriptions.size() < 128) subscriptions.put(subscription.getSubId(), subscription);
            }
            return true;
        }
    }

    public boolean onRelayConnectRequest(NostrRelay relay) {
        return true;
    }

    public synchronized boolean onRelayConnect(NostrRelay relay) {
        challenge = null;
        challenges = 0;
        authenticated = false;
        retry.clear();
        return true;
    }

    public boolean onRelayError(NostrRelay relay, Throwable error) {
        return true;
    }

    public synchronized boolean onRelayDisconnect(NostrRelay relay, String reason, boolean byClient) {
        challenge = null;
        authenticated = false;
        retry.clear();
        return true;
    }

    public boolean onRelayBeforeSend(NostrRelay relay, NostrMessage message) {
        return true;
    }

    public boolean onRelayAfterSend(NostrRelay relay, NostrMessage message) {
        return true;
    }

    public boolean onRelayDisconnectRequest(NostrRelay relay, String reason) {
        return true;
    }
}
