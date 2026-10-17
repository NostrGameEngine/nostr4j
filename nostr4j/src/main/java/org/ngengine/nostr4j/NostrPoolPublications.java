package org.ngengine.nostr4j;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.ngengine.nostr4j.event.SignedNostrEvent;
import org.ngengine.nostr4j.proto.NostrMessageAck;
import org.ngengine.platform.AsyncExecutor;
import org.ngengine.platform.AsyncTask;
import org.ngengine.platform.NGEPlatform;

/**
 * Bounded, best-effort fan-out. It never participates in the caller's ACK policy.
 */
final class NostrPoolPublications {

    private static final class Delivery {

        private final SignedNostrEvent event;
        private final NostrRelay relay;

        Delivery(SignedNostrEvent event, NostrRelay relay) {
            this.event = event;
            this.relay = relay;
        }

        SignedNostrEvent event() {
            return event;
        }

        NostrRelay relay() {
            return relay;
        }

        String key() {
            return event.getId() + ":" + relay.getUrl();
        }
    }

    private final NostrPool pool;
    private final ArrayDeque<Delivery> queue = new ArrayDeque<>();
    private final Set<String> pending = new HashSet<>();
    private AsyncExecutor executor;
    private AsyncTask<?> timer;
    private AutoCloseable lease;
    private Delivery active;
    private boolean enabled, busy;
    private long generation;
    private int capacity = 1024;
    private long tickMillis = 100;

    NostrPoolPublications(NostrPool pool) {
        this.pool = pool;
    }

    synchronized boolean isEnabled() {
        return enabled;
    }

    synchronized int size() {
        return pending.size();
    }

    synchronized void setEnabled(boolean value) {
        enabled = value;
        if (!value) clear();
    }

    synchronized void setInterval(Duration value) {
        if (value == null || value.toMillis() < 10 || value.toMillis() > 60_000) throw new IllegalArgumentException(
            "Publication interval must be between 10 ms and one minute"
        );
        tickMillis = value.toMillis();
    }

    synchronized void setCapacity(int value) {
        if (value < 1 || value > 100_000) throw new IllegalArgumentException("Invalid publication queue capacity");
        capacity = value;
        while (queue.size() > capacity) pending.remove(queue.removeFirst().key());
    }

    synchronized void enqueue(SignedNostrEvent event, List<NostrRelay> targets) {
        if (!enabled || event == null || event.isExpired()) return;
        int kind = event.getKind();
        if (kind == 4 || kind == 13 || kind == 14 || kind == 1059 || kind >= 20_000 && kind < 30_000) return;
        for (NostrRelay relay : targets) {
            Delivery delivery = new Delivery(event, relay);
            if (pending.contains(delivery.key())) continue;
            // Discard the oldest unsent delivery rather than retaining unbounded event bodies.
            while (pending.size() >= capacity && !queue.isEmpty()) pending.remove(queue.removeFirst().key());
            if (pending.size() >= capacity) break;
            pending.add(delivery.key());
            queue.addLast(delivery);
        }
        schedule();
    }

    private synchronized void schedule() {
        if (!enabled || busy || timer != null || queue.isEmpty()) return;
        if (executor == null) executor = NGEPlatform.get().newAsyncExecutor(NostrPoolPublications.class);
        long version = generation;
        timer =
            executor.runLater(
                () -> {
                    drain(version);
                    return null;
                },
                tickMillis,
                TimeUnit.MILLISECONDS
            );
    }

    private void drain(long version) {
        Delivery delivery;
        synchronized (this) {
            if (version != generation || !enabled) return;
            timer = null;
            do {
                delivery = queue.pollFirst();
                if (delivery == null) return;
                if (!delivery.event().isExpired() && pool.getRelays().contains(delivery.relay())) break;
                pending.remove(delivery.key());
            } while (true);
            busy = true;
            active = delivery;
            lease = pool.retainConnections(List.of(delivery.relay().getUrl()));
        }
        Delivery selected = delivery;
        // Time-bound the lease as well as connection establishment; dead relays cannot stop the queue.
        AsyncTask<?> deadline = executor.runLater(
            () -> {
                finish(selected, version);
                return null;
            },
            10,
            TimeUnit.SECONDS
        );
        selected
            .relay()
            .connect()
            .compose(connected -> {
                synchronized (this) {
                    if (
                        version != generation ||
                        active != selected ||
                        selected.event().isExpired() ||
                        !pool.getRelays().contains(connected)
                    ) return AsyncTask.completed(null);
                }
                connected.beforeSendMessage(selected.event());
                AsyncTask<NostrMessageAck> sent = connected.sendMessage(selected.event());
                connected.afterSendMessage(selected.event());
                return sent;
            })
            .then(ack -> {
                deadline.cancel();
                finish(selected, version);
                return null;
            })
            .catchException(error -> {
                deadline.cancel();
                finish(selected, version);
            });
    }

    private synchronized void finish(Delivery delivery, long version) {
        if (version != generation || active != delivery) return;
        pending.remove(delivery.key());
        active = null;
        if (lease != null) try {
            lease.close();
        } catch (Exception ignored) {}
        lease = null;
        busy = false;
        schedule();
    }

    synchronized void clear() {
        generation++;
        queue.clear();
        pending.clear();
        busy = false;
        active = null;
        if (timer != null) timer.cancel();
        timer = null;
        if (lease != null) try {
            lease.close();
        } catch (Exception ignored) {}
        lease = null;
        if (executor != null) executor.close();
        executor = null;
    }
}
