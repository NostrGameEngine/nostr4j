/**
 * BSD 3-Clause License
 *
 * Copyright (c) 2026, Riccardo Balbo
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
package org.ngengine.nostr4j.store;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.ngengine.nostr4j.NostrRelay;
import org.ngengine.nostr4j.NostrRelayInfo;
import org.ngengine.nostr4j.NostrSubscription;
import org.ngengine.nostr4j.event.SignedNostrEvent;
import org.ngengine.nostr4j.event.SignedNostrEvent.ReceivedSignedNostrEvent;
import org.ngengine.nostr4j.listeners.NostrRelayComponent;
import org.ngengine.nostr4j.nip50.NostrSearchFilter;
import org.ngengine.nostr4j.proto.NostrMessage;
import org.ngengine.nostr4j.proto.NostrMessageAck;
import org.ngengine.nostr4j.proto.impl.NostrClosedMessage;
import org.ngengine.nostr4j.proto.impl.NostrEOSEMessage;
import org.ngengine.platform.AsyncTask;
import org.ngengine.platform.NGEUtils;

/**
 * Serves cached events as a local relay.
 */
public final class NostrCacheRelay extends NostrRelay implements AutoCloseable {
    private final NostrEventCache cache;
    private final Map<String, NostrSubscription> active = new ConcurrentHashMap<>();
    private final AutoCloseable observation;
    private AsyncTask<Void> pruning;

    public NostrCacheRelay(NostrEventCache cache) {
        super("cache://local", NGEUtils.getPlatform().newAsyncExecutor(NostrCacheRelay.class), true);
        this.cache = Objects.requireNonNull(cache);
        setQueryPriority(10);
        observation = cache.observe(event -> executor.run(() -> {
            List<NostrSubscription> subscriptions = new ArrayList<>(active.values());
            for (NostrSubscription sub : subscriptions) {
                if (sub.isOpened() && sub.getFilters().stream().anyMatch(filter ->
                        !(filter instanceof NostrSearchFilter) && filter.matches(event)))
                    receive(ReceivedSignedNostrEvent.forSubscription(sub.getId(), event));
            }
            return null;
        }));
    }

    public NostrEventCache getCache() {
        return cache;
    }

    @Override
    public NostrRelayInfo getInfo() {
        return new NostrRelayInfo(getUrl(), Map.of("name", "Local event cache", "description", "In-process verified event storage"));
    }

    @Override
    public AsyncTask<NostrRelay> connect() {
        if (isConnected()) return AsyncTask.completed(this);
        updateStatus(Status.CONNECTED);
        if (pruning == null) pruneLater();
        for (NostrRelayComponent component : listeners) component.onRelayConnect(this);
        return AsyncTask.completed(this);
    }

    @Override
    public AsyncTask<NostrRelay> disconnect(String reason, boolean reconnect) {
        if (pruning != null) { pruning.cancel(); pruning = null; }
        active.clear();
        updateStatus(Status.DISCONNECTED);
        for (NostrRelayComponent component : listeners) component.onRelayDisconnect(this, reason, true);
        return AsyncTask.completed(this);
    }

    @Override
    public void resetConnection() {
        connect();
    }

    private void pruneLater() {
        pruning = executor.runLater(() -> {
            if (isConnected()) {
                cache.clean(64).then(ignored -> { if (isConnected()) pruneLater(); return null; })
                    .catchException(error -> { if (isConnected()) pruneLater(); });
            }
            return null;
        }, 1, TimeUnit.SECONDS);
    }

    @Override
    public AsyncTask<NostrMessageAck> sendMessage(NostrMessage message) {
        NostrMessageAck ack = NostrMessage.ack(this, message instanceof NostrSubscription ? ((NostrSubscription) message).getId() : "local",
            Instant.now(), null, null);
        for (NostrRelayComponent component : listeners) component.onRelaySend(this, message);
        if (message instanceof NostrSubscription) {
            NostrSubscription sub = (NostrSubscription) message;
            active.put(sub.getId(), sub);
            cache.query(sub.getFilters()).then(events -> {
                if (!sub.isOpened() || !isConnected() || active.get(sub.getId()) != sub) return null;
                for (SignedNostrEvent event : events) {
                    if (!sub.isOpened() || !isConnected() || active.get(sub.getId()) != sub) return null;
                    receive(ReceivedSignedNostrEvent.forSubscription(sub.getId(), event));
                }
                if (sub.isOpened()) receive(new NostrEOSEMessage(sub.getId()));
                return null;
            }).catchException(error -> {
                if (active.remove(sub.getId(), sub) && sub.isOpened())
                    receive(new NostrClosedMessage(sub.getId(), "error: local cache query failed"));
            });
        } else if (message instanceof NostrSubscription.NostrSubCloseMessage) {
            active.remove(((NostrSubscription.NostrSubCloseMessage) message).getId());
        } else if (message instanceof SignedNostrEvent) {
            SignedNostrEvent event = (SignedNostrEvent) message;
            return AsyncTask.create((resolve, reject) -> cache.put(event).then(ignored -> {
                ack.callSuccessCallback("local");
                resolve.accept(ack);
                return null;
            }).catchException(error -> {
                ack.callFailureCallback(error.getMessage());
                resolve.accept(ack);
            }));
        }
        ack.callSuccessCallback("local");
        return AsyncTask.completed(ack);
    }

    private void receive(NostrMessage message) {
        for (NostrRelayComponent component : listeners) if (!component.onRelayMessage(this, message)) break;
    }

    @Override
    public void close() {
        disconnect("Cache relay closed", false);
        try { observation.close(); } catch (Exception ignored) { }
        executor.close();
    }
}
