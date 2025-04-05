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

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;
import org.ngengine.nostr4j.event.SignedNostrEvent;
import org.ngengine.nostr4j.event.tracker.EventTracker;
import org.ngengine.nostr4j.event.tracker.ForwardSlidingWindowEventTracker;
import org.ngengine.nostr4j.event.tracker.NaiveEventTracker;
import org.ngengine.nostr4j.listeners.NostrNoticeListener;
import org.ngengine.nostr4j.listeners.NostrRelayListener;
import org.ngengine.nostr4j.platform.AsyncTask;
import org.ngengine.nostr4j.platform.Platform;
import org.ngengine.nostr4j.transport.NostrMessage;
import org.ngengine.nostr4j.transport.NostrMessageAck;
import org.ngengine.nostr4j.transport.NostrTransport;
import org.ngengine.nostr4j.utils.NostrUtils;
import org.ngengine.nostr4j.utils.ScheduledAction;

public class NostrPool implements NostrRelayListener {

    private static final Logger logger = Logger.getLogger(
        NostrPool.class.getName()
    );
    private static final AtomicLong subCounter = new AtomicLong(0);
    private final Map<String, NostrSubscription> subscriptions =
        new ConcurrentHashMap<>();
    private final List<NostrNoticeListener> noticeListener =
        new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<NostrRelay> relays =
        new CopyOnWriteArrayList<>();
    private final List<ScheduledAction> scheduledActions =
        new CopyOnWriteArrayList<>();
    private final Class<? extends EventTracker> defaultEventTracker;
    private volatile boolean verifyEvents = true;

    public NostrPool() {
        this(ForwardSlidingWindowEventTracker.class);
    }

    public NostrPool(Class<? extends EventTracker> defaultEventTracker) {
        this.defaultEventTracker = defaultEventTracker;
    }

    public void setVerifyEvents(boolean verifyEvents) {
        this.verifyEvents = verifyEvents;
    }

    public boolean isVerifyEvents() {
        return verifyEvents;
    }

    public void addNoticeListener(NostrNoticeListener listener) {
        this.noticeListener.add(listener);
    }

    public void removeNoticeListener(NostrNoticeListener listener) {
        this.noticeListener.remove(listener);
    }

    public AsyncTask<List<NostrMessageAck>> send(SignedNostrEvent ev) {
        return send(ev);
    }

    protected AsyncTask<List<NostrMessageAck>> send(NostrMessage message) {
        List<AsyncTask<NostrMessageAck>> promises = new ArrayList<>();
        for (NostrRelay relay : relays) {
            logger.fine("sending message to relay " + relay.getUrl());
            promises.add(relay.sendMessage(message));
        }
        Platform platform = NostrUtils.getPlatform();
        return platform.waitAll(promises);
    }

    public NostrRelay ensureRelay(String url) {
        Platform platform = NostrUtils.getPlatform();
        return ensureRelay(url, platform.newTransport());
    }

    public NostrRelay ensureRelay(String url, NostrTransport transport) {
        NostrRelay relay = null;
        for (NostrRelay r : relays) {
            if (r.getUrl().equals(url)) {
                relay = r;
                break;
            }
        }
        if (relay == null) {
            relay = new NostrRelay(url);
            relay.addListener(this);
            relays.add(relay);
        }
        if (!relay.isConnected()) {
            relay.connect();
        }
        return relay;
    }

    public NostrRelay ensureRelay(NostrRelay relay) {
        if (!relays.contains(relay)) {
            relays.add(relay);
            relay.addListener(this);
        }
        if (!relay.isConnected()) {
            relay.connect();
        }
        return relay;
    }

    public void disconnectRelay(String url) {
        for (NostrRelay relay : relays) {
            if (relay.getUrl().equals(url)) {
                relay.removeListener(this);
                relay.disconnect("Removed by user");
                relays.remove(relay);
                break;
            }
        }
    }

    public void disconnectRelay(NostrRelay relay) {
        if (relays.contains(relay)) {
            relay.removeListener(this);
            relay.disconnect("Removed by user");
            relays.remove(relay);
        }
    }

    public NostrSubscription subscribe(NostrFilter filter) {
        return subscribe(Arrays.asList(filter), defaultEventTracker);
    }

    public NostrSubscription subscribe(Collection<NostrFilter> filter) {
        return subscribe(filter, defaultEventTracker);
    }

    public NostrSubscription subscribe(
        NostrFilter filter,
        Class<? extends EventTracker> eventTracker
    ) {
        return subscribe(Arrays.asList(filter), eventTracker);
    }

    public NostrSubscription subscribe(
        Collection<NostrFilter> filters,
        Class<? extends EventTracker> eventTracker
    ) {
        String subId = "nostr4j-" + subCounter.incrementAndGet();
        EventTracker tracker;
        try {
            tracker = eventTracker.getDeclaredConstructor().newInstance();
        } catch (
            InstantiationException
            | IllegalAccessException
            | IllegalArgumentException
            | InvocationTargetException
            | NoSuchMethodException
            | SecurityException e
        ) {
            throw new RuntimeException("Unable to create event tracker", e);
        }
        logger.fine("subscribing to " + subId + " with filter " + filters);
        NostrSubscription sub = new NostrSubscription(
            subId,
            filters,
            tracker,
            s -> {
                logger.fine("starting subscription " + s.getId());
                return this.send(s);
            },
            (s, closeMessage) -> {
                logger.fine(
                    "closing subscription " +
                    s.getId() +
                    " reason: " +
                    closeMessage
                );
                subscriptions.remove(subId);
                return this.send(closeMessage);
            }
        );
        tracker.tuneFor(sub);
        subscriptions.put(subId, sub);
        return sub;
    }

    public AsyncTask<List<SignedNostrEvent>> fetch(NostrFilter filter) {
        return fetch(Arrays.asList(filter));
    }

    public AsyncTask<List<SignedNostrEvent>> fetch(
        Collection<NostrFilter> filters
    ) {
        return fetch(filters, 1, TimeUnit.MINUTES);
    }

    public AsyncTask<List<SignedNostrEvent>> fetch(
        NostrFilter filter,
        long timeout,
        TimeUnit unit
    ) {
        return fetch(Arrays.asList(filter), timeout, unit);
    }

    public AsyncTask<List<SignedNostrEvent>> fetch(
        Collection<NostrFilter> filters,
        long timeout,
        TimeUnit unit
    ) {
        return fetch(filters, timeout, unit, NaiveEventTracker.class);
    }

    public AsyncTask<List<SignedNostrEvent>> fetch(
        NostrFilter filter,
        Class<? extends EventTracker> eventTracker
    ) {
        return fetch(Arrays.asList(filter), eventTracker);
    }

    public AsyncTask<List<SignedNostrEvent>> fetch(
        Collection<NostrFilter> filters,
        Class<? extends EventTracker> eventTracker
    ) {
        return fetch(filters, 1, TimeUnit.MINUTES, eventTracker);
    }

    public AsyncTask<List<SignedNostrEvent>> fetch(
        NostrFilter filters,
        long timeout,
        TimeUnit unit,
        Class<? extends EventTracker> eventTracker
    ) {
        return fetch(Arrays.asList(filters), timeout, unit, eventTracker);
    }

    public AsyncTask<List<SignedNostrEvent>> fetch(
        Collection<NostrFilter> filters,
        long timeout,
        TimeUnit unit,
        Class<? extends EventTracker> eventTracker
    ) {
        Platform platform = NostrUtils.getPlatform();
        NostrSubscription sub = subscribe(filters, eventTracker);
        return platform.promisify(
            (res, rej) -> {
                List<SignedNostrEvent> events = new ArrayList<>();

                logger.fine(
                    "Initialize fetch of " +
                    filters +
                    " with timeout " +
                    timeout +
                    " " +
                    unit +
                    " for subscription " +
                    sub.getId()
                );

                scheduledActions.add(
                    new ScheduledAction(
                        platform.getTimestampSeconds() +
                        unit.toSeconds(timeout),
                        () -> {
                            logger.fine(
                                "fetch timeout for subscription " + sub.getId()
                            );
                            sub.close("timeout");
                            rej.accept(new Exception("timeout"));
                        }
                    )
                );

                sub
                    .listenEose(s -> {
                        logger.fine("fetch eose for subscription " + s.getId());
                        s.close("eose");
                    })
                    .listenEvent((s, e, stored) -> {
                        logger.fine(
                            "fetch event " +
                            e +
                            " for subscription " +
                            s.getId()
                        );
                        events.add(e);
                    })
                    .listenClose((s, reason) -> {
                        logger.fine(
                            "fetch close " +
                            reason +
                            " for subscription " +
                            s.getId()
                        );
                        res.accept(events);
                    })
                    .open();
            },
            sub.getExecutor()
        );
    }

    @Override
    public void onRelayMessage(NostrRelay relay, List<Object> doc) {
        // logger.fine("received message from relay " + relay.getUrl() + " : " + doc);
        try {
            String type = NostrUtils.safeString(doc.get(0));
            if (type.equals("CLOSED")) {
                // TODO cound relays
                String subId = NostrUtils.safeString(doc.get(1));
                String reason = doc.size() > 2
                    ? NostrUtils.safeString(doc.get(2))
                    : "";
                NostrSubscription sub = subscriptions.get(subId);
                if (sub != null) {
                    logger.fine("received closed for subscription " + subId);
                    sub.callCloseListeners(reason);
                    subscriptions.remove(subId);
                } else {
                    logger.fine(
                        "received closed for unknown subscription " + subId
                    );
                }
            } else if (type.equals("EOSE")) {
                // TODO cound relays
                String subId = NostrUtils.safeString(doc.get(1));
                NostrSubscription sub = subscriptions.get(subId);
                if (sub != null && !sub.isEose()) {
                    logger.fine("received eose for subscription " + subId);
                    sub.setEose(true);
                    sub.callEoseListeners();
                } else {
                    logger.fine(
                        "received invalid eose for subscription " + subId
                    );
                }
            } else if (type.equals("NOTICE")) {
                String eventMessage = NostrUtils.safeString(doc.get(1));
                logger.fine(
                    "Received notice from relay " +
                    relay.getUrl() +
                    ": " +
                    eventMessage
                );
                noticeListener.forEach(listener ->
                    listener.onNotice(relay, eventMessage)
                );
            } else if (type.equals("EVENT")) {
                String subId = NostrUtils.safeString(doc.get(1));
                NostrSubscription sub = subscriptions.get(subId);
                if (sub != null) {
                    // logger.fine("received event for subscription " + subId);
                    Map<String, Object> eventMap =
                        (Map<String, Object>) doc.get(2);
                    SignedNostrEvent e = new SignedNostrEvent(eventMap);
                    if (verifyEvents && !e.verify()) throw new Exception(
                        "Event signature is invalid"
                    );
                    if (!sub.eventTracker.seen(e)) {
                        logger.fine(
                            "Event not seen " +
                            e.getId() +
                            " for subscription " +
                            subId
                        );
                        sub.callEventListeners(e, !sub.isEose());
                    } else {
                        logger.fine(
                            "Event already seen " +
                            e.getId() +
                            " for subscription " +
                            subId
                        );
                    }
                } else {
                    logger.warning(
                        "Received event for unknown subscription " + subId
                    );
                }
            }
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

    public void close() {
        // close all subs
        for (NostrSubscription sub : subscriptions.values()) {
            sub.close("closed by user");
        }

        // close all relays
        for (NostrRelay relay : relays) {
            relay.disconnect("closed by pool");
        }
        relays.clear();
    }

    public void unsubscribeAll() {
        for (NostrSubscription sub : subscriptions.values()) {
            sub.close("closed by user");
        }
    }

    public List<String> getRelays() {
        List<String> urls = new ArrayList<>();
        for (NostrRelay relay : relays) {
            urls.add(relay.getUrl());
        }
        return urls;
    }

    @Override
    public void onRelayConnect(NostrRelay relay) {
        // subscribe the relay to everything
        for (NostrSubscription sub : subscriptions.values()) {
            relay.sendMessage(sub);
        }
    }
}
