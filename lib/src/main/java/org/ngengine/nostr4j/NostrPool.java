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

import static org.ngengine.nostr4j.utils.NostrUtils.dbg;

import java.lang.reflect.InvocationTargetException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;
import org.ngengine.nostr4j.event.SignedNostrEvent;
import org.ngengine.nostr4j.event.SignedNostrEvent.ReceivedSignedNostrEvent;
import org.ngengine.nostr4j.event.tracker.EventTracker;
import org.ngengine.nostr4j.event.tracker.ForwardSlidingWindowEventTracker;
import org.ngengine.nostr4j.event.tracker.NaiveEventTracker;
import org.ngengine.nostr4j.listeners.NostrNoticeListener;
import org.ngengine.nostr4j.listeners.NostrRelayComponent;
import org.ngengine.nostr4j.platform.AsyncTask;
import org.ngengine.nostr4j.platform.Platform;
import org.ngengine.nostr4j.transport.NostrMessage;
import org.ngengine.nostr4j.transport.NostrMessageAck;
import org.ngengine.nostr4j.transport.impl.NostrClosedMessage;
import org.ngengine.nostr4j.transport.impl.NostrEOSEMessage;
import org.ngengine.nostr4j.transport.impl.NostrNoticeMessage;
import org.ngengine.nostr4j.utils.NostrUtils;
import org.ngengine.nostr4j.utils.ScheduledAction;

public class NostrPool implements NostrRelayComponent {

    private static final Logger logger = Logger.getLogger(NostrPool.class.getName());
    private static final AtomicLong subCounter = new AtomicLong(0);
    private final Map<String, NostrSubscription> subscriptions = new ConcurrentHashMap<>();
    private final List<NostrNoticeListener> noticeListener = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<NostrRelay> relays = new CopyOnWriteArrayList<>();
    private final List<ScheduledAction> scheduledActions = new CopyOnWriteArrayList<>();
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
        return sendMessage(ev);
    }

    protected AsyncTask<List<NostrMessageAck>> sendMessage(NostrMessage message) {
        List<AsyncTask<NostrMessageAck>> promises = new ArrayList<>();
        for (NostrRelay relay : relays) {
            assert dbg(() -> {
                logger.finer("sending message to relay " + relay.getUrl() + " " + message);
            });
            promises.add(relay.sendMessage(message));
        }
        Platform platform = NostrUtils.getPlatform();
        return platform.awaitAll(promises);
    }

    public AsyncTask<NostrRelay> connectRelay(NostrRelay relay) {
        if (!relays.contains(relay)) {
            relays.add(relay);
            if (relay.getComponent(NostrRelaySubManager.class) == null) {
                relay.addComponent(new NostrRelaySubManager());
            }
            if (relay.getComponent(NostrRelayLifecycleManager.class) == null) {
                relay.addComponent(new NostrRelayLifecycleManager());
            }
            relay.addComponent(this);
        }
        return relay.connect();
    }

    public NostrRelay removeRelay(NostrRelay relay) {
        if (relays.contains(relay)) {
            relay.removeComponent(this);
            relays.remove(relay);
            return relay;
        }
        return null;
    }

    public NostrSubscription subscribe(NostrFilter filter) {
        return subscribe(Arrays.asList(filter), defaultEventTracker);
    }

    public NostrSubscription subscribe(Collection<NostrFilter> filter) {
        return subscribe(filter, defaultEventTracker);
    }

    public NostrSubscription subscribe(NostrFilter filter, Class<? extends EventTracker> eventTracker) {
        return subscribe(Arrays.asList(filter), eventTracker);
    }

    public NostrSubscription subscribe(Collection<NostrFilter> filters, Class<? extends EventTracker> eventTracker) {
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

        assert dbg(() -> {
            logger.fine("subscribing to " + subId + " with filter " + filters);
        });
        NostrSubscription sub = new NostrSubscription(
            subId,
            filters,
            tracker,
            s -> {
                assert dbg(() -> {
                    logger.fine("opening subscription " + s.getId());
                });
                return this.sendMessage(s);
            },
            (s, closeMessage) -> {
                assert dbg(() -> {
                    logger.fine("closing subscription " + s.getId() + " reason: " + closeMessage);
                });

                subscriptions.remove(subId);
                return this.sendMessage(closeMessage);
            }
        );
        tracker.tuneFor(sub);
        subscriptions.put(subId, sub);
        return sub;
    }

    public AsyncTask<List<SignedNostrEvent>> fetch(NostrFilter filter) {
        return fetch(Arrays.asList(filter));
    }

    public AsyncTask<List<SignedNostrEvent>> fetch(Collection<NostrFilter> filters) {
        return fetch(filters, 1, TimeUnit.MINUTES);
    }

    public AsyncTask<List<SignedNostrEvent>> fetch(NostrFilter filter, long timeout, TimeUnit unit) {
        return fetch(Arrays.asList(filter), timeout, unit);
    }

    public AsyncTask<List<SignedNostrEvent>> fetch(Collection<NostrFilter> filters, long timeout, TimeUnit unit) {
        return fetch(filters, timeout, unit, NaiveEventTracker.class);
    }

    public AsyncTask<List<SignedNostrEvent>> fetch(NostrFilter filter, Class<? extends EventTracker> eventTracker) {
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
        return platform.wrapPromise((res, rej) -> {
            List<SignedNostrEvent> events = new ArrayList<>();

            assert dbg(() -> {
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
            });

            AtomicBoolean ended = new AtomicBoolean(false);
            ScheduledAction scheduled = new ScheduledAction(
                platform.getTimestampSeconds() + unit.toSeconds(timeout),
                () -> {
                    if (ended.get()) return;
                    logger.warning("fetch timeout for fetch " + sub.getId());
                    sub.close();
                    rej.accept(new Exception("timeout"));
                }
            );

            scheduledActions.add(scheduled);
            sub
                .listenEose(all -> {
                    if (all) {
                        assert dbg(() -> {
                            logger.fine("fetch eose for fetch " + sub.getId() + " with received events: " + events);
                        });
                        res.accept(events);
                        ended.set(true);
                        scheduledActions.remove(scheduled);
                        sub.close();
                    }
                })
                .listenEvent((e, stored) -> {
                    assert dbg(() -> {
                        logger.finer("fetch event " + e + " for subscription " + sub.getId());
                    });

                    events.add(e);
                })
                .listenClose(reason -> {
                    assert dbg(() -> {
                        logger.fine("fetch close " + reason + " for subscription " + sub.getId());
                    });
                })
                .open();
        });
    }

    @Override
    public boolean onRelayMessage(NostrRelay relay, NostrMessage rcv) {
        assert dbg(() -> {
            logger.finer("received message from relay " + relay.getUrl() + " : " + rcv);
        });

        try {
            // String type = NostrUtils.safeString(doc.get(0));
            if (rcv instanceof NostrClosedMessage) {
                NostrClosedMessage msg = (NostrClosedMessage) rcv;
                String subId = msg.getSubId();
                String reason = msg.getReason();
                NostrSubscription sub = subscriptions.get(subId);
                if (sub != null) {
                    // register that the subscription was closed for a reason
                    sub.registerClosure(reason);

                    // check if it is closed in every relay
                    boolean isClosedEverywhere = true;
                    for (NostrRelay r : relays) {
                        NostrRelaySubManager m = r.getComponent(NostrRelaySubManager.class);
                        if (m != null && m.isActive(sub)) {
                            isClosedEverywhere = false;
                            break;
                        }
                    }

                    logger.fine(
                        "received closed for subscription " +
                        subId +
                        " from " +
                        relay.getUrl() +
                        " for reason: " +
                        reason +
                        " isClosedEverywhere: " +
                        isClosedEverywhere
                    );

                    // if so, call the close listeners
                    if (isClosedEverywhere) {
                        sub.callCloseListeners();
                        subscriptions.remove(subId);
                    }
                } else {
                    logger.warning("received closed for unknown subscription " + subId);
                }
            } else if (rcv instanceof NostrEOSEMessage) {
                NostrEOSEMessage msg = (NostrEOSEMessage) rcv;
                String subId = msg.getSubId();
                NostrSubscription sub = subscriptions.get(subId);
                if (sub != null) {
                    // check if it is eosed in every relay
                    boolean isEOSEEverywhere = true;
                    for (NostrRelay r : relays) {
                        NostrRelaySubManager m = r.getComponent(NostrRelaySubManager.class);
                        if (m != null && m.isActive(sub) && !m.isEose(sub)) {
                            isEOSEEverywhere = false;
                            break;
                        }
                    }
                    logger.fine(
                        "received eose for subscription " +
                        subId +
                        " from " +
                        relay.getUrl() +
                        " isEOSEEverywhere: " +
                        isEOSEEverywhere
                    );
                    sub.callEoseListeners(isEOSEEverywhere);
                } else {
                    logger.warning("received invalid eose for subscription " + subId);
                }
            } else if (rcv instanceof NostrNoticeMessage) {
                NostrNoticeMessage msg = (NostrNoticeMessage) rcv;
                String eventMessage = msg.getMessage();
                logger.info("Received notice from relay " + relay.getUrl() + ": " + eventMessage);
                noticeListener.forEach(listener -> listener.onNotice(relay, eventMessage, null));
            } else if (rcv instanceof ReceivedSignedNostrEvent) {
                ReceivedSignedNostrEvent e = (ReceivedSignedNostrEvent) rcv;
                String subId = e.getSubId();
                NostrSubscription sub = subscriptions.get(subId);
                if (sub != null) {
                    assert dbg(() -> {
                        logger.finer("received event for subscription " + subId);
                    });
                    // if (verifyEvents && !e.verify()) throw new Exception(
                    //     "Event signature is invalid"
                    // );
                    if (!sub.eventTracker.seen(e)) {
                        assert dbg(() -> {
                            logger.finest("Event not seen " + e.getId() + " for subscription " + subId);
                        });

                        boolean stored = false;

                        // check if current relay reached EOSE
                        NostrRelaySubManager m = relay.getComponent(NostrRelaySubManager.class);
                        if (m != null && m.isActive(sub) && !m.isEose(sub)) {
                            stored = true;
                        }
                        final boolean storedFinal = stored;
                        // syncher.then((n)->{
                        sub.callEventListeners(e, storedFinal);
                        //     return null;
                        // });

                    } else {
                        assert dbg(() -> {
                            logger.finest("Event already seen " + e.getId() + " for subscription " + subId);
                        });
                    }
                } else {
                    logger.warning("Received event for unknown subscription " + subId + " " + rcv);
                }
            }
        } catch (Throwable t) {
            t.printStackTrace();
        }
        return true;
    }

    public void close() {
        // close all subs
        for (NostrSubscription sub : subscriptions.values()) {
            sub.close();
        }

        // close all relays
        for (NostrRelay relay : relays) {
            relay.disconnect("closed by pool");
        }
        relays.clear();
    }

    public void unsubscribeAll() {
        for (NostrSubscription sub : subscriptions.values()) {
            sub.close();
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
    public boolean onRelayConnect(NostrRelay relay) {
        // subscribe the relay to everything
        for (NostrSubscription sub : subscriptions.values()) {
            relay.sendMessage(sub);
        }
        return true;
    }

    @Override
    public boolean onRelayError(NostrRelay relay, Throwable error) {
        noticeListener.forEach(listener -> listener.onNotice(relay, error.getMessage(), error));
        return true;
    }

    @Override
    public boolean onRelayConnectRequest(NostrRelay relay) {
        return true;
    }

    @Override
    public boolean onRelayLoop(NostrRelay relay, Instant nowInstant) {
        return true;
    }

    @Override
    public boolean onRelayDisconnect(NostrRelay relay, String reason, boolean byClient) {
        return true;
    }

    @Override
    public boolean onRelaySend(NostrRelay relay, NostrMessage message) {
        return true;
    }

    @Override
    public boolean onRelayDisconnectRequest(NostrRelay relay, String reason) {
        return true;
    }
}
