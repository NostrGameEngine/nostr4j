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

import jakarta.annotation.Nullable;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.ngengine.nostr4j.cache.NostrCacheRelay;
import org.ngengine.nostr4j.cache.NostrEventCache;
import org.ngengine.nostr4j.event.SignedNostrEvent;
import org.ngengine.nostr4j.event.SignedNostrEvent.ReceivedSignedNostrEvent;
import org.ngengine.nostr4j.event.tracker.EventTracker;
import org.ngengine.nostr4j.event.tracker.ForwardSlidingWindowEventTracker;
import org.ngengine.nostr4j.event.tracker.NaiveEventTracker;
import org.ngengine.nostr4j.listeners.NostrNoticeListener;
import org.ngengine.nostr4j.listeners.NostrRelayComponent;
import org.ngengine.nostr4j.pool.ackpolicy.NostrPoolAckPolicy;
import org.ngengine.nostr4j.pool.ackpolicy.NostrPoolAnyAckPolicy;
import org.ngengine.nostr4j.pool.fetchpolicy.NostrFetchTask;
import org.ngengine.nostr4j.pool.fetchpolicy.NostrPoolFetchPolicy;
import org.ngengine.nostr4j.pool.fetchpolicy.NostrWaitForEventFetchPolicy;
import org.ngengine.nostr4j.proto.NostrMessage;
import org.ngengine.nostr4j.proto.NostrMessageAck;
import org.ngengine.nostr4j.proto.NostrMessageAck.Status;
import org.ngengine.nostr4j.proto.impl.NostrClosedMessage;
import org.ngengine.nostr4j.proto.impl.NostrEOSEMessage;
import org.ngengine.nostr4j.proto.impl.NostrNoticeMessage;
import org.ngengine.nostr4j.utils.UniqueId;
import org.ngengine.platform.AsyncTask;
import org.ngengine.platform.NGEPlatform;
import org.ngengine.platform.NGEUtils;

public class NostrPool {

    private static final Logger logger = Logger.getLogger(NostrPool.class.getName());
    private final Map<String, NostrSubscription> subscriptions = new ConcurrentHashMap<>();
    private final List<NostrNoticeListener> noticeListener = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<NostrRelay> relays = new CopyOnWriteArrayList<>();
    private final List<NostrRelay> relaysRO = Collections.unmodifiableList(relays);
    private final Supplier<EventTracker> defaultEventTracker;
    private final Map<String, ReadPlan> readPlans = new ConcurrentHashMap<>();
    private volatile Duration priorityTimeout = Duration.ofSeconds(2);

    /**
     * Sets how long a read waits for one query-priority tier before starting the next.
     * EOSE can advance the read earlier after its event and policy callbacks complete.
     * This is a per-tier fallback timeout, independent of the fetch policy's overall deadline.
     *
     * @param timeout positive, non-null wait for each higher-priority tier
     * @throws IllegalArgumentException if the duration is null, zero or negative
     */
    public void setPriorityTimeout(Duration timeout) {
        if (timeout == null || timeout.isNegative() || timeout.isZero()) throw new IllegalArgumentException("Invalid priority timeout");
        priorityTimeout = timeout;
    }

    private final class ReadPlan {
        final NostrSubscription sub;
        final List<NostrRelay> targets = new ArrayList<>();
        final Set<NostrRelay> started = new HashSet<>();
        final Set<NostrRelay> finished = new HashSet<>();
        final Set<NostrRelay> eosed = new HashSet<>();
        AsyncTask<Void> deadline;

        ReadPlan(NostrSubscription sub) {
            this.sub = sub;
            for (NostrRelay relay : relays) if (accepts(relay)) targets.add(relay);
            targets.sort(Comparator.comparingInt(NostrRelay::getQueryPriority).reversed());
        }

        boolean accepts(NostrRelay relay) {
            return true;
        }

        synchronized boolean allFinished() {
            return finished.containsAll(targets);
        }

        synchronized boolean allEosed() {
            return eosed.containsAll(targets);
        }

        synchronized List<AsyncTask<NostrMessageAck>> advance() {
            if (!sub.isOpened() || !finished.containsAll(started)) return Collections.emptyList();
            if (deadline != null) { deadline.cancel(); deadline = null; }
            List<NostrRelay> group = new ArrayList<>();
            for (NostrRelay relay : targets) if (!started.contains(relay)) {
                if (!group.isEmpty() && relay.getQueryPriority() != group.get(0).getQueryPriority()) break;
                group.add(relay);
            }
            if (group.isEmpty()) return Collections.emptyList();
            started.addAll(group);
            if (started.size() < targets.size()) deadline = sub.getExecutor().runLater(() -> {
                synchronized (ReadPlan.this) { finished.addAll(group); }
                advance();
                return null;
            }, priorityTimeout.toMillis(), TimeUnit.MILLISECONDS);
            List<AsyncTask<NostrMessageAck>> messages = new ArrayList<>();
            for (NostrRelay relay : group) {
                if (!sub.isOpened()) break;
                relay.beforeSendMessage(sub);
                messages.add(relay.sendMessage(sub));
                relay.afterSendMessage(sub);
            }
            return messages;
        }

        synchronized void close() {
            if (deadline != null) deadline.cancel();
            deadline = null;
        }
    }

    private NostrRelayComponent listener = new NostrRelayComponent() {
        @Override
        public boolean onRelayConnectRequest(NostrRelay relay) {
            return NostrPool.this.onRelayConnectRequest(relay);
        }

        @Override
        public boolean onRelayConnect(NostrRelay relay) {
            return NostrPool.this.onRelayConnect(relay);
        }

        @Override
        public boolean onRelayMessage(NostrRelay relay, NostrMessage message) {
            return NostrPool.this.onRelayMessage(relay, message);
        }

        @Override
        public boolean onRelayError(NostrRelay relay, Throwable error) {
            return NostrPool.this.onRelayError(relay, error);
        }

        @Override
        public boolean onRelayDisconnect(NostrRelay relay, String reason, boolean byClient) {
            return NostrPool.this.onRelayDisconnect(relay, reason, byClient);
        }

        @Override
        public boolean onRelaySend(NostrRelay relay, NostrMessage message) {
            return NostrPool.this.onRelaySend(relay, message);
        }

        @Override
        public boolean onRelayDisconnectRequest(NostrRelay relay, String reason) {
            return NostrPool.this.onRelayDisconnectRequest(relay, reason);
        }

        @Override
        public boolean onRelayBeforeSend(NostrRelay relay, NostrMessage message) {
            return NostrPool.this.onBeforeRelaySend(relay, message);
        }

        @Override
        public boolean onRelayAfterSend(NostrRelay relay, NostrMessage message) {
            return NostrPool.this.onAfterRelaySend(relay, message);
        }
    };

    public NostrPool() {
        this(() -> new ForwardSlidingWindowEventTracker());
    }

    public NostrPool(Supplier<EventTracker> defaultEventTracker) {
        this.defaultEventTracker = defaultEventTracker;
    }

    public NostrPool addNoticeListener(NostrNoticeListener listener) {
        this.noticeListener.add(listener);
        return this;
    }

    public NostrPool removeNoticeListener(NostrNoticeListener listener) {
        this.noticeListener.remove(listener);
        return this;
    }

    public AsyncTask<List<AsyncTask<NostrMessageAck>>> publish(SignedNostrEvent ev) {
        return publish(ev, NostrPoolAnyAckPolicy.get());
    }

    public AsyncTask<List<AsyncTask<NostrMessageAck>>> publish(SignedNostrEvent ev, NostrPoolAckPolicy ackPolicy) {
        List<AsyncTask<NostrMessageAck>> requests = sendMessage(ev);
        if (requests.isEmpty() && relays.stream().anyMatch(relay -> relay instanceof NostrCacheRelay))
            return AsyncTask.failed(new IllegalStateException("No network relays available for publication"));
        return ack(requests, ackPolicy);
    }

    /**
     * @deprecated Use {@link #publish(SignedNostrEvent)} instead.
     */
    @Deprecated
    public List<AsyncTask<NostrMessageAck>> send(SignedNostrEvent ev) {
        return sendMessage(ev);
    }

    private AsyncTask<List<AsyncTask<NostrMessageAck>>> ack(
        List<AsyncTask<NostrMessageAck>> promises,
        NostrPoolAckPolicy ackPolicy
    ) {
        return NGEUtils
            .getPlatform()
            .wrapPromise((res, rej) -> {
                if (promises.isEmpty()) {
                    res.accept(promises);
                    return;
                }

                AtomicInteger remaining = new AtomicInteger(promises.size());
                AtomicBoolean finished = new AtomicBoolean(false);
                Runnable checkPolicy = () -> {
                    int left = remaining.decrementAndGet();
                    if (finished.get()) return;
                    try {
                        if (ackPolicy.apply(promises) == Status.SUCCESS) {
                            if (finished.compareAndSet(false, true)) res.accept(promises);
                        } else if (left == 0 && finished.compareAndSet(false, true)) {
                            rej.accept(new IllegalStateException("Failed to achieve required acknowledgements"));
                        }
                    } catch (Throwable error) {
                        if (finished.compareAndSet(false, true)) rej.accept(error);
                    }
                };

                for (AsyncTask<NostrMessageAck> promise : promises) {
                    promise
                        .catchException(error -> checkPolicy.run())
                        .then(ack -> {
                            checkPolicy.run();
                            return null;
                        });
                }
            });
    }

    protected List<AsyncTask<NostrMessageAck>> sendMessage(NostrMessage message) {
        List<AsyncTask<NostrMessageAck>> promises = new ArrayList<>();
        List<NostrRelay> targets = new ArrayList<>();
        targets.addAll(relays);
        for (NostrRelay relay : targets) {
            relay.beforeSendMessage(message);
        }
        for (NostrRelay relay : targets) {
            // Local storage must never make a network publication appear acknowledged.
            if (message instanceof SignedNostrEvent && relay instanceof NostrCacheRelay) continue;
            assert dbg(() -> {
                logger.finer("sending message to relay " + relay.getUrl() + " " + message);
            });
            promises.add(relay.sendMessage(message));
        }
        for (NostrRelay relay : targets) {
            relay.afterSendMessage(message);
        }

        return promises;
    }

    public AsyncTask<NostrRelay> ensureRelay(String relay) {
        for (NostrRelay r : relays) {
            if (r.getUrl().equals(relay)) {
                return r.connect();
            }
        }
        return addRelay(createRelay(relay));
    }

    protected NostrRelay createRelay(String url) { return new NostrRelay(url); }

    /**
     * Adds a relay to this pool and connects to it if not already connected.
     *
     * @param relay The relay to attach to this pool
     * @return  A promise that resolves when the relay is connected and ready to use
     */
    public AsyncTask<NostrRelay> addRelay(NostrRelay relay) {
        if (!relays.contains(relay)) {
            relays.addIfAbsent(relay);
            if (relay.getComponent(NostrRelaySubManager.class) == null) {
                relay.addComponent(new NostrRelaySubManager());
            }
            if (!(relay instanceof NostrCacheRelay) && relay.getComponent(NostrRelayLifecycleManager.class) == null) {
                relay.addComponent(new NostrRelayLifecycleManager());
            }
            if (!(relay instanceof NostrCacheRelay) && relay.getComponent(NostrRelayWatchdog.class) == null) {
                relay.addComponent(new NostrRelayWatchdog());
            }
            relay.addComponent(listener);
        }
        return relay.connect();
    }

    /**
     * @deprecated Use {@link #addRelay(NostrRelay)}
     */
    @Deprecated
    public AsyncTask<NostrRelay> connectRelay(NostrRelay relay) {
        return addRelay(relay);
    }

    public NostrRelay removeRelay(NostrRelay relay) {
        if (relays.contains(relay)) {
            relay.removeComponent(listener);
            relays.remove(relay);
            for (ReadPlan plan : readPlans.values()) {
                synchronized (plan) {
                    if (plan.started.remove(relay)) {
                        NostrMessage close = new NostrSubscription.NostrSubCloseMessage(plan.sub.getId());
                        relay.beforeSendMessage(close);
                        relay.sendMessage(close);
                        relay.afterSendMessage(close);
                    }
                    plan.targets.remove(relay);
                    plan.finished.remove(relay);
                    plan.eosed.remove(relay);
                }
                plan.advance();
            }
            return relay;
        }
        return null;
    }

    public NostrSubscription subscribe(NostrFilter filter) {
        return subscribe(Arrays.asList(filter), defaultEventTracker);
    }

    /**
     * Creates a subscription based on the provided filters.
     * <p>
     * This method creates a subscription that will match events against the given filters,
     * using the default event tracker class for duplicate detection.
     * </p>
     *
     * @param filter A collection of filters that define what events to receive
     * @return A new subscription instance
     */
    public NostrSubscription subscribe(Collection<NostrFilter> filter) {
        return subscribe(filter, defaultEventTracker);
    }

    /**
     * Creates a subscription using the specified filter and event tracker.
     * <p>
     * This method allows specifying both the filter for matching events and
     * a custom event tracker implementation for handling duplicate events.
     * </p>
     *
     * @param filter The filter defining what events to receive
     * @param eventTracker The event tracker class to use for this subscription
     * @return A new subscription instance
     */
    public NostrSubscription subscribe(NostrFilter filter, Supplier<EventTracker> eventTracker) {
        return subscribe(Arrays.asList(filter), eventTracker);
    }

    public NostrSubscription subscribe(Collection<NostrFilter> filters, Supplier<EventTracker> eventTracker) {
        String subId = UniqueId.getNext();
        EventTracker tracker;
        try {
            tracker = Objects.requireNonNull(eventTracker.get(), "Event tracker cannot be null");
        } catch (Exception e) {
            throw new IllegalStateException("Cannot create subscription event tracker", e);
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
                ReadPlan plan = new ReadPlan(s);
                readPlans.put(s.getId(), plan);
                return plan.advance();
            },
            (s, closeMessage) -> {
                assert dbg(() -> {
                    logger.fine("closing subscription " + s.getId() + " reason: " + closeMessage);
                });

                subscriptions.remove(subId);
                ReadPlan plan = readPlans.remove(subId);
                if (plan == null) return Collections.emptyList();
                plan.close();
                List<AsyncTask<NostrMessageAck>> messages = new ArrayList<>();
                synchronized (plan) {
                    for (NostrRelay relay : plan.started) {
                        relay.beforeSendMessage(closeMessage);
                        messages.add(relay.sendMessage(closeMessage));
                        relay.afterSendMessage(closeMessage);
                    }
                }
                return messages;
            }
        );
        tracker.tuneFor(sub);
        subscriptions.put(subId, sub);
        return sub;
    }

    /**
     * Fetch up to numEvents events matching the given filter until timeout is
     * reached or numEvents have been received.
     *
     * <p>
     * The method might return less events if the timeout is reached before numEvents
     * </p>
     *
     * @param filter    The filter to match events against
     * @param numEvents The number of events to fetch
     * @param timeout   The maximum duration to wait for events, null means no
     *                  timeout
     * @return A promise that resolves with up to numEvents matching events or when
     *         the timeout is reached
     */
    public AsyncTask<List<SignedNostrEvent>> fetch(NostrFilter filter, int numEvents, @Nullable Duration timeout) {
        return fetch(filter, numEvents, false, timeout);
    }

    /**
     * Fetch up to maxEvents events matching the given filter until timeout is
     * reached, numEvents have been received or all relays have sent EOSE if
     * withEose is true.
     *
     * <p>
     * The method might return less events if the timeout is reached before numEvents
     * or if all relays have sent EOSE and withEose is true
     * </p>
     *
     * @param filter    The filter to match events against
     * @param numEvents The number of events to fetch
     * @param withEose  If true the fetch will end when all relays have sent EOSE
     *                  even if neither numEvents nor timeout are reached
     * @param timeout   The maximum duration to wait for events, null means no
     *                  timeout
     * @return A promise that resolves with up to numEvents matching events or when
     *         the timeout is reached
     *
     */
    public AsyncTask<List<SignedNostrEvent>> fetch(
        NostrFilter filter,
        int numEvents,
        boolean withEose,
        @Nullable Duration timeout
    ) {
        return fetch(Arrays.asList(filter), NostrWaitForEventFetchPolicy.get(e -> true, numEvents, withEose, timeout));
    }

    /**
     * Fetch events matching the given filters using the specified fetch policy.
     * @param filters The filters to match events against
     * @param fetchPolicy The fetch policy to determine when to stop fetching events
     * @return A promise that resolves with the list of matching events based on the fetch policy
     */
    public AsyncTask<List<SignedNostrEvent>> fetch(Collection<NostrFilter> filters, NostrPoolFetchPolicy fetchPolicy) {
        return fetch(filters, () -> new NaiveEventTracker(), fetchPolicy);
    }

    /**
     * Fetch events matching the given filters using the specified event tracker and fetch policy.
     * @param filters The filters to match events against
     * @param eventTracker A supplier that provides an event tracker instance for handling duplicates
     * @param fetchPolicy The fetch policy to determine when to stop fetching events
     * @return A promise that resolves with the list of matching events based on the fetch policy
     */
    public AsyncTask<List<SignedNostrEvent>> fetch(
        Collection<NostrFilter> filters,
        Supplier<EventTracker> eventTracker,
        NostrPoolFetchPolicy fetchPolicy
    ) {
        NGEPlatform platform = NGEUtils.getPlatform();
        NostrSubscription sub = subscribe(filters, eventTracker);
        AsyncTask<List<SignedNostrEvent>> task = platform.wrapPromise((res, rej) -> {
            List<SignedNostrEvent> events = Collections.synchronizedList(new ArrayList<>());

            assert dbg(() -> {
                logger.fine("Initialize fetch of " + filters + " for subscription " + sub.getId());
            });

            AtomicBoolean ended = new AtomicBoolean(false);

            Consumer<List<SignedNostrEvent>> done = evs -> {
                if (!ended.getAndSet(true)) {
                    ArrayList<SignedNostrEvent> safeEvs;
                    synchronized (evs) {
                        safeEvs = new ArrayList<>(evs);
                    }
                    // sort newest to oldest
                    safeEvs.sort((a, b) -> b.getCreatedAt().compareTo(a.getCreatedAt()));
                    assert dbg(() -> {
                        logger.fine("fetch done for fetch " + sub.getId() + " got " + safeEvs.size() + " events");
                        // check if events are in order newest to oldest
                        Instant last = null;
                        for (SignedNostrEvent e : safeEvs) {
                            if (last != null && e.getCreatedAt().isAfter(last)) {
                                logger.warning("events are not in order!");
                            }
                            last = e.getCreatedAt();
                        }
                    });
                    res.accept(Collections.unmodifiableList(safeEvs));
                    sub.close();
                }
            };

            sub
                .addListener(
                    fetchPolicy.getListener(
                        sub,
                        events,
                        () -> {
                            done.accept(events);
                        }
                    )
                )
                .open();
        });
        return new NostrFetchTask<>(task, sub::close);
    }

    protected boolean onRelayMessage(NostrRelay relay, NostrMessage rcv) {
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
                    ReadPlan plan = readPlans.get(subId);
                    if (plan != null) synchronized (plan) { plan.finished.add(relay); }

                    // check if it is closed in every relay
                    boolean isClosedEverywhere = plan == null || plan.allFinished();
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
                    } else if (plan != null) plan.advance();
                } else {
                    logger.warning("received closed for unknown subscription " + subId);
                }
            } else if (rcv instanceof NostrEOSEMessage) {
                NostrEOSEMessage msg = (NostrEOSEMessage) rcv;
                String subId = msg.getSubId();
                NostrSubscription sub = subscriptions.get(subId);
                if (sub != null) {
                    if (!sub.isOpened()) {
                        logger.finer("ignoring eose for non-open subscription " + subId + " from " + relay.getUrl());
                        return true;
                    }
                    // check if it is eosed in every relay
                    ReadPlan plan = readPlans.get(subId);
                    if (plan != null) synchronized (plan) { plan.finished.add(relay); plan.eosed.add(relay); }
                    boolean isEOSEEverywhere = plan != null && plan.allEosed();
                    logger.fine(
                        "received eose for subscription " +
                        subId +
                        " from " +
                        relay.getUrl() +
                        " isEOSEEverywhere: " +
                        isEOSEEverywhere
                    );
                    sub.callEoseListeners(relay, isEOSEEverywhere, () -> { if (plan != null) plan.advance(); });
                }
            } else if (rcv instanceof NostrNoticeMessage) {
                NostrNoticeMessage msg = (NostrNoticeMessage) rcv;
                String eventMessage = msg.getMessage();
                logger.info("Received notice from relay " + relay.getUrl() + ": " + eventMessage);
                for (NostrNoticeListener listener : noticeListener) {
                    try {
                        listener.onNotice(relay, eventMessage, null);
                    } catch (Throwable e) {
                        logger.log(Level.WARNING, "Error in notice listener", e);
                    }
                }
            } else if (rcv instanceof ReceivedSignedNostrEvent) {
                ReceivedSignedNostrEvent e = (ReceivedSignedNostrEvent) rcv;
                e.setRelayUrl(relay.getUrl());
                if (e.isExpired()) return true;
                List<AsyncTask<Boolean>> cachePolicies = new ArrayList<>();
                for (NostrRelay target : relays) if (target instanceof NostrCacheRelay) {
                    NostrEventCache cache = ((NostrCacheRelay) target).getCache();
                    AsyncTask<Boolean> policy = target == relay ? cache.isDeleted(e).then(deleted -> !deleted)
                        : cache.put(e.getId(), e, false).compose(ignored -> cache.isDeleted(e)).then(deleted -> !deleted);
                    policy.catchException(error -> logger.log(Level.WARNING, "Local cache policy failed", error));
                    cachePolicies.add(policy);
                }
                AsyncTask<Boolean> accepted = AsyncTask.all(cachePolicies).then(results -> results.stream().allMatch(Boolean::booleanValue));
                String subId = e.getSubId();
                NostrSubscription sub = subscriptions.get(subId);
                if (sub != null) {
                    ReadPlan plan = readPlans.get(subId);
                    if (plan == null || !plan.accepts(relay)) return true;
                    if (!sub.isOpened()) {
                        logger.finer("ignoring event for non-open subscription " + subId + " from " + relay.getUrl());
                        return true;
                    }
                    assert dbg(() -> {
                        logger.finer("received event for subscription " + subId);
                    });
                    boolean stored = false;
                    NostrRelaySubManager manager = relay.getComponent(NostrRelaySubManager.class);
                    if (manager != null && manager.isActive(sub) && !manager.isEose(sub)) stored = true;
                    sub.callEventListeners(e, stored, accepted.then(deliver -> deliver && !sub.eventTracker.seen(e)));
                } else {
                    logger.finer("Received event for closed subscription " + subId);
                }
            }
        } catch (Throwable t) {
            logger.log(Level.WARNING, "Error processing message from relay " + relay.getUrl(), t);
        }
        return true;
    }

    /**
     * @deprecated This method does not disconnect relay transports by design to
     *             support shared relays. This is deprecated because the name was misleading
     *             use {@link #clean()} to both unsubscribe and remove relays
     *              or {@link #removeAllRelays()} to just remove relays without unsubscribing or {@link #unsubscribeAll()} to just unsubscribe without removing relays.
     *
     */
    @Deprecated
    public List<NostrRelay> close() {
        // close all subscriptions owned by this pool
        for (NostrSubscription sub : subscriptions.values()) {
            sub.close();
        }

        // detach all relays from this pool without disconnecting them
        List<NostrRelay> detachedRelays = new ArrayList<>(relays);
        for (NostrRelay relay : detachedRelays) {
            removeRelay(relay);
        }
        return detachedRelays;
    }

    public List<NostrRelay> removeAllRelays() {
        List<NostrRelay> detachedRelays = new ArrayList<>(relays);
        for (NostrRelay relay : relays) {
            removeRelay(relay);
        }
        return detachedRelays;
    }

    public List<NostrRelay> clean() {
        unsubscribeAll();
        return removeAllRelays();
    }

    public void unsubscribeAll() {
        for (NostrSubscription sub : subscriptions.values()) {
            sub.close();
        }
    }

    public List<NostrRelay> getRelays() {
        return relaysRO;
    }

    protected boolean onRelayConnect(NostrRelay relay) {
        // subscribe the relay to everything
        for (NostrSubscription sub : subscriptions.values()) {
            ReadPlan plan = readPlans.get(sub.getId());
            if (plan != null) synchronized (plan) {
                if (sub.isOpened() && plan.started.contains(relay)) {
                    plan.finished.remove(relay);
                    plan.eosed.remove(relay);
                    relay.beforeSendMessage(sub);
                    relay.sendMessage(sub);
                    relay.afterSendMessage(sub);
                } else if (sub.isOpened() && plan.accepts(relay) && !plan.targets.contains(relay)) {
                    plan.targets.add(relay);
                    plan.started.add(relay);
                    relay.beforeSendMessage(sub);
                    relay.sendMessage(sub);
                    relay.afterSendMessage(sub);
                }
            }
        }
        return true;
    }

    protected boolean onRelayError(NostrRelay relay, Throwable error) {
        for (NostrNoticeListener listener : noticeListener) {
            try {
                listener.onNotice(relay, error.getMessage(), error);
            } catch (Throwable e) {
                logger.log(Level.WARNING, "Error in notice listener", e);
            }
        }
        return true;
    }

    protected boolean onRelayConnectRequest(NostrRelay relay) {
        return true;
    }

    protected boolean onRelayDisconnect(NostrRelay relay, String reason, boolean byClient) {
        return true;
    }

    protected boolean onRelaySend(NostrRelay relay, NostrMessage message) {
        return true;
    }

    protected boolean onRelayDisconnectRequest(NostrRelay relay, String reason) {
        return true;
    }

    protected boolean onBeforeRelaySend(NostrRelay relay, NostrMessage message) {
        return true;
    }

    protected boolean onAfterRelaySend(NostrRelay relay, NostrMessage message) {
        return true;
    }
}
