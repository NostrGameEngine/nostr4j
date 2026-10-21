/**
 * BSD 3-Clause License
 *
 * Copyright (c) 2025, Riccardo Balbo
 */
package org.ngengine.nostr4j.rtc.routing.topology;

import java.io.Closeable;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.ngengine.nostr4j.NostrFilter;
import org.ngengine.nostr4j.NostrPool;
import org.ngengine.nostr4j.NostrSubscription;
import org.ngengine.nostr4j.event.SignedNostrEvent;
import org.ngengine.nostr4j.keypair.NostrKeyPair;
import org.ngengine.nostr4j.proto.NostrMessageAck;
import org.ngengine.nostr4j.rtc.routing.NodeId;
import org.ngengine.nostr4j.rtc.routing.RoutingLimits;
import org.ngengine.nostr4j.rtc.routing.RoutingProtocol;
import org.ngengine.nostr4j.rtc.routing.RoutingScope;
import org.ngengine.nostr4j.rtc.signal.NostrRTCConnectSignal;
import org.ngengine.nostr4j.rtc.signal.NostrRTCLocalPeer;
import org.ngengine.nostr4j.rtc.signal.NostrRTCPeer;
import org.ngengine.platform.AsyncExecutor;
import org.ngengine.platform.AsyncTask;
import org.ngengine.platform.NGEUtils;

/**
 * Always-on Nostr control plane for encrypted dc4 topology snapshots.
 */
public final class TopologyControlPlane implements Closeable {

    public interface Listener {
        void onTopologyChanged();
    }

    private static final Logger logger = Logger.getLogger(TopologyControlPlane.class.getName());
    private static final long PUBLISH_DEBOUNCE_MS = 250L;

    private final RoutingScope scope;
    private final NostrRTCLocalPeer localPeer;
    private final NostrKeyPair roomKeys;
    private final NostrKeyPair routingKeys;
    private final NostrPool pool;
    private final Duration expiration;
    private final Duration refreshInterval;
    private final AsyncExecutor executor;
    private final AsyncExecutor legacyExecutor;
    private final TopologyEventCodec codec = new TopologyEventCodec();
    private final TopologySnapshotStore store = new TopologySnapshotStore();
    private final AtomicLong revision = new AtomicLong();
    private final Map<String, NostrRTCPeer> presences = new HashMap<String, NostrRTCPeer>();

    private volatile Listener listener;
    private volatile NostrSubscription subscription;
    private volatile AsyncTask<Void> refreshTask;
    private volatile AsyncTask<Void> debounceTask;
    private volatile boolean started;
    private volatile boolean closed;
    // Handoff/close serialization never holds the control-plane monitor across pool or listener code.
    private final Object publicationHandoff = new Object();
    private final Object legacyHandoff = new Object();
    private final Supplier<Instant> clock;
    private long lastCreatedAtSecond = Long.MIN_VALUE;
    private volatile Publication activePublication;
    private Publication pendingPublication;
    private volatile LegacyPublication activeLegacyPublication;
    private LegacyPublication pendingLegacyPublication;
    private boolean legacyPumpScheduled;
    private long lastLegacyRequestedRevision;
    private AsyncTask<Void> eligibilityTask;
    private long eligibilityGeneration;
    private List<TopologyNeighbor> currentNeighbors = Collections.emptyList();

    public TopologyControlPlane(
        RoutingScope scope,
        NostrRTCLocalPeer localPeer,
        NostrKeyPair roomKeys,
        NostrKeyPair routingKeys,
        NostrPool pool,
        Duration expiration,
        Duration signalingInterval
    ) {
        this(scope, localPeer, roomKeys, routingKeys, pool, expiration, signalingInterval, Instant::now);
    }

    TopologyControlPlane(
        RoutingScope scope,
        NostrRTCLocalPeer localPeer,
        NostrKeyPair roomKeys,
        NostrKeyPair routingKeys,
        NostrPool pool,
        Duration expiration,
        Duration signalingInterval,
        Supplier<Instant> clock
    ) {
        this.clock = Objects.requireNonNull(clock, "Topology clock cannot be null");
        this.scope = Objects.requireNonNull(scope, "Routing scope cannot be null");
        this.localPeer = Objects.requireNonNull(localPeer, "Local peer cannot be null");
        this.roomKeys = Objects.requireNonNull(roomKeys, "Room keypair cannot be null");
        this.routingKeys = Objects.requireNonNull(routingKeys, "Routing keypair cannot be null");
        this.pool = Objects.requireNonNull(pool, "Nostr pool cannot be null");
        this.expiration = Objects.requireNonNull(expiration, "Topology expiration cannot be null");
        Duration halfExpiry = expiration.dividedBy(2);
        this.refreshInterval = signalingInterval.compareTo(halfExpiry) < 0 ? signalingInterval : halfExpiry;
        if (expiration.isZero() || expiration.isNegative() || refreshInterval.isZero() || refreshInterval.isNegative()) {
            throw new IllegalArgumentException("Topology timing must be positive");
        }
        if (expiration.compareTo(Duration.ofSeconds(RoutingLimits.MAX_TOPOLOGY_LIFETIME_SECONDS)) > 0) {
            throw new IllegalArgumentException("Topology expiration exceeds limit");
        }
        this.executor = NGEUtils.getPlatform().newAsyncExecutor(TopologyControlPlane.class);
        this.legacyExecutor = NGEUtils.getPlatform().newAsyncExecutor(LegacyPublication.class);
    }

    public synchronized void setListener(Listener listener) {
        this.listener = listener;
    }

    public synchronized void start() {
        if (closed) throw new IllegalStateException("Topology control plane is closed");
        if (started) return;
        started = true;
        NostrFilter filter = subscriptionFilter(scope, expiration, Instant.now());
        subscription = pool.subscribe(filter);
        subscription.addEventListener((sub, event, stored) -> acceptEvent(event, Instant.now()));
        subscription.open();
        requestPublish(currentNeighbors);
        scheduleRefresh();
    }

    static NostrFilter subscriptionFilter(RoutingScope scope, Duration expiration, Instant now) {
        return new NostrFilter()
            .withKind(RoutingProtocol.TOPOLOGY_EVENT_KIND)
            .withTag("t", RoutingProtocol.TOPOLOGY_EVENT_TYPE)
            .withTag("P", scope.getRoomPubkey().asHex())
            .since(now.minus(expiration).truncatedTo(ChronoUnit.SECONDS))
            .limit(2 * RoutingLimits.MAX_TOPOLOGY_SNAPSHOTS);
    }

    public void updatePresences(Collection<NostrRTCConnectSignal> announces, Instant now) {
        boolean changed;
        synchronized (this) {
            if (closed) return;
            Map<String, NostrRTCPeer> updated = new HashMap<String, NostrRTCPeer>();
            for (NostrRTCConnectSignal announce : announces) {
                if (announce == null || !announce.supportsRouting() || announce.isExpired(now)) continue;
                NostrRTCPeer peer = announce.getPeer();
                updated.put(presenceKey(peer.getPubkey().asHex(), scope.topologyAddress(peer.getSessionId())), peer);
                // Both addresses only select a presence; the codec still verifies its complete authenticated scope.
                updated.put(presenceKey(peer.getPubkey().asHex(), scope.legacyTopologyAddress(peer.getSessionId())), peer);
            }
            presences.clear();
            presences.putAll(updated);
            changed = store.removeExpired(now);
        }
        // Never call a listener under the state monitor: it can reentrantly close the control plane.
        if (changed) notifyChanged();
    }

    public synchronized void requestPublish(Collection<TopologyNeighbor> neighbors) {
        List<TopologyNeighbor> sorted = new ArrayList<TopologyNeighbor>(neighbors);
        sorted.sort(Comparator.comparing(TopologyNeighbor::getNodeId));
        boolean changed = !sorted.equals(currentNeighbors);
        currentNeighbors = Collections.unmodifiableList(sorted);
        boolean initialPublish = revision.get() == 0L;
        if (!started || closed || debounceTask != null || (!changed && !initialPublish)) return;
        debounceTask =
            executor.runLater(
                () -> {
                    publishDebouncedNow();
                    return null;
                },
                PUBLISH_DEBOUNCE_MS,
                TimeUnit.MILLISECONDS
            );
    }

    void publishDebouncedNow() {
        synchronized (this) {
            debounceTask = null;
            if (closed || !started) return;
        }
        publishNow(Instant.now())
            .catchException(error -> logger.log(Level.WARNING, "Failed to publish private topology snapshot", error));
    }

    /**
     * Requests the latest topology; pending callers share one bounded publication, not a snapshot queue.
     */
    AsyncTask<SignedNostrEvent> publishNow(Instant requestedAt) {
        Objects.requireNonNull(requestedAt, "Publication request time cannot be null");
        Publication requested;
        synchronized (this) {
            if (closed) return AsyncTask.failed(new IllegalStateException("Topology control plane is closed"));
            if (pendingPublication == null) pendingPublication = new Publication();
            requested = pendingPublication;
        }
        pumpPublication();
        return requested.result.task;
    }

    private void pumpPublication() {
        Publication next;
        synchronized (this) {
            if (closed || activePublication != null || pendingPublication == null) return;
            // NIP-01 replacement is ordered by seconds, then event ID. Never manufacture future seconds.
            Instant now = clock.get();
            long second = now.getEpochSecond();
            if (second <= lastCreatedAtSecond) {
                if (eligibilityTask == null) {
                    // Recheck at most a second later, including after a wall-clock rewind.
                    long delay = Math.max(1L, 1000L - now.getNano() / 1_000_000L);
                    final long generation = ++eligibilityGeneration;
                    eligibilityTask =
                        executor.runLater(
                            () -> {
                                synchronized (TopologyControlPlane.this) {
                                    if (closed || eligibilityGeneration != generation) return null;
                                    eligibilityTask = null;
                                }
                                pumpPublication();
                                return null;
                            },
                            delay,
                            TimeUnit.MILLISECONDS
                        );
                }
                return;
            }
            if (eligibilityTask != null) eligibilityTask.cancel();
            eligibilityGeneration++;
            eligibilityTask = null;
            Instant createdAt = Instant.ofEpochSecond(second);
            next = pendingPublication;
            next.snapshot =
                new TopologySnapshot(
                    scope,
                    localPeer.getPubkey(),
                    localPeer.getSessionId(),
                    revision.incrementAndGet(),
                    NodeId.derive(scope, localPeer.getPubkey(), localPeer.getSessionId()),
                    routingKeys.getPublicKey(),
                    createdAt,
                    createdAt.plus(expiration),
                    currentNeighbors
                );
            lastCreatedAtSecond = second;
            pendingPublication = null;
            activePublication = next;
        }
        final Publication publication = next;
        try {
            AsyncTask<SignedNostrEvent> encoded;
            synchronized (publicationHandoff) {
                if (!ownsPublication(publication)) return;
                encoded = codec.encode(publication.snapshot, localPeer, roomKeys, publication.snapshot.getIssuedAt());
            }
            // Platform continuations can run inline; attach them after releasing the handoff mutex.
            encoded
                .then(event -> {
                    publishEncoded(publication, event);
                    return null;
                })
                .catchException(error -> finishPublication(publication, null, error));
        } catch (Throwable error) {
            finishPublication(publication, null, error);
        }
    }

    private boolean ownsPublication(Publication publication) {
        return !closed && activePublication == publication;
    }

    private void publishEncoded(Publication publication, SignedNostrEvent event) {
        AsyncTask<List<AsyncTask<NostrMessageAck>>> published;
        boolean handedOff = false;
        boolean legacyRequested = false;
        try {
            synchronized (publicationHandoff) {
                if (!ownsPublication(publication)) return;
                handedOff = true;
                published = pool.publish(event);
                // A pool callback may reentrantly close this control plane.
                if (!ownsPublication(publication)) return;
                store.accept(publication.snapshot, clock.get());
            }
            requestLegacyPublication(publication.snapshot, event);
            legacyRequested = true;
            notifyChanged();
            published
                .then(promises -> {
                    if (!ownsPublication(publication)) return null;
                    for (AsyncTask<NostrMessageAck> promise : promises) {
                        promise
                            .then(ack -> {
                                if (!closed && ack.getStatus() == NostrMessageAck.Status.FAILURE) {
                                    logger.warning(
                                        "Relay rejected private topology snapshot " + event.getId() + ": " + ack.getMessage()
                                    );
                                }
                                return ack;
                            })
                            .catchException(error -> {
                                if (!closed) {
                                    logger.log(
                                        Level.WARNING,
                                        "Failed to publish private topology snapshot " + event.getId(),
                                        error
                                    );
                                }
                            });
                    }
                    // Preserve the pool's existing ANY-ACK policy. An unrelated relay may have a
                    // much longer acknowledgement timeout; its observers must not gate the next topology.
                    finishPublication(publication, event, null);
                    return null;
                })
                .catchException(error -> finishPublication(publication, null, error));
        } catch (Throwable error) {
            finishPublication(publication, null, error);
        } finally {
            if (handedOff && !legacyRequested) requestLegacyPublication(publication.snapshot, event);
        }
    }

    private void requestLegacyPublication(TopologySnapshot snapshot, SignedNostrEvent compact) {
        if (!scope.canPublishLegacyTopology(snapshot.getSessionId())) return;
        boolean schedule;
        synchronized (this) {
            if (closed) return;
            if (Long.compareUnsigned(snapshot.getRevision(), lastLegacyRequestedRevision) <= 0) return;
            lastLegacyRequestedRevision = snapshot.getRevision();
            pendingLegacyPublication = new LegacyPublication(snapshot, compact);
            schedule = activeLegacyPublication == null && !legacyPumpScheduled;
            if (schedule) legacyPumpScheduled = true;
        }
        if (schedule) scheduleLegacyPublication();
    }

    private void scheduleLegacyPublication() {
        try {
            legacyExecutor
                .run(() -> {
                    pumpLegacyPublication();
                    return null;
                })
                .catchException(error -> {
                    synchronized (this) {
                        legacyPumpScheduled = false;
                    }
                    if (!closed) logger.log(Level.FINE, "Legacy topology publication task failed", error);
                });
        } catch (Throwable error) {
            synchronized (this) {
                legacyPumpScheduled = false;
            }
            if (!closed) logger.log(Level.FINE, "Could not schedule legacy topology publication", error);
        }
    }

    private void pumpLegacyPublication() {
        final LegacyPublication publication;
        synchronized (this) {
            legacyPumpScheduled = false;
            if (closed || activeLegacyPublication != null || pendingLegacyPublication == null) return;
            publication = pendingLegacyPublication;
            pendingLegacyPublication = null;
            activeLegacyPublication = publication;
        }
        try {
            AsyncTask<SignedNostrEvent> encoded;
            synchronized (legacyHandoff) {
                if (!ownsLegacyPublication(publication)) return;
                if (publication.snapshot.isExpired(clock.get())) {
                    finishLegacyPublication(publication, null);
                    return;
                }
                encoded = codec.encodeLegacy(publication.compact, publication.snapshot, localPeer);
            }
            encoded
                .then(event -> {
                    publishLegacyEncoded(publication, event);
                    return null;
                })
                .catchException(error -> finishLegacyPublication(publication, error));
        } catch (Throwable error) {
            finishLegacyPublication(publication, error);
        }
    }

    private boolean ownsLegacyPublication(LegacyPublication publication) {
        return !closed && activeLegacyPublication == publication;
    }

    private void publishLegacyEncoded(LegacyPublication publication, SignedNostrEvent event) {
        try {
            AsyncTask<List<AsyncTask<NostrMessageAck>>> published;
            synchronized (legacyHandoff) {
                if (!ownsLegacyPublication(publication)) return;
                if (publication.snapshot.isExpired(clock.get())) {
                    finishLegacyPublication(publication, null);
                    return;
                }
                published = pool.publish(event);
            }
            published
                .then(promises -> {
                    // Legacy relay acknowledgements never own the canonical publication result or its next revision.
                    finishLegacyPublication(publication, null);
                    return null;
                })
                .catchException(error -> finishLegacyPublication(publication, error));
        } catch (Throwable error) {
            finishLegacyPublication(publication, error);
        }
    }

    private void finishLegacyPublication(LegacyPublication publication, Throwable error) {
        boolean schedule;
        synchronized (this) {
            if (activeLegacyPublication != publication) return;
            activeLegacyPublication = null;
            schedule = !closed && pendingLegacyPublication != null && !legacyPumpScheduled;
            if (schedule) legacyPumpScheduled = true;
        }
        if (!closed && error != null) logger.log(Level.FINE, "Legacy topology publication failed", error);
        if (schedule) scheduleLegacyPublication();
    }

    private void finishPublication(Publication publication, SignedNostrEvent event, Throwable error) {
        synchronized (this) {
            if (closed || activePublication != publication) return;
            activePublication = null;
        }
        publication.result.finish(event, error);
        pumpPublication();
    }

    private static final class Publication {

        final DeferredEvent result = new DeferredEvent();
        TopologySnapshot snapshot;
    }

    private static final class LegacyPublication {

        final TopologySnapshot snapshot;
        final SignedNostrEvent compact;

        LegacyPublication(TopologySnapshot snapshot, SignedNostrEvent compact) {
            this.snapshot = snapshot;
            this.compact = compact;
        }
    }

    /**
     * Completion can race the platform's asynchronous promise registration, including during close.
     */
    private static final class DeferredEvent {

        private Consumer<SignedNostrEvent> resolve;
        private Consumer<Throwable> reject;
        private boolean done;
        private SignedNostrEvent value;
        private Throwable failure;
        final AsyncTask<SignedNostrEvent> task = AsyncTask.create(this::bind);

        private void bind(Consumer<SignedNostrEvent> yes, Consumer<Throwable> no) {
            SignedNostrEvent completed;
            Throwable error;
            synchronized (this) {
                if (!done) {
                    resolve = yes;
                    reject = no;
                    return;
                }
                completed = value;
                error = failure;
            }
            if (error == null) yes.accept(completed); else no.accept(error);
        }

        void finish(SignedNostrEvent completed, Throwable error) {
            Consumer<SignedNostrEvent> yes;
            Consumer<Throwable> no;
            synchronized (this) {
                if (done) return;
                done = true;
                value = completed;
                failure = error;
                yes = resolve;
                no = reject;
                resolve = null;
                reject = null;
            }
            if (yes != null) {
                if (error == null) yes.accept(completed); else no.accept(error);
            }
        }
    }

    void acceptEvent(SignedNostrEvent event, Instant now) {
        NostrRTCPeer presence;
        synchronized (this) {
            if (closed) return;
            String address = event.getFirstTagFirstValue("d");
            presence = presences.get(presenceKey(event.getPubkey().asHex(), address));
        }
        if (presence == null) {
            logger.fine(
                "Ignoring private topology event without matching dc4 presence: author=" +
                event.getPubkey().asHex() +
                " d=" +
                event.getFirstTagFirstValue("d")
            );
            return;
        }
        try {
            codec
                .decode(event, scope, presence, roomKeys, now)
                .then(snapshot -> {
                    boolean changed;
                    synchronized (TopologyControlPlane.this) {
                        changed = !closed && store.accept(snapshot, now);
                    }
                    if (changed) notifyChanged();
                    return null;
                })
                .catchException(error -> logger.log(Level.FINE, "Rejected private topology event", error));
        } catch (RuntimeException error) {
            logger.log(Level.FINE, "Rejected private topology event", error);
        }
    }

    public Collection<TopologySnapshot> getSnapshots(Instant now) {
        return store.snapshots(now);
    }

    public NostrKeyPair getRoutingKeyPair() {
        return routingKeys;
    }

    private void scheduleRefresh() {
        if (closed || !started) return;
        refreshTask =
            executor.runLater(
                () -> {
                    if (!closed && started) {
                        publishNow(Instant.now())
                            .catchException(error ->
                                logger.log(Level.WARNING, "Failed to refresh private topology snapshot", error)
                            );
                        scheduleRefresh();
                    }
                    return null;
                },
                refreshInterval.toMillis(),
                TimeUnit.MILLISECONDS
            );
    }

    private void notifyChanged() {
        if (closed) return;
        Listener current = listener;
        if (current != null) {
            try {
                current.onTopologyChanged();
            } catch (Throwable error) {
                logger.log(Level.WARNING, "Topology listener failed", error);
            }
        }
    }

    private static String presenceKey(String author, String address) {
        return author + '\u0000' + address;
    }

    @Override
    public void close() {
        Publication active;
        Publication pending;
        NostrSubscription closingSubscription;
        synchronized (publicationHandoff) {
            synchronized (this) {
                if (closed) return;
                closed = true;
                started = false;
                if (debounceTask != null) debounceTask.cancel();
                if (refreshTask != null) refreshTask.cancel();
                if (eligibilityTask != null) eligibilityTask.cancel();
                debounceTask = null;
                refreshTask = null;
                eligibilityTask = null;
                eligibilityGeneration++;
                active = activePublication;
                pending = pendingPublication;
                activePublication = null;
                pendingPublication = null;
                closingSubscription = subscription;
                subscription = null;
                presences.clear();
                currentNeighbors = Collections.emptyList();
                store.clear();
                listener = null;
            }
        }
        CancellationException error = new CancellationException("Topology control plane is closed");
        if (active != null) active.result.finish(null, error);
        if (pending != null) pending.result.finish(null, error);
        synchronized (legacyHandoff) {
            synchronized (this) {
                activeLegacyPublication = null;
                pendingLegacyPublication = null;
                legacyPumpScheduled = false;
            }
        }
        try {
            if (closingSubscription != null) closingSubscription.close();
        } finally {
            try {
                executor.close();
            } finally {
                legacyExecutor.close();
            }
        }
        // The shared signer and pool retain their own physical lifecycle; late completions are fenced.
    }
}
