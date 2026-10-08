/**
 * BSD 3-Clause License
 *
 * Copyright (c) 2025, Riccardo Balbo
 */
package org.ngengine.nostr4j.rtc;

import static org.junit.Assert.*;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Test;
import org.ngengine.nostr4j.RTCSettings;
import org.ngengine.nostr4j.keypair.NostrKeyPair;
import org.ngengine.nostr4j.keypair.NostrPublicKey;
import org.ngengine.nostr4j.rtc.PhysicalConnectionManager.Attempt;
import org.ngengine.nostr4j.rtc.PhysicalConnectionManager.LinkState;
import org.ngengine.nostr4j.rtc.routing.topology.TopologyGraph;
import org.ngengine.nostr4j.rtc.signal.NostrRTCPeer;

public class TestPhysicalConnectionManager {

    private static final class Fixture {

        final AtomicLong clock = new AtomicLong(1000L);
        final NostrKeyPair keys = new NostrKeyPair();
        final List<NostrRTCPeer> peers = new ArrayList<>();
        final Map<NostrRTCPeer, Float> priorities = new HashMap<>();
        final Map<NostrRTCPeer, LinkState> links = new HashMap<>();
        final Map<NostrRTCPeer, Integer> roles = new HashMap<>();
        final Set<NostrRTCPeer> protectedPeers = new HashSet<>();
        final PhysicalConnectionManager manager;

        Fixture(int k, int count) {
            RTCSettings settings = RTCSettings
                .getDefault("app", "proto")
                .withMaxDirectPeers(k)
                .withConnectionRetryJitter(0f)
                .withConnectionMinimumLifetime(Duration.ofMillis(500));
            manager = new PhysicalConnectionManager(settings, keys.getPublicKey(), clock::get, () -> 0.5d);
            for (int i = 0; i < count; i++) {
                NostrRTCPeer peer = peer("s" + i, keys.getPublicKey(), true);
                peers.add(peer);
                priorities.put(peer, (float) (count - i));
            }
            update();
        }

        void policy() {
            manager.setPriority(priorities::get);
            update();
        }

        void update() {
            assertTrue(manager.evaluate(peers));
            manager.reconcile(links, roles, protectedPeers);
        }

        void advance(long millis) {
            clock.addAndGet(millis);
            update();
        }

        void establish(Attempt a) {
            assertNotNull(a);
            if (a.outgoing) assertTrue(manager.accepted(a));
            links.put(a.peer, new LinkState(true, false, "RTC"));
            update();
            manager.ready(a, false);
            manager.ready(a, true);
            assertTrue(manager.prepareCommit(a));
            assertTrue(manager.commit(a));
        }

        void release() {
            for (Attempt a : manager.takeClosures()) manager.released(a);
        }

        RTCConnectionDiagnostics snapshot() {
            return manager.snapshot(new TopologyGraph(Set.of(), Set.of()), Instant.EPOCH);
        }

        RTCConnectionDiagnostics.Candidate diagnostic(NostrRTCPeer peer) {
            return snapshot().getCandidates().stream().filter(c -> c.getPeer().equals(peer)).findFirst().orElseThrow();
        }

        List<Attempt> fillEstablished() {
            List<Attempt> result = manager.fill();
            result.forEach(this::establish);
            return result;
        }
    }

    private static NostrRTCPeer peer(String session, NostrPublicKey room, boolean capable) {
        return new NostrRTCPeer(new NostrKeyPair().getPublicKey(), "app", "proto", session, room, null) {
            @Override
            public boolean supportsLinkAdmission() {
                return capable;
            }
        };
    }

    @Test
    public void nullPolicyFillsWithoutPreferenceSwaps() {
        Fixture f = new Fixture(2, 5);
        assertEquals(2, f.fillEstablished().size());
        f.advance(1000);
        assertTrue(f.manager.fill().isEmpty());
        assertEquals(2, f.manager.resources());
    }

    @Test
    public void finitePrioritiesZeroNegativeAndMaximum() {
        Fixture f = new Fixture(2, 5);
        f.priorities.put(f.peers.get(0), -1f);
        f.priorities.put(f.peers.get(1), 0f);
        f.priorities.put(f.peers.get(2), Float.MAX_VALUE);
        f.policy();
        List<Attempt> attempts = f.manager.fill();
        assertEquals(f.peers.get(2), attempts.get(0).peer);
        assertFalse(attempts.stream().anyMatch(a -> a.peer.equals(f.peers.get(0))));
        assertEquals("EXCLUDED", f.diagnostic(f.peers.get(0)).getState());
        f.manager.fail(attempts.get(0), "failure");
        f.manager.fail(attempts.get(1), "failure");
        f.release();
        f.priorities.replaceAll((p, v) -> p.equals(f.peers.get(1)) ? 0f : -1f);
        f.policy();
        f.advance(1000);
        assertEquals(f.peers.get(1), f.manager.fill().get(0).peer);
    }

    @Test
    public void invalidPolicyRetainsLastValidAndDoesNotRunUnderLock() throws Exception {
        Fixture f = new Fixture(2, 3);
        f.policy();
        f.fillEstablished();
        AtomicInteger calls = new AtomicInteger();
        f.manager.setPriority(peer -> {
            calls.incrementAndGet();
            Thread observer = new Thread(f.manager::resources);
            observer.start();
            try {
                observer.join(1000);
            } catch (InterruptedException error) {
                throw new AssertionError(error);
            }
            assertFalse("callback ran under manager lock", observer.isAlive());
            if (peer.equals(f.peers.get(0))) return Float.NaN;
            if (peer.equals(f.peers.get(1))) return Float.POSITIVE_INFINITY;
            throw new IllegalStateException("invalid application policy");
        });
        f.update();
        assertEquals(3, calls.get());
        assertEquals(2, f.manager.established());
        assertEquals(Float.valueOf(3f), f.diagnostic(f.peers.get(0)).getPriority());
    }

    @Test
    public void firstInvalidEvaluationDoesNotAttemptPeer() {
        Fixture f = new Fixture(2, 0);
        f.manager.setPriority(peer -> Float.NaN);
        NostrRTCPeer added = peer("new", f.keys.getPublicKey(), true);
        f.peers.add(added);
        f.update();
        assertNull(f.diagnostic(added).getPriority());
        assertTrue(f.manager.fill().isEmpty());
    }

    @Test
    public void priorityEpochChangesDuringEvaluationDiscardThePlan() {
        Fixture f = new Fixture(2, 3);
        f.manager.setPriority(peer -> {
            f.manager.setPriority(p -> 0f);
            return -1f;
        });
        assertFalse(f.manager.evaluate(f.peers));
        assertTrue(f.manager.fill().isEmpty());
        f.update();
        assertEquals(2, f.manager.fill().size());
    }

    @Test
    public void equivalentCallbackDoesNotResetAnAttemptOrBackoff() {
        Fixture f = new Fixture(2, 3);
        f.policy();
        List<Attempt> a = f.manager.fill();
        f.manager.fail(a.get(0), "failure");
        f.release();
        f.manager.setPriority(peer -> f.priorities.get(peer));
        f.update();
        assertSame(a.get(1), f.manager.attempt(a.get(1).peer));
        assertEquals(250, f.diagnostic(a.get(0).peer).getRetryAfterMillis());
    }

    @Test
    public void failedLeaderDoesNotBlockOtherCandidates() {
        Fixture f = new Fixture(2, 4);
        f.policy();
        List<Attempt> a = f.manager.fill();
        f.manager.fail(a.get(0), "timeout");
        f.establish(a.get(1));
        f.release();
        f.advance(1000);
        List<Attempt> next = f.manager.fill();
        assertEquals(1, next.size());
        assertNotEquals(a.get(0).peer, next.get(0).peer);
        f.establish(next.get(0));
        assertEquals(2, f.manager.established());
    }

    @Test
    public void backoffStartsAtTerminalFailureAndCountsExactlyOnce() {
        Fixture f = new Fixture(2, 3);
        List<Attempt> a = f.manager.fill();
        f.advance(20_000);
        f.manager.fail(a.get(0), "failure");
        f.manager.fail(a.get(0), "duplicate");
        f.release();
        assertEquals(1, f.diagnostic(a.get(0).peer).getFailureCount());
        assertEquals(250, f.diagnostic(a.get(0).peer).getRetryAfterMillis());
        f.advance(249);
        assertEquals(1, f.diagnostic(a.get(0).peer).getRetryAfterMillis());
        f.update();
        assertEquals(1, f.diagnostic(a.get(0).peer).getRetryAfterMillis());
    }

    @Test
    public void pendingAndClosingResourcesRemainCharged() {
        Fixture f = new Fixture(2, 5);
        List<Attempt> a = f.manager.fill();
        f.manager.fail(a.get(0), "failure");
        f.advance(1000);
        assertEquals(2, f.manager.resources());
        assertTrue(f.manager.fill().isEmpty());
        f.release();
        f.advance(1000);
        assertEquals(1, f.manager.fill().size());
    }

    @Test
    public void physicalReadinessAndRemoteAdmissionAreRequiredForCommit() {
        Fixture f = new Fixture(2, 3);
        f.policy();
        Attempt a = f.manager.fill().get(0);
        f.manager.accepted(a);
        f.manager.ready(a, false);
        f.manager.ready(a, true);
        assertFalse(f.manager.commit(a));
        f.links.put(a.peer, new LinkState(false, true, "NONE"));
        f.update();
        assertEquals(0, f.manager.established());
        assertFalse(f.manager.commit(a));
    }

    @Test
    public void successfulSwapUsesOneProbeAndPreservesVictimUntilCommit() {
        Fixture f = new Fixture(2, 4);
        List<Attempt> old = f.fillEstablished();
        f.policy();
        f.advance(1000);
        for (Attempt a : old) f.priorities.put(a.peer, 0f);
        f.update();
        Attempt probe = f.manager.fill().get(0);
        assertTrue(probe.probe);
        assertEquals(3, f.manager.resources());
        assertEquals(2, f.manager.established());
        assertTrue(f.manager.committed(probe.victim));
        assertFalse(f.manager.committed(probe.peer));
        f.establish(probe);
        assertFalse(f.manager.committed(probe.victim));
        assertTrue(f.manager.committed(probe.peer));
        f.advance(1000);
        assertTrue("closing resources forbid K+2", f.manager.fill().isEmpty());
        f.release();
        assertEquals(2, f.manager.resources());
    }

    @Test
    public void failedSwapPreservesOldAndSharesRetryWithFill() {
        Fixture f = new Fixture(2, 4);
        List<Attempt> old = f.fillEstablished();
        f.policy();
        old.forEach(a -> f.priorities.put(a.peer, 0f));
        f.advance(1000);
        Attempt probe = f.manager.fill().get(0);
        f.manager.fail(probe, "remote-busy");
        f.release();
        assertEquals(2, f.manager.established());
        assertEquals(250, f.diagnostic(probe.peer).getRetryAfterMillis());
        f.peers.remove(old.get(0).peer);
        f.update();
        f.release();
        assertNull(f.manager.admit(probe.peer, "f".repeat(32), true));
    }

    @Test
    public void equalPrioritiesNeverSwap() {
        Fixture f = new Fixture(2, 5);
        f.priorities.replaceAll((p, v) -> 0f);
        f.policy();
        f.fillEstablished();
        f.advance(1000);
        assertTrue(f.manager.fill().isEmpty());
    }

    @Test
    public void victimRemovalPromotesIntoFreeSlot() {
        Fixture f = new Fixture(2, 4);
        List<Attempt> old = f.fillEstablished();
        f.policy();
        old.forEach(a -> f.priorities.put(a.peer, 0f));
        f.advance(1000);
        Attempt probe = f.manager.fill().get(0);
        f.peers.remove(probe.victim);
        f.update();
        f.release();
        f.establish(probe);
        assertEquals(2, f.manager.established());
        assertTrue(f.manager.takeClosures().isEmpty());
    }

    @Test
    public void capturedVictimLosingPhysicalReadinessDoesNotStallAProvedReplacement() {
        Fixture f = new Fixture(2, 4);
        List<Attempt> old = f.fillEstablished();
        f.policy();
        old.forEach(a -> f.priorities.put(a.peer, 0f));
        f.advance(1000);
        Attempt probe = f.manager.fill().get(0);
        f.links.remove(probe.victim);
        f.update();
        assertEquals("The lost resource is still charged during recovery grace", 3, f.manager.resources());
        f.establish(probe);
        List<Attempt> closures = f.manager.takeClosures();
        assertEquals(1, closures.size());
        assertEquals(probe.victim, closures.get(0).peer);
        closures.forEach(f.manager::released);
        assertEquals(2, f.manager.resources());
        assertEquals(2, f.manager.established());
    }

    @Test
    public void policyChangeDuringProbePreservesHealthyVictim() {
        Fixture f = new Fixture(2, 4);
        List<Attempt> old = f.fillEstablished();
        f.policy();
        old.forEach(a -> f.priorities.put(a.peer, 0f));
        f.advance(1000);
        Attempt probe = f.manager.fill().get(0);
        f.priorities.put(probe.peer, 0f);
        f.update();
        f.manager.accepted(probe);
        f.links.put(probe.peer, new LinkState(true, false, "RTC"));
        f.update();
        f.manager.ready(probe, true);
        f.manager.ready(probe, false);
        assertFalse(f.manager.commit(probe));
        assertTrue(f.manager.committed(probe.victim));
    }

    @Test
    public void backboneAndRepairVictimsAreProtected() {
        Fixture f = new Fixture(2, 4);
        List<Attempt> old = f.fillEstablished();
        f.policy();
        old.forEach(a -> {
            f.priorities.put(a.peer, 0f);
            f.protectedPeers.add(a.peer);
        });
        f.advance(1000);
        assertTrue(f.manager.fill().isEmpty());
        assertEquals(2, f.manager.established());
    }

    @Test
    public void minimumLifetimePreventsImmediateOscillation() {
        Fixture f = new Fixture(2, 4);
        List<Attempt> old = f.fillEstablished();
        f.policy();
        old.forEach(a -> f.priorities.put(a.peer, 0f));
        f.update();
        assertTrue(f.manager.fill().isEmpty());
        f.advance(1000);
        assertEquals(1, f.manager.fill().size());
    }

    @Test
    public void concurrentInboundReservationsRespectKAndOneProbe() throws Exception {
        Fixture f = new Fixture(2, 20);
        List<Attempt> old = f.fillEstablished();
        f.policy();
        old.forEach(a -> f.priorities.put(a.peer, 0f));
        f.advance(1000);
        CountDownLatch start = new CountDownLatch(1);
        List<Thread> threads = new ArrayList<>();
        AtomicInteger accepted = new AtomicInteger();
        for (NostrRTCPeer peer : f.peers) {
            Thread t = new Thread(() -> {
                try {
                    start.await();
                } catch (InterruptedException error) {
                    throw new AssertionError(error);
                }
                if (f.manager.admit(peer, "e".repeat(32), true) != null) accepted.incrementAndGet();
            });
            threads.add(t);
            t.start();
        }
        start.countDown();
        for (Thread t : threads) t.join();
        assertEquals(1, accepted.get());
        assertEquals(3, f.manager.resources());
    }

    @Test
    public void localCap16UsesOnlyOneSupplementaryProbe() {
        Fixture f = new Fixture(16, 18);
        for (int i = 0; i < 4; i++) {
            assertEquals(4, f.fillEstablished().size());
            f.advance(1000);
        }
        f.policy();
        for (RTCConnectionDiagnostics.Candidate c : f.snapshot().getCandidates()) if (c.isPhysicalReady()) f.priorities.put(
            c.getPeer(),
            0f
        );
        f.update();
        Attempt probe = f.manager.fill().get(0);
        assertEquals(17, f.manager.resources());
        assertEquals(16, f.manager.established());
        f.manager.fail(probe, "remote-busy");
        f.release();
        assertEquals(16, f.manager.resources());
        assertEquals(16, f.manager.established());
    }

    @Test
    public void localCap64NeverCommits65Neighbors() {
        Fixture f = new Fixture(64, 66);
        for (int i = 0; i < 16; i++) {
            assertEquals(4, f.fillEstablished().size());
            f.advance(1000);
        }
        assertEquals(64, f.manager.established());
        f.policy();
        for (RTCConnectionDiagnostics.Candidate c : f.snapshot().getCandidates()) if (c.isPhysicalReady()) f.priorities.put(
            c.getPeer(),
            0f
        );
        f.update();
        Attempt probe = f.manager.fill().get(0);
        assertEquals(65, f.manager.resources());
        assertEquals(64, f.manager.established());
        f.establish(probe);
        assertEquals(64, f.manager.established());
    }

    @Test
    public void admissionUsesEachEndpointsOwnPolicyAndCapacity() {
        Fixture f = new Fixture(2, 4);
        f.policy();
        assertNotNull(f.manager.admit(f.peers.get(3), "c".repeat(32), true));
        assertNotNull(f.manager.admit(f.peers.get(2), "d".repeat(32), true));
        assertNull(f.manager.admit(f.peers.get(0), "e".repeat(32), true));
        assertEquals(2, f.manager.resources());
    }

    @Test
    public void lateCallbacksAfterExclusionRemovalOrCloseCannotCommit() {
        Fixture f = new Fixture(2, 3);
        f.policy();
        Attempt a = f.manager.fill().get(0);
        f.priorities.put(a.peer, -1f);
        f.update();
        assertFalse(f.manager.active(a));
        assertFalse(f.manager.accepted(a));
        assertFalse(f.manager.commit(a));
        f.release();
        f.priorities.put(a.peer, 3f);
        f.advance(1000);
        Attempt newAttempt = f.manager.fill().get(0);
        f.manager.close();
        assertFalse(f.manager.active(newAttempt));
        assertFalse(f.manager.commit(newAttempt));
    }

    @Test
    public void turnSurvivesBriefRtcDegradationWithoutRepeatedTeardown() {
        Fixture f = new Fixture(2, 3);
        Attempt a = f.fillEstablished().get(0);
        f.links.put(a.peer, new LinkState(false, true, "NONE"));
        f.update();
        f.advance(1000);
        assertSame(a, f.manager.attempt(a.peer));
        f.links.put(a.peer, new LinkState(true, false, "TURN"));
        f.update();
        assertEquals(2, f.manager.established());
        assertTrue(f.manager.takeClosures().isEmpty());
    }

    @Test
    public void sessionChurnDoesNotBypassIdentityCooldown() {
        Fixture f = new Fixture(2, 1);
        Attempt a = f.manager.fill().get(0);
        f.manager.fail(a, "failure");
        f.release();
        NostrRTCPeer changed = new NostrRTCPeer(a.peer.getPubkey(), "app", "proto", "new", f.keys.getPublicKey(), null) {
            @Override
            public boolean supportsLinkAdmission() {
                return true;
            }
        };
        f.peers.clear();
        f.peers.add(changed);
        f.update();
        assertNull(f.manager.admit(changed, "e".repeat(32), true));
        assertFalse(f.manager.active(a));
    }

    @Test
    public void startsAndControlResponsesStayBoundedUnderInboundPressure() {
        Fixture f = new Fixture(16, 12);
        List<Attempt> first = f.manager.fill();
        assertEquals(4, first.size());
        first.forEach(a -> f.manager.fail(a, "unavailable"));
        f.release();
        List<NostrRTCPeer> remaining = new ArrayList<>(f.peers);
        first.forEach(a -> remaining.remove(a.peer));
        assertNotNull(f.manager.admit(remaining.get(0), "1".repeat(32), true));
        assertNull(f.manager.admit(remaining.get(1), "2".repeat(32), true));
        for (int i = 0; i < 32; i++) assertTrue(f.manager.responseAllowed());
        assertFalse(f.manager.responseAllowed());
        f.advance(1000);
        assertNotNull(f.manager.admit(remaining.get(1), "2".repeat(32), true));
        assertTrue(f.manager.responseAllowed());
    }

    @Test
    public void diagnosticsAreImmutableAndBounded() {
        Fixture f = new Fixture(2, 4);
        RTCConnectionDiagnostics before = f.snapshot();
        f.manager.fill();
        assertEquals(0, before.getOccupiedResources());
        assertEquals(2, f.snapshot().getOccupiedResources());
        try {
            before.getCandidates().clear();
            fail();
        } catch (UnsupportedOperationException expected) {}
        List<NostrRTCPeer> oversized = new ArrayList<>();
        for (int i = 0; i < PhysicalConnectionManager.MAX_CANDIDATES + 10; i++) oversized.add(
            new NostrRTCPeer(f.keys.getPublicKey(), "app", "proto", "bounded-" + i, f.keys.getPublicKey(), null)
        );
        assertTrue(f.manager.evaluate(oversized));
        assertEquals(PhysicalConnectionManager.MAX_CANDIDATES, f.snapshot().getCandidates().size());
    }
}
