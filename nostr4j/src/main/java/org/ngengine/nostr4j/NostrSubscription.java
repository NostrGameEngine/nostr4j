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
import java.util.Objects;
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
import org.ngengine.nostr4j.proto.NostrMessage;
import org.ngengine.nostr4j.proto.NostrMessageAck;
import org.ngengine.platform.AsyncExecutor;
import org.ngengine.platform.AsyncTask;
import org.ngengine.platform.NGEPlatform;
import org.ngengine.platform.NGEUtils;

/**
 * Represents a subscription to a Nostr relay based on specific filter criteria.
 * <p>
 * A subscription allows clients to receive events matching specific filters
 * from the relay.
 * It manages listeners for different types of subscription events (event
 * received, end of stored events,
 * subscription closed) and tracks event delivery.
 * </p>
 * <p>
 * Example usage:
 * </p>
 * 
 * <pre>{@code
 * NostrFilter filter = new NostrFilter().kind(1).limit(10);
 * NostrSubscription subscription = relaySubManager.createSubscription(filter, new PassthroughEventTracker())
 *     .listenEvent((event, stored) -> System.out.println("Event: " + event.getContent()))
 *     .listenEose(everywhere -> System.out.println("End of stored events"));
 * subscription.open();
 * }</pre>
 */
public class NostrSubscription extends NostrMessage {

    private static final Logger logger = Logger.getLogger(NostrSubscription.class.getName());
    protected final EventTracker eventTracker;
    private final String subId;

    private final Collection<NostrSubEoseListener> onEoseListeners = new CopyOnWriteArrayList<>();
    private final Collection<NostrSubEventListener> onEventListeners = new CopyOnWriteArrayList<>();
    private final Collection<NostrSubCloseListener> onCloseListeners = new CopyOnWriteArrayList<>();

    private AsyncExecutor exc;
    private final Collection<NostrFilter> filters;
    private final Collection<NostrFilter> filtersRO;

    private final Function<NostrSubscription, AsyncTask<List<AsyncTask<NostrMessageAck>>>> onOpen;
    private final BiFunction<NostrSubscription, NostrSubCloseMessage, AsyncTask<List<AsyncTask<NostrMessageAck>>>> onClose;
    private final List<String> closeReasons = new ArrayList<>();

    private volatile boolean opened = false;

    /**
     * Creates a new subscription with the specified parameters.
     *
     * @param subId        The subscription identifier
     * @param filters      The filters that determine which events to receive
     * @param eventTracker The tracker used to manage event delivery and deduplication
     * @param onOpen       Function called when the subscription is opened
     * @param onClose      Function called when the subscription is closed
     */
    protected NostrSubscription(
        String subId,
        Collection<NostrFilter> filters,
        EventTracker eventTracker,
        Function<NostrSubscription, AsyncTask<List<AsyncTask<NostrMessageAck>>>> onOpen,
        BiFunction<NostrSubscription, NostrSubCloseMessage, AsyncTask<List<AsyncTask<NostrMessageAck>>>> onClose
    ) {
        this.subId = subId;
        this.eventTracker = eventTracker;
        this.filters = filters;
        this.filtersRO = Collections.unmodifiableCollection(filters);
        this.onOpen = onOpen;
        this.onClose = onClose;
    }

    /**
     * Registers a reason for subscription closure.
     * <p>
     * This method records reasons why a subscription was closed, which can be
     * useful for diagnostics and reporting to subscription listeners.
     * </p>
     *
     * @param reason The reason for closing the subscription
     */
    protected void registerClosure(String reason) {
        closeReasons.add(reason);
    }

    /**
     * Gets the filters associated with this subscription.
     *
     * @return An unmodifiable collection of filters
     */
    public Collection<NostrFilter> getFilters() {
        return this.filtersRO;
    }

    /**
     * Gets the executor for this subscription.
     *
     * @return The executor handling subscription tasks
     */
    protected AsyncExecutor getExecutor() {
        if (this.exc == null) {
            throw new IllegalStateException("Subscription not opened yet");
        }
        return this.exc;
    }

    /**
     * Gets the subscription ID.
     *
     * @return The subscription ID
     */
    public String getId() {
        return this.subId;
    }

    /**
     * Opens the subscription with the relay, starting the event flow.
     *
     * @return An async task representing the open operation
     */
    public AsyncTask<List<AsyncTask<NostrMessageAck>>> open() {
        if (opened) {
            throw new IllegalStateException("Subscription already opened");
        }
        NGEPlatform platform = NGEUtils.getPlatform();
        this.exc = platform.newSubscriptionExecutor();
        opened = true;
        return this.onOpen.apply(this);
    }

    public boolean isOpened() {
        return opened;
    }

    /**
     * Closes the subscription, stopping the event flow.
     *
     * @return An async task representing the close operation
     */
    public AsyncTask<List<AsyncTask<NostrMessageAck>>> close() {
        NGEPlatform platform = NGEUtils.getPlatform();
        if (!opened) return platform.wrapPromise((res, rej) -> {
            res.accept(Collections.emptyList());
        });
        opened = false;
        this.exc.close();
        AsyncTask<List<AsyncTask<NostrMessageAck>>> out = this.onClose.apply(this, getCloseMessage());
        registerClosure("closed by client");
        callCloseListeners();
        return out;
    }

    /**
     * Gets the subscription ID.
     *
     * @return The subscription ID
     */
    public String getSubId() {
        return subId;
    }

    /**
     * Adds a listener for events received on this subscription.
     *
     * @param listener The event listener to add
     * @return This subscription for method chaining
     */
    public NostrSubscription addEventListener(NostrSubEventListener listener) {
        assert listener != null;
        assert onEventListeners.contains(listener) == false;
        onEventListeners.add((NostrSubEventListener) listener);
        return this;
    }

    /**
     * Adds a listener for EOSE (End Of Stored Events) notifications.
     *
     * @param listener The EOSE listener to add
     * @return This subscription for method chaining
     */
    public NostrSubscription addEoseListener(NostrSubEoseListener listener) {
        assert listener != null;
        assert onEoseListeners.contains(listener) == false;
        onEoseListeners.add(listener);
        return this;
    }

    /**
     * Adds a listener for subscription close events.
     *
     * <p>
     * The listener will be notified when the subscription is closed both remotely
     * or locally using the {@link #close()} method.
     * </p>
     *
     * @param listener The close listener to add
     * @return This subscription for method chaining
     */
    public NostrSubscription addCloseListener(NostrSubCloseListener listener) {
        assert listener != null;
        assert onCloseListeners.contains(listener) == false;
        onCloseListeners.add(listener);
        return this;
    }

    /**
     * Adds a general subscription listener that may implement one or more specific listener interfaces.
     *
     * @param listener The listener to add
     * @return This subscription for method chaining
     */
    public NostrSubscription addListener(NostrSubListener listener) {
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

    /**
     * Removes a subscription listener.
     *
     * @param listener The listener to remove
     * @return This subscription for method chaining
     */
    public NostrSubscription removeListener(NostrSubListener listener) {
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

    protected void callEoseListeners(NostrRelay relay, boolean everyWhere) {
        if (onEoseListeners.isEmpty()) return;
        for (NostrSubEoseListener listener : onEoseListeners) {
            this.getExecutor()
                .run(() -> {
                    listener.onSubEose(relay, everyWhere);
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
            this.getExecutor()
                .run(() -> {
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
            this.getExecutor()
                .run(() -> {
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
            this.id = Objects.requireNonNull(id, "Subscription ID cannot be null");
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
            fragments = new ArrayList<>();
            fragments.add(id);
            return fragments;
        }
    }

    private NostrSubCloseMessage getCloseMessage() {
        return new NostrSubCloseMessage(getId());
    }
}
