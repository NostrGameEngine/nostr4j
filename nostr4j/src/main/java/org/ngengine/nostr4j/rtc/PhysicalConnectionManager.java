/**
 * BSD 3-Clause License
 *
 * Copyright (c) 2025, Riccardo Balbo
 */
package org.ngengine.nostr4j.rtc;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.DoubleSupplier;
import java.util.function.LongSupplier;
import java.util.logging.Logger;
import org.ngengine.nostr4j.RTCSettings;
import org.ngengine.nostr4j.keypair.NostrPublicKey;
import org.ngengine.nostr4j.rtc.signal.NostrRTCPeer;
import org.ngengine.nostr4j.utils.ExponentialBackoff;
import org.ngengine.platform.NGEUtils;

/**
 * State only. Transport calls and application callbacks never run under this monitor.
 */
final class PhysicalConnectionManager {

    static final int MAX_CANDIDATES = 2048;

    enum State {
        DISCOVERED,
        CONNECTING,
        PROBING_SWAP,
        ESTABLISHED,
        BACKOFF,
        EXCLUDED,
        CLOSING,
    }

    enum Phase {
        CONNECTING,
        ESTABLISHED,
    }

    static final class Attempt {

        final NostrRTCPeer peer;
        final long generation;
        final boolean outgoing;
        final boolean probe;
        final NostrRTCPeer victim;
        Attempt victimAttempt;
        final long startedAt;
        volatile Phase phase = Phase.CONNECTING;
        volatile boolean turnOnly;
        volatile boolean peerRouteReceived;
        boolean offerReceived;
        boolean answerReceived;
        volatile String remoteDescription;
        volatile org.ngengine.nostr4j.rtc.signal.NostrRTCSignal localDescription;
        volatile org.ngengine.nostr4j.rtc.signal.NostrRTCRouteSignal localRoute;
        long lastControlAt = Long.MIN_VALUE;

        Attempt(
            NostrRTCPeer peer,
            long generation,
            boolean outgoing,
            boolean probe,
            NostrRTCPeer victim,
            Attempt victimAttempt,
            long now
        ) {
            this.peer = peer;
            this.generation = generation;
            this.outgoing = outgoing;
            this.probe = probe;
            this.victim = victim;
            this.victimAttempt = victimAttempt;
            this.startedAt = now;
        }
    }

    static final class Candidate {

        final NostrRTCPeer peer;
        final ExponentialBackoff backoff;
        Float priority;
        int role = 2;
        boolean protectedLink;
        boolean present = true;
        boolean physicalReady;
        boolean replacementReady;
        boolean routedReady;
        String transport = "NONE";
        State state = State.DISCOVERED;
        Attempt attempt;
        long establishedAt;
        long degradedAt = Long.MIN_VALUE;
        long generation;
        int failures;
        String outcome = "discovered";

        Candidate(NostrRTCPeer peer, RTCSettings settings) {
            this.peer = peer;
            backoff =
                new ExponentialBackoff(
                    settings.getConnectionRetryInitialDelay(),
                    settings.getConnectionRetryMaxDelay(),
                    settings.getConnectionMinimumLifetime(),
                    settings.getConnectionRetryMultiplier()
                );
        }
    }

    static final class LinkState {

        final boolean ready;
        final boolean routed;
        final String transport;
        final boolean fallbackAvailable;
        final boolean replacementReady;

        LinkState(boolean ready, boolean routed, String transport) {
            this(ready, routed, transport, false);
        }

        LinkState(boolean ready, boolean routed, String transport, boolean fallbackAvailable) {
            this(ready, routed, transport, fallbackAvailable, ready && "RTC".equals(transport));
        }

        LinkState(boolean ready, boolean routed, String transport, boolean fallbackAvailable, boolean replacementReady) {
            this.replacementReady = replacementReady;
            this.ready = ready;
            this.routed = routed;
            this.transport = transport;
            this.fallbackAvailable = fallbackAvailable;
        }
    }

    private static final Logger logger = Logger.getLogger(PhysicalConnectionManager.class.getName());
    private final RTCSettings settings;
    private final NostrPublicKey localKey;
    private final LongSupplier clock;
    private final DoubleSupplier random;
    private final Map<NostrRTCPeer, Candidate> candidates = new LinkedHashMap<>();
    private final Map<NostrPublicKey, Long> identityRetry = new LinkedHashMap<>();
    private final Set<NostrRTCPeer> served = new HashSet<>();
    private final List<Attempt> closures = new ArrayList<>();
    private DiscoveryPriority priority;
    private long policyEpoch;
    private long evaluatedEpoch = -1L;
    private long nextBatchAt = Long.MIN_VALUE;
    private long rateWindowAt = Long.MIN_VALUE;
    private int startsInWindow;
    private long lastPolicyWarning = Long.MIN_VALUE;
    private boolean closed;
    private long responseWindowAt = Long.MIN_VALUE;
    private int responsesInWindow;

    PhysicalConnectionManager(RTCSettings settings, NostrPublicKey localKey, LongSupplier clock, DoubleSupplier random) {
        this.settings = settings;
        this.localKey = localKey;
        this.clock = clock;
        this.random = random;
    }

    synchronized boolean hasPriority() {
        return priority != null;
    }

    synchronized void setPriority(DiscoveryPriority value) {
        priority = value;
        ++policyEpoch;
    }

    /**
     * Snapshot, evaluate outside the monitor, then apply only to the same epoch.
     */
    boolean evaluate(List<NostrRTCPeer> peers) {
        DiscoveryPriority callback;
        long epoch;
        synchronized (this) {
            callback = priority;
            epoch = policyEpoch;
        }
        Map<NostrRTCPeer, Float> values = new LinkedHashMap<>();
        boolean invalid = false;
        for (NostrRTCPeer peer : peers) {
            try {
                float value = callback == null ? 0f : callback.priorityOf(peer);
                if (!Float.isFinite(value)) {
                    invalid = true;
                    continue;
                }
                values.put(peer, value);
            } catch (Throwable error) {
                invalid = true;
            }
        }
        boolean warn = false;
        synchronized (this) {
            if (closed || epoch != policyEpoch) return false;
            Set<NostrRTCPeer> present = new HashSet<>(peers);
            for (Candidate candidate : candidates.values()) candidate.present = present.contains(candidate.peer);
            for (NostrRTCPeer peer : peers) {
                Candidate candidate = candidates.get(peer);
                if (candidate == null && candidates.size() < MAX_CANDIDATES) {
                    candidate = new Candidate(peer, settings);
                    candidates.put(peer, candidate);
                }
                if (candidate == null) continue;
                candidate.present = true;
                if (values.containsKey(peer)) candidate.priority = values.get(peer);
                if (candidate.attempt == null) {
                    candidate.state =
                        candidate.priority != null && candidate.priority < 0f
                            ? State.EXCLUDED
                            : delay(candidate) > 0 ? State.BACKOFF : State.DISCOVERED;
                }
            }
            evaluatedEpoch = epoch;
            long now = clock.getAsLong();
            if (invalid && (lastPolicyWarning == Long.MIN_VALUE || now - lastPolicyWarning >= 10_000L)) {
                lastPolicyWarning = now;
                warn = true;
            }
        }
        if (warn) logger.warning("Invalid discovery priority; retaining last valid values for affected peers");
        return true;
    }

    synchronized void reconcile(
        Map<NostrRTCPeer, LinkState> links,
        Map<NostrRTCPeer, Integer> roles,
        Set<NostrRTCPeer> protectedPeers
    ) {
        long now = clock.getAsLong();
        for (Candidate c : candidates.values()) {
            c.role = roles.getOrDefault(c.peer, priority == null ? 3 : 2);
            c.protectedLink = protectedPeers.contains(c.peer);
            LinkState link = links.get(c.peer);
            c.physicalReady = link != null && link.ready;
            c.replacementReady = link != null && link.replacementReady;
            c.routedReady = link != null && link.routed;
            c.transport = link == null ? "NONE" : link.transport;
            Attempt a = c.attempt;
            if (a == null || c.state == State.CLOSING) continue;
            if (!c.present || (c.priority != null && c.priority < 0f)) {
                retire(c, !c.present ? "membership-removed" : "policy-excluded", false);
            } else if (c.state == State.ESTABLISHED) {
                // Leave working TURN in place. A routed circuit never counts as a physical link.
                if (!c.physicalReady) {
                    if (c.degradedAt == Long.MIN_VALUE) c.degradedAt = now;
                    if (now - c.degradedAt >= settings.getP2pAttemptTimeout().toMillis()) retire(
                        c,
                        "physical-transport-lost",
                        true
                    );
                } else {
                    c.degradedAt = Long.MIN_VALUE;
                    c.backoff.getDelay(instant(now));
                }
            } else if (
                now -
                a.startedAt >=
                settings.getP2pGiveupTimeout().toMillis() +
                (link != null && link.fallbackAvailable && !a.turnOnly ? settings.getP2pAttemptTimeout().toMillis() : 0L)
            ) {
                retire(c, "attempt-timeout", true);
            }
        }
        candidates.entrySet().removeIf(e -> !e.getValue().present && e.getValue().attempt == null);
        served.retainAll(candidates.keySet());
    }

    synchronized List<Attempt> fill() {
        List<Attempt> result = new ArrayList<>();
        long now = clock.getAsLong();
        if (closed || evaluatedEpoch != policyEpoch || now < nextBatchAt) return result;
        nextBatchAt = now + Math.max(250L, settings.getRoomLoopInterval().toMillis());
        List<Candidate> eligible = eligible();
        if (eligible.stream().allMatch(c -> served.contains(c.peer))) served.clear();
        eligible.sort(order());
        int limit = Math.min(settings.getMaxConcurrentConnectionAttempts(), settings.getMaxDirectPeers());
        for (Candidate c : eligible) {
            if (result.size() >= limit || pendingOrdinary() >= limit || !rateAvailable(now)) break;
            if (served.contains(c.peer)) continue;
            Attempt a = reserve(c, true, now);
            served.add(c.peer);
            if (a != null) result.add(a);
        }
        return result;
    }

    synchronized Attempt admit(NostrRTCPeer peer) {
        Candidate c = candidates.get(peer);
        long now = clock.getAsLong();
        if (closed || evaluatedEpoch != policyEpoch || c == null || !eligible(c) || !rateAvailable(now)) return null;
        return reserve(c, false, now);
    }

    /**
     * Transfer a simultaneous outgoing reservation to the winning offer without freeing its slot.
     */
    synchronized Attempt yieldToOffer(Attempt a) {
        if (!active(a) || !a.outgoing || a.answerReceived || a.phase != Phase.CONNECTING) return null;
        Candidate c = candidates.get(a.peer);
        Attempt incoming = new Attempt(a.peer, ++c.generation, false, a.probe, a.victim, a.victimAttempt, a.startedAt);
        c.attempt = incoming;
        c.outcome = "offer-collision-yielded";
        return incoming;
    }

    private Attempt reserve(Candidate c, boolean outgoing, long now) {
        if (resources() >= settings.getMaxDirectPeers() + 1) return null;
        int ordinary = ordinaryResources();
        Candidate victim = null;
        if (ordinary < settings.getMaxDirectPeers() && hasProbe()) return null;
        boolean probe = ordinary >= settings.getMaxDirectPeers();
        if (probe) {
            if (hasProbe() || established() < settings.getMaxDirectPeers()) return null;
            victim = victimFor(c, now);
            if (victim == null) return null;
        } else if (pendingOrdinary() >= Math.min(settings.getMaxConcurrentConnectionAttempts(), settings.getMaxDirectPeers())) {
            return null;
        }
        Attempt a = new Attempt(
            c.peer,
            ++c.generation,
            outgoing,
            probe,
            victim == null ? null : victim.peer,
            victim == null ? null : victim.attempt,
            now
        );
        c.attempt = a;
        c.state = probe ? State.PROBING_SWAP : State.CONNECTING;
        c.outcome = probe ? "probe-reserved" : "reserved";
        startsInWindow++;
        return a;
    }

    synchronized boolean responseAllowed() {
        long now = clock.getAsLong();
        if (closed) return false;
        if (responseWindowAt == Long.MIN_VALUE || now - responseWindowAt >= 1000L) {
            responseWindowAt = now;
            responsesInWindow = 0;
        }
        return responsesInWindow++ < Math.max(16, settings.getMaxConcurrentConnectionAttempts() * 8);
    }

    synchronized boolean claimDescription(Attempt a, boolean offer) {
        if (!active(a)) return false;
        if (offer) {
            if (a.offerReceived) return false;
            a.offerReceived = true;
        } else {
            if (a.answerReceived) return false;
            a.answerReceived = true;
        }
        return true;
    }

    synchronized boolean controlDue(Attempt a) {
        long now = clock.getAsLong();
        if (!active(a) || (a.lastControlAt != Long.MIN_VALUE && now - a.lastControlAt < 250L)) return false;
        a.lastControlAt = now;
        return true;
    }

    synchronized boolean commit(Attempt a) {
        if (!active(a) || closed || evaluatedEpoch != policyEpoch) return false;
        Candidate c = candidates.get(a.peer);
        if (c.priority == null || c.priority < 0f || !c.physicalReady) return false;
        if (a.phase == Phase.ESTABLISHED) return true;
        if (ordinaryResourcesExcluding(a) >= settings.getMaxDirectPeers()) {
            Candidate victim = replacementVictim(a);
            if (victim == null) {
                retire(c, "probe-obsolete", false);
                return false;
            }
            if (!c.replacementReady && victim.physicalReady) return false;
            retire(victim, "swap-committed", false);
        }
        establish(c, clock.getAsLong());
        return true;
    }

    private Candidate replacementVictim(Attempt a) {
        Candidate candidate = candidates.get(a.peer);
        Candidate captured = candidates.get(a.victim);
        long now = clock.getAsLong();
        if (captured == null || captured.attempt != a.victimAttempt || !replaceable(captured, candidate, now)) return null;
        if (!captured.physicalReady) return captured;
        Candidate best = victimFor(candidate, now);
        // A newly eligible equal-priority neighbor must not invalidate a still-safe captured victim.
        return best != null && captured.priority <= best.priority ? captured : null;
    }

    private boolean replaceable(Candidate victim, Candidate candidate, long now) {
        return (
            victim.state == State.ESTABLISHED &&
            !victim.peer.equals(candidate.peer) &&
            !victim.protectedLink &&
            victim.priority != null &&
            (candidate.role < 2 || now - victim.establishedAt >= settings.getConnectionMinimumLifetime().toMillis()) &&
            (
                (candidate.role < 2 && candidate.role < victim.role) ||
                (priority != null && candidate.priority != null && candidate.priority > victim.priority)
            )
        );
    }

    private void establish(Candidate c, long now) {
        c.state = State.ESTABLISHED;
        c.attempt.phase = Phase.ESTABLISHED;
        c.establishedAt = now;
        c.degradedAt = Long.MIN_VALUE;
        c.attempt.victimAttempt = null;
        c.backoff.registerSuccess(instant(now));
        c.outcome = "established";
    }

    private Candidate victimFor(Candidate candidate, long now) {
        return candidates
            .values()
            .stream()
            .filter(v -> v.physicalReady && replaceable(v, candidate, now))
            .min(Comparator.comparing((Candidate v) -> v.priority).thenComparing(v -> stableRank(v.peer)))
            .orElse(null);
    }

    synchronized void fail(Attempt a, String reason) {
        if (active(a)) retire(candidates.get(a.peer), reason, true);
    }

    synchronized void abort(Attempt a, String reason) {
        if (active(a)) retire(candidates.get(a.peer), reason, false);
    }

    private void retire(Candidate c, String reason, boolean failed) {
        if (c.attempt == null || c.state == State.CLOSING) return;
        if (failed) {
            c.backoff.registerAttempt(instant(clock.getAsLong()), settings.getConnectionRetryJitter(), random.getAsDouble());
            c.failures++;
            identityRetry.put(c.peer.getPubkey(), clock.getAsLong() + delay(c));
            while (identityRetry.size() > MAX_CANDIDATES) identityRetry.remove(identityRetry.keySet().iterator().next());
        }
        c.state = State.CLOSING;
        c.outcome = reason;
        closures.add(c.attempt);
    }

    synchronized List<Attempt> takeClosures() {
        List<Attempt> result = new ArrayList<>(closures);
        closures.clear();
        return result;
    }

    /**
     * Capacity remains charged until the transport owner has completed cleanup.
     */
    synchronized void released(Attempt a) {
        Candidate c = candidates.get(a.peer);
        if (c == null || c.attempt != a || c.state != State.CLOSING) return;
        a.victimAttempt = null;
        c.attempt = null;
        c.physicalReady = false;
        c.state = c.priority != null && c.priority < 0f ? State.EXCLUDED : delay(c) > 0 ? State.BACKOFF : State.DISCOVERED;
    }

    synchronized Attempt attempt(NostrRTCPeer peer) {
        Candidate c = candidates.get(peer);
        return c == null || c.state == State.CLOSING ? null : c.attempt;
    }

    synchronized boolean active(Attempt a) {
        Candidate c = candidates.get(a.peer);
        return (
            !closed &&
            c != null &&
            c.present &&
            c.attempt == a &&
            c.state != State.CLOSING &&
            (c.priority == null || c.priority >= 0f)
        );
    }

    synchronized boolean committed(NostrRTCPeer peer) {
        Candidate c = candidates.get(peer);
        return c != null && c.state == State.ESTABLISHED && c.physicalReady;
    }

    synchronized List<Attempt> pending() {
        List<Attempt> result = new ArrayList<>();
        for (Candidate c : candidates.values()) if (
            c.attempt != null && active(c.attempt) && c.state != State.ESTABLISHED
        ) result.add(c.attempt);
        return result;
    }

    synchronized void close() {
        closed = true;
        for (Candidate c : candidates.values()) retire(c, "room-closed", false);
        served.clear();
        identityRetry.clear();
    }

    synchronized RTCConnectionDiagnostics snapshot(org.ngengine.nostr4j.rtc.routing.topology.TopologyGraph graph, Instant at) {
        return snapshot(graph, at, Collections.emptyList());
    }

    synchronized RTCConnectionDiagnostics snapshot(
        org.ngengine.nostr4j.rtc.routing.topology.TopologyGraph graph,
        Instant at,
        java.util.Collection<org.ngengine.nostr4j.rtc.routing.topology.TopologySnapshot> snapshots
    ) {
        return new RTCConnectionDiagnostics(
            settings.getMaxDirectPeers(),
            resources(),
            established(),
            hasProbe(),
            diagnostics(),
            graph,
            at,
            snapshots
        );
    }

    synchronized List<RTCConnectionDiagnostics.Candidate> diagnostics() {
        List<RTCConnectionDiagnostics.Candidate> result = new ArrayList<>();
        for (Candidate c : candidates.values()) result.add(
            new RTCConnectionDiagnostics.Candidate(
                c.peer,
                c.priority,
                c.role == 0 ? "BACKBONE" : c.role == 1 ? "REPAIR" : "OPTIONAL",
                c.state.name(),
                c.transport,
                c.physicalReady,
                c.routedReady,
                c.protectedLink,
                c.failures,
                delay(c),
                c.outcome,
                c.attempt == null ? null : c.attempt.victim
            )
        );
        return Collections.unmodifiableList(result);
    }

    synchronized int resources() {
        return (int) candidates.values().stream().filter(c -> c.attempt != null).count();
    }

    synchronized int established() {
        return (int) candidates.values().stream().filter(c -> c.state == State.ESTABLISHED && c.physicalReady).count();
    }

    synchronized boolean hasProbe() {
        return (
            resources() > settings.getMaxDirectPeers() ||
            candidates.values().stream().anyMatch(c -> c.attempt != null && c.attempt.probe && c.state != State.ESTABLISHED)
        );
    }

    private int ordinaryResources() {
        return ordinaryResourcesExcluding(null);
    }

    private int ordinaryResourcesExcluding(Attempt excluded) {
        return (int) candidates
            .values()
            .stream()
            .filter(c -> c.attempt != null && c.attempt != excluded)
            .filter(c -> !c.attempt.probe || c.state == State.ESTABLISHED)
            .count();
    }

    private int pendingOrdinary() {
        return (int) candidates
            .values()
            .stream()
            .filter(c -> c.attempt != null && !c.attempt.probe && c.state != State.ESTABLISHED)
            .count();
    }

    private List<Candidate> eligible() {
        List<Candidate> result = new ArrayList<>();
        for (Candidate c : candidates.values()) if (eligible(c)) result.add(c);
        return result;
    }

    private boolean eligible(Candidate c) {
        return (
            c.present &&
            c.attempt == null &&
            c.priority != null &&
            c.priority >= 0f &&
            delay(c) == 0L &&
            clock.getAsLong() >= identityRetry.getOrDefault(c.peer.getPubkey(), Long.MIN_VALUE)
        );
    }

    private long delay(Candidate c) {
        java.time.Duration remaining = c.backoff.getDelay(instant(clock.getAsLong()));
        long millis = remaining.toMillis();
        return remaining.minusMillis(millis).isZero() ? millis : millis + 1L;
    }

    private static Instant instant(long millis) {
        return Instant.EPOCH.plusMillis(millis);
    }

    private boolean rateAvailable(long now) {
        if (rateWindowAt == Long.MIN_VALUE || now - rateWindowAt >= Math.max(250L, settings.getRoomLoopInterval().toMillis())) {
            rateWindowAt = now;
            startsInWindow = 0;
        }
        return startsInWindow < Math.min(settings.getMaxConcurrentConnectionAttempts(), settings.getMaxDirectPeers()) + 1;
    }

    private Comparator<Candidate> order() {
        return Comparator
            .comparingInt((Candidate c) -> c.role)
            .thenComparing((Candidate c) -> c.priority, Comparator.reverseOrder())
            .thenComparing(c -> stableRank(c.peer));
    }

    private String stableRank(NostrRTCPeer peer) {
        byte[] local = hexBytes(localKey.asHex());
        byte[] remote = hexBytes(peer.getPubkey().asHex());
        for (int i = 0; i < remote.length; i++) remote[i] ^= local[i];
        return NGEUtils.bytesToHex(remote) + peer.getSessionId();
    }

    private static byte[] hexBytes(String value) {
        byte[] bytes = new byte[value.length() / 2];
        for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) Integer.parseInt(value.substring(i * 2, i * 2 + 2), 16);
        return bytes;
    }
}
