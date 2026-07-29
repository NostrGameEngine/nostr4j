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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.ngengine.nostr4j.NostrFilter;
import org.ngengine.nostr4j.NostrPool;
import org.ngengine.nostr4j.NostrSubscription;
import org.ngengine.nostr4j.event.SignedNostrEvent;
import org.ngengine.nostr4j.keypair.NostrKeyPair;
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
    private volatile long lastCreatedAtSecond;
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
    }

    public synchronized void setListener(Listener listener) {
        this.listener = listener;
    }

    public synchronized void start() {
        if (closed) throw new IllegalStateException("Topology control plane is closed");
        if (started) return;
        started = true;
        NostrFilter filter = new NostrFilter()
            .withKind(RoutingProtocol.TOPOLOGY_EVENT_KIND)
            .withTag("t", RoutingProtocol.TOPOLOGY_EVENT_TYPE)
            .withTag("version", RoutingProtocol.VERSION)
            .withTag("P", scope.getRoomPubkey().asHex())
            .withTag("i", scope.getProtocolId())
            .withTag("y", scope.getApplicationId())
            .since(Instant.now().minus(expiration).truncatedTo(ChronoUnit.SECONDS))
            .limit(RoutingLimits.MAX_TOPOLOGY_SNAPSHOTS);
        subscription = pool.subscribe(filter);
        subscription.addEventListener((sub, event, stored) -> acceptEvent(event, Instant.now()));
        subscription.open();
        requestPublish(currentNeighbors);
        scheduleRefresh();
    }

    public synchronized void updatePresences(Collection<NostrRTCConnectSignal> announces, Instant now) {
        Map<String, NostrRTCPeer> updated = new HashMap<String, NostrRTCPeer>();
        for (NostrRTCConnectSignal announce : announces) {
            if (announce == null || !announce.supportsRouting() || announce.isExpired(now)) continue;
            NostrRTCPeer peer = announce.getPeer();
            updated.put(presenceKey(peer.getPubkey().asHex(), scope.topologyAddress(peer.getSessionId())), peer);
        }
        presences.clear();
        presences.putAll(updated);
        if (store.removeExpired(now)) notifyChanged();
    }

    public synchronized void requestPublish(Collection<TopologyNeighbor> neighbors) {
        List<TopologyNeighbor> sorted = new ArrayList<TopologyNeighbor>(neighbors);
        sorted.sort(Comparator.comparing(TopologyNeighbor::getNodeId));
        currentNeighbors = Collections.unmodifiableList(sorted);
        if (!started || closed || debounceTask != null) return;
        debounceTask =
            executor.runLater(
                () -> {
                    synchronized (TopologyControlPlane.this) {
                        debounceTask = null;
                    }
                    publishNow(Instant.now())
                        .catchException(error -> logger.log(Level.WARNING, "Failed to publish private topology snapshot", error)
                        );
                    return null;
                },
                PUBLISH_DEBOUNCE_MS,
                TimeUnit.MILLISECONDS
            );
    }

    AsyncTask<SignedNostrEvent> publishNow(Instant requestedAt) {
        final TopologySnapshot snapshot;
        final Instant createdAt;
        synchronized (this) {
            if (closed) return AsyncTask.failed(new IllegalStateException("Topology control plane is closed"));
            long requestedSecond = requestedAt.getEpochSecond();
            long nextSecond = Math.max(requestedSecond, lastCreatedAtSecond + 1L);
            lastCreatedAtSecond = nextSecond;
            createdAt = Instant.ofEpochSecond(nextSecond);
            long nextRevision = revision.incrementAndGet();
            snapshot =
                new TopologySnapshot(
                    scope,
                    localPeer.getPubkey(),
                    localPeer.getSessionId(),
                    nextRevision,
                    NodeId.derive(scope, localPeer.getPubkey(), localPeer.getSessionId()),
                    routingKeys.getPublicKey(),
                    createdAt,
                    createdAt.plus(expiration),
                    currentNeighbors
                );
        }
        return codec
            .encode(snapshot, localPeer, roomKeys, createdAt)
            .then(event -> {
                store.accept(snapshot, Instant.now());
                pool.publish(event);
                notifyChanged();
                return event;
            });
    }

    void acceptEvent(SignedNostrEvent event, Instant now) {
        NostrRTCPeer presence;
        synchronized (this) {
            String address = event.getFirstTagFirstValue("d");
            presence = presences.get(presenceKey(event.getPubkey().asHex(), address));
        }
        if (presence == null) {
            return;
        }
        try {
            codec
                .decode(event, scope, presence, roomKeys, now)
                .then(snapshot -> {
                    if (store.accept(snapshot, now)) notifyChanged();
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
    public synchronized void close() {
        if (closed) return;
        closed = true;
        started = false;
        if (debounceTask != null) debounceTask.cancel();
        if (refreshTask != null) refreshTask.cancel();
        debounceTask = null;
        refreshTask = null;
        if (subscription != null) subscription.close();
        subscription = null;
        presences.clear();
        currentNeighbors = Collections.emptyList();
        store.clear();
        listener = null;
        executor.close();
    }
}
