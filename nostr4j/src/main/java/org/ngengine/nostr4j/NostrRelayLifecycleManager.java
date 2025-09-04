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

import static org.ngengine.platform.NGEUtils.dbg;

import java.time.Instant;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import org.ngengine.nostr4j.event.NostrEvent;
import org.ngengine.nostr4j.listeners.NostrRelayComponent;
import org.ngengine.nostr4j.proto.NostrMessage;
import org.ngengine.nostr4j.proto.impl.NostrClosedMessage;

public class NostrRelayLifecycleManager implements NostrRelayComponent {

    private static final Logger logger = Logger.getLogger(NostrRelayLifecycleManager.class.getName());

    protected final CopyOnWriteArrayList<String> subTracker = new CopyOnWriteArrayList<>();
    protected volatile long keepAliveTime = TimeUnit.MINUTES.toSeconds(2);
    protected volatile long lastAction;

    public void setKeepAliveTime(long time, TimeUnit unit) {
        this.keepAliveTime = unit.toSeconds(time);
    }

    public long getKeepAliveTime(TimeUnit outputUnit) {
        return outputUnit.convert(this.keepAliveTime, TimeUnit.SECONDS);
    }

    public boolean hasActiveSubscription(NostrSubscription sub) {
        return this.subTracker.contains(sub.getSubId());
    }

    @Override
    public boolean onRelayConnect(NostrRelay relay) {
        this.keepAlive();
        return true;
    }

    @Override
    public boolean onRelayMessage(NostrRelay relay, NostrMessage rcv) {
        if (rcv instanceof NostrClosedMessage) {
            NostrClosedMessage msg = (NostrClosedMessage) rcv;
            String subId = msg.getSubId();
            logger.fine("Removing closed subscription from lifecycle tracker: " + subId);
            this.subTracker.remove(subId);
        } else if (rcv instanceof NostrEvent) {
            this.keepAlive();
        }
        return true;
    }

    public void keepAlive() {
        this.lastAction = Instant.now().getEpochSecond();
    }

    @Override
    public boolean onRelayError(NostrRelay relay, Throwable error) {
        return true;
    }

    @Override
    public boolean onRelayLoop(NostrRelay relay, Instant nowInstant) {
        // disconnect if no active subscriptions and last action is too old
        long now = nowInstant.getEpochSecond();
        if (this.subTracker.isEmpty() && now - this.lastAction > keepAliveTime) {
            logger.fine("Disconnecting from relay: " + relay.getUrl() + " for inactivity");
            relay.disconnect("timeout");
        }
        return true;
    }

    @Override
    public boolean onRelayDisconnect(NostrRelay relay, String reason, boolean byClient) {
        logger.fine(
            "Clearing tracked subscription in lifecycle manager for relay: " +
            relay.getUrl() +
            " since it was closed for reason: " +
            reason +
            (byClient ? " (by client)" : "")
        );
        this.subTracker.clear();
        return true;
    }

    @Override
    public boolean onRelaySend(NostrRelay relay, NostrMessage message) {
        if (message instanceof NostrSubscription) {
            NostrSubscription sub = (NostrSubscription) message;

            // track sub if not already tracked, otherwise ignore
            // this servers two purposes:
            // 1. track how many subscriptions are active to decide when to disconnect the
            // relay
            // 2. make the code upstream simpler as it won't have to worry about which
            // subscriptions
            // are active on which relay or when the relay was added to the pool etc
            // you just subscribe to everything and the relay will automatically ignore
            // duplicates
            if (subTracker.addIfAbsent(sub.getSubId())) {
                // assert dbg(() -> {
                // logger.finer("Tracking new subscription: " + sub.getSubId());
                logger.fine("Adding subscription to lifecycle tracker: " + sub.getSubId());
                // });
            } else {
                assert dbg(() -> {
                    logger.finer("Subscription already tracked: " + sub.getSubId());
                });
                return false;
            }
        } else if (message instanceof NostrSubscription.NostrSubCloseMessage) {
            subTracker.remove(((NostrSubscription.NostrSubCloseMessage) message).getId());
            // assert dbg(() -> {
            logger.fine(
                "Removing subscription from lifecycle tracker due to it being closed by the client: " +
                ((NostrSubscription.NostrSubCloseMessage) message).getId()
            );
            // });
        }
        return true;
    }

    @Override
    public boolean onRelayConnectRequest(NostrRelay relay) {
        this.keepAlive();
        return true;
    }

    @Override
    public boolean onRelayDisconnectRequest(NostrRelay relay, String reason) {
        this.keepAlive();
        return true;
    }

    @Override
    public boolean onRelayBeforeSend(NostrRelay relay, NostrMessage message) {
        relay.connect();
        this.keepAlive();
        return true;
    }

    @Override
    public boolean onRelayAfterSend(NostrRelay relay, NostrMessage message) {
        return true;
    }
}
