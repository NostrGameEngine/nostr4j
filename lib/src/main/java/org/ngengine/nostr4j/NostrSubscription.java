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

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.logging.Logger;
import org.ngengine.nostr4j.event.SignedNostrEvent;
import org.ngengine.nostr4j.event.tracker.EventTracker;
import org.ngengine.nostr4j.listeners.sub.NostrSubCloseListener;
import org.ngengine.nostr4j.listeners.sub.NostrSubEoseListener;
import org.ngengine.nostr4j.listeners.sub.NostrSubEventListener;
import org.ngengine.nostr4j.listeners.sub.NostrSubListener;
import org.ngengine.nostr4j.platform.AsyncTask;
import org.ngengine.nostr4j.platform.NostrExecutor;
import org.ngengine.nostr4j.platform.Platform;
import org.ngengine.nostr4j.transport.NostrMessage;
import org.ngengine.nostr4j.transport.NostrMessageAck;
import org.ngengine.nostr4j.utils.NostrUtils;

public class NostrSubscription extends NostrMessage {

    private static final Logger logger = Logger.getLogger(NostrSubscription.class.getName());
    protected final EventTracker eventTracker;
    private final String subId;

    private final Collection<NostrSubEoseListener> onEoseListeners = new CopyOnWriteArrayList<>();
    private final Collection<NostrSubEventListener> onEventListeners = new CopyOnWriteArrayList<>();
    private final Collection<NostrSubCloseListener> onCloseListeners = new CopyOnWriteArrayList<>();

    private final NostrExecutor executor;
    private final Collection<NostrFilter> filters;
    private final Collection<NostrFilter> filtersRO;

    private final Function<NostrSubscription, AsyncTask<List<NostrMessageAck>>> onOpen;
    private final BiFunction<NostrSubscription, NostrSubCloseMessage, AsyncTask<List<NostrMessageAck>>> onClose;
    private final List<String> closeReasons = new ArrayList<>();

    protected NostrSubscription(
        String subId,
        Collection<NostrFilter> filters,
        EventTracker eventTracker,
        Function<NostrSubscription, AsyncTask<List<NostrMessageAck>>> onOpen,
        BiFunction<NostrSubscription, NostrSubCloseMessage, AsyncTask<List<NostrMessageAck>>> onClose
    ) {
        Platform platform = NostrUtils.getPlatform();
        this.subId = subId;
        this.eventTracker = eventTracker;
        this.executor = platform.newSubscriptionExecutor();
        this.filters = filters;
        this.filtersRO = Collections.unmodifiableCollection(filters);
        this.onOpen = onOpen;
        this.onClose = onClose;
    }

    protected void registerClosure(String reason) {
        closeReasons.add(reason);
    }

    public Collection<NostrFilter> getFilters() {
        return this.filtersRO;
    }

    public NostrExecutor getExecutor() {
        return this.executor;
    }

    public String getId() {
        return this.subId;
    }

    public AsyncTask<List<NostrMessageAck>> open() {
        return this.onOpen.apply(this);
    }

    public AsyncTask<List<NostrMessageAck>> close() {
        AsyncTask<List<NostrMessageAck>> out = this.onClose.apply(this, getCloseMessage());
        registerClosure("closed by client");
        callCloseListeners();
        return out;
    }

    public String getSubId() {
        return subId;
    }

    public NostrSubscription listenEvent(NostrSubEventListener listener) {
        assert listener != null;
        assert onEventListeners.contains(listener) == false;
        onEventListeners.add((NostrSubEventListener) listener);
        return this;
    }

    public NostrSubscription listenEose(NostrSubEoseListener listener) {
        assert listener != null;
        assert onEoseListeners.contains(listener) == false;
        onEoseListeners.add(listener);
        return this;
    }

    public NostrSubscription listenClose(NostrSubCloseListener listener) {
        assert listener != null;
        assert onCloseListeners.contains(listener) == false;
        onCloseListeners.add(listener);
        return this;
    }

    public NostrSubscription listen(NostrSubListener listener) {
        assert listener != null;
        if (listener instanceof NostrSubEoseListener) {
            assert onEoseListeners.contains(listener) == false;
            onEoseListeners.add((NostrSubEoseListener) listener);
        }
        if (listener instanceof NostrSubEventListener) {
            assert onEventListeners.contains(listener) == false;
            onEventListeners.add((NostrSubEventListener) listener);
        }
        if (listener instanceof NostrSubCloseListener) {
            assert onCloseListeners.contains(listener) == false;
            onCloseListeners.add((NostrSubCloseListener) listener);
        }

        return this;
    }

    public NostrSubscription stopListening(NostrSubListener listener) {
        if (listener instanceof NostrSubEoseListener) {
            onEoseListeners.remove((NostrSubEoseListener) listener);
        }
        if (listener instanceof NostrSubEventListener) {
            onEventListeners.remove((NostrSubEventListener) listener);
        }
        if (listener instanceof NostrSubCloseListener) {
            onCloseListeners.remove((NostrSubCloseListener) listener);
        }
        return this;
    }

    protected void callEoseListeners(boolean everyWhere) {
        if (onEoseListeners.isEmpty()) return;
        for (NostrSubEoseListener listener : onEoseListeners) {
            this.executor.run(() -> {
                    listener.onSubEose(everyWhere);
                    return null;
                })
                .catchException(ex -> {
                    logger.warning("Error calling EOSE listener: " + listener + " " + ex);
                });
        }
    }

    protected void callEventListeners(SignedNostrEvent event, boolean stored) {
        if (onEventListeners.isEmpty()) return;
        for (NostrSubEventListener listener : onEventListeners) {
            this.executor.run(() -> {
                    listener.onSubEvent(event, stored);
                    return null;
                })
                .catchException(ex -> {
                    logger.warning("Error calling Event listener: " + listener + " " + ex);
                });
        }
    }

    protected void callCloseListeners() {
        if (onCloseListeners.isEmpty()) return;
        for (NostrSubCloseListener listener : onCloseListeners) {
            this.executor.run(() -> {
                    listener.onSubClose(closeReasons);
                    return null;
                })
                .catchException(ex -> {
                    logger.warning("Error calling Close listener: " + listener + " " + ex);
                });
        }
    }

    @Override
    protected String getPrefix() {
        return "REQ";
    }

    @Override
    protected Collection<Object> getFragments() {
        List<Object> fragments = new ArrayList<>(filters.size() + 1);
        fragments.add(subId);
        for (NostrFilter filter : filters) {
            fragments.add(filter);
        }
        return fragments;
    }

    static final class NostrSubCloseMessage extends NostrMessage {

        private transient List<Object> fragments;
        private final String id;

        public NostrSubCloseMessage(String id) {
            this.id = id;
        }

        public String getId() {
            return id;
        }

        @Override
        public String getPrefix() {
            return "CLOSE";
        }

        @Override
        public Collection<Object> getFragments() {
            if (fragments != null) return fragments;
            fragments = new ArrayList<>(1);
            fragments.add(id);
            return fragments;
        }
    }

    private NostrSubCloseMessage getCloseMessage() {
        return new NostrSubCloseMessage(getId());
    }
}
