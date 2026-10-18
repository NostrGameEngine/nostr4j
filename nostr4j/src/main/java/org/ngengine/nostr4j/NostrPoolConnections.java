package org.ngengine.nostr4j;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.ngengine.nostr4j.store.NostrCacheRelay;
import org.ngengine.platform.AsyncExecutor;
import org.ngengine.platform.AsyncTask;
import org.ngengine.platform.NGEUtils;

/**
 * Maintains pool-owned connection selections and per-read relay leases.
 * Selection updates are serialized so budgets, leases and retry deadlines stay consistent.
 * The selected set is published as an immutable snapshot for reads that do not take
 * the selection monitor. Transport reconciliation runs asynchronously.
 */
final class NostrPoolConnections {
    private final Supplier<List<NostrRelay>> catalog;
    private final Map<String, Set<NostrRelay>> leases = new LinkedHashMap<>();
    private final Map<NostrRelay, AsyncTask<NostrRelay>> connecting = new LinkedHashMap<>();
    private final Map<NostrRelay, Long> retryAt = new LinkedHashMap<>();
    private volatile Set<NostrRelay> selected = Set.of();
    private volatile int budget;
    private AsyncExecutor executor;
    private AsyncTask<?> maintenance;
    private long maintenanceGeneration;
    private boolean scheduled;
    private List<NostrRelay> pending = List.of();

    NostrPoolConnections(Supplier<List<NostrRelay>> catalog) { this.catalog = catalog; }

    int getBudget() { return budget; }

    synchronized void setBudget(int value) {
        if (value < 0) throw new IllegalArgumentException("Connection budget cannot be negative; zero means unlimited");
        budget = value;
        if(value==0 && maintenance!=null) {maintenanceGeneration++;maintenance.cancel();maintenance=null;}
        refresh();
    }

    synchronized void acquire(String owner, Collection<NostrRelay> relays) {
        leases.put(owner, new LinkedHashSet<>(relays));
        refresh();
    }

    synchronized void release(String owner) {
        if (leases.remove(owner) != null) refresh();
    }

    synchronized void remove(NostrRelay relay) {
        for (Set<NostrRelay> lease : leases.values()) lease.remove(relay);
        connecting.remove(relay); retryAt.remove(relay);
        if(budget>0 || !leases.isEmpty()) refresh();
    }

    boolean wanted(NostrRelay relay) {
        return relay instanceof NostrCacheRelay || budget == 0 || selected.contains(relay);
    }

    /**
     * Recomputes the immutable selected-relay snapshot and schedules reconciliation.
     * Required leases are selected before automatic candidates; the remaining slots honor
     * connection priority, incumbent preference and retry backoff. The monitor protects
     * this compound selection; transport connections are reconciled on the executor.
     */
    synchronized void refresh() {
        List<NostrRelay> all = new ArrayList<>(catalog.get());
        Set<NostrRelay> next = new LinkedHashSet<>();
        for (Set<NostrRelay> lease : leases.values()) next.addAll(lease);
        next.retainAll(all);
        next.removeIf(relay -> relay instanceof NostrCacheRelay);
        long now = System.currentTimeMillis();
        List<NostrRelay> automatic = new ArrayList<>();
        for (NostrRelay relay : all) {
            if (relay instanceof NostrCacheRelay) continue;
            if (!next.contains(relay) && (budget == 0 || retryAt.getOrDefault(relay, 0L) <= now)) automatic.add(relay);
        }
        automatic.sort(Comparator.comparingInt(NostrRelay::getConnectionPriority).reversed()
                .thenComparing(relay -> !selected.contains(relay)));
        int available = budget == 0 ? automatic.size() : Math.max(0, budget - next.size());
        next.addAll(automatic.subList(0, Math.min(available, automatic.size())));
        selected = Set.copyOf(next);
        pending = List.copyOf(all);
        if (executor == null) executor = NGEUtils.getPlatform().newAsyncExecutor(NostrPoolConnections.class);
        if (!scheduled) {
            scheduled = true;
            executor.run(() -> { reconcile(); return null; });
        }
        if (budget > 0 && maintenance == null) {
            long generation=++maintenanceGeneration;
            maintenance = executor.runLater(() -> {
                synchronized (NostrPoolConnections.this) {
                    if(generation!=maintenanceGeneration) return null;
                    maintenance = null;
                    if (budget > 0) refresh();
                }
                return null;
            }, 1, TimeUnit.SECONDS);
        }
    }

    private void reconcile() {
        List<NostrRelay> all;
        synchronized (this) { scheduled = false; all = pending; }
        for (NostrRelay relay : all) {
            if (relay instanceof NostrCacheRelay) continue;
            boolean open;
            synchronized (this) {
                if (!catalog.get().contains(relay)) continue;
                open = wanted(relay);
                boolean queued=connecting.containsKey(relay) && relay.getStatus()!=NostrRelay.Status.INITIALIZE_CONNECTION
                        && relay.getStatus()!=NostrRelay.Status.DISCONNECTED;
                if (open && (relay.isConnected() || queued || retryAt.getOrDefault(relay, 0L) > System.currentTimeMillis())) continue;
                if (open) connecting.put(relay, null);
            }
            if (!open) {
                if (relay.getStatus() != NostrRelay.Status.NEW && relay.getStatus() != NostrRelay.Status.DISCONNECTED)
                    relay.disconnect("Pool connection budget", false);
                continue;
            }
            try {
                AsyncTask<NostrRelay> attempt = relay.connect();
                attempt.then(ready -> {
                    synchronized (this) { connecting.remove(relay); retryAt.remove(relay); }
                    if (!wanted(relay)) relay.disconnect("Pool connection budget", false);
                    return null;
                }).catchException(error -> {
                    synchronized (this) { connecting.remove(relay); retryAt.put(relay, System.currentTimeMillis() + 3000); }
                });
            } catch (Exception error) {
                synchronized (this) { connecting.remove(relay); retryAt.put(relay, System.currentTimeMillis() + 3000); }
            }
        }
    }

    synchronized void reset() {
        leases.clear(); selected = Set.of(); connecting.clear(); retryAt.clear(); scheduled = false;
        pending=List.of();maintenanceGeneration++;
        if (maintenance != null) maintenance.cancel(); maintenance = null;
        if (executor != null) executor.close(); executor = null;
    }
}
