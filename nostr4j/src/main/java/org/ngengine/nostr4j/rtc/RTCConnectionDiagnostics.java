/**
 * BSD 3-Clause License
 *
 * Copyright (c) 2025, Riccardo Balbo
 */
package org.ngengine.nostr4j.rtc;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import org.ngengine.nostr4j.keypair.NostrPublicKey;
import org.ngengine.nostr4j.rtc.routing.topology.TopologyGraph;
import org.ngengine.nostr4j.rtc.routing.topology.TopologySnapshot;
import org.ngengine.nostr4j.rtc.signal.NostrRTCPeer;

/**
 * Immutable bounded snapshot. Retry delays use monotonic time; topology freshness uses UTC.
 */
public final class RTCConnectionDiagnostics {

    private final int maxDirectPeers, occupiedResources, establishedLinks;
    private final boolean probeInUse;
    private final List<Candidate> candidates;
    private final TopologyGraph topology;
    private final Instant topologyEvaluatedAt;
    private final List<TopologySnapshot> topologySnapshots;

    RTCConnectionDiagnostics(
        int maxDirectPeers,
        int occupiedResources,
        int establishedLinks,
        boolean probeInUse,
        List<Candidate> candidates,
        TopologyGraph topology,
        Instant topologyEvaluatedAt,
        Collection<TopologySnapshot> snapshots
    ) {
        this.maxDirectPeers = maxDirectPeers;
        this.occupiedResources = occupiedResources;
        this.establishedLinks = establishedLinks;
        this.probeInUse = probeInUse;
        this.candidates = List.copyOf(candidates);
        this.topology = topology;
        this.topologyEvaluatedAt = topologyEvaluatedAt;
        this.topologySnapshots = List.copyOf(snapshots);
    }

    public int getMaxDirectPeers() {
        return maxDirectPeers;
    }

    /**
     * Includes pending reservations, probes and resources still being closed.
     */
    public int getOccupiedResources() {
        return occupiedResources;
    }

    public int getEstablishedLinks() {
        return establishedLinks;
    }

    public int getPendingAttempts() {
        return (int) candidates.stream().filter(c -> c.state.equals("CONNECTING") || c.state.equals("PROBING_SWAP")).count();
    }

    public int getClosingResources() {
        return (int) candidates.stream().filter(c -> c.state.equals("CLOSING")).count();
    }

    public List<TopologySnapshot> getTopologySnapshots() {
        return topologySnapshots;
    }

    public boolean isProbeInUse() {
        return probeInUse;
    }

    public List<Candidate> getCandidates() {
        return candidates;
    }

    public TopologyGraph getTopology() {
        return topology;
    }

    public Instant getTopologyEvaluatedAt() {
        return topologyEvaluatedAt;
    }

    /**
     * Copied values only; no transport-owned peer or mutable metadata can escape a snapshot.
     */
    public static final class PeerIdentity {

        private final String publicKey, applicationId, protocolId, sessionId, roomPublicKey, turnServer;
        private final int nipDcVersion;
        private final Instant lastSeen;

        private PeerIdentity(NostrRTCPeer peer) {
            publicKey = peer.getPubkey().asHex();
            applicationId = peer.getApplicationId();
            protocolId = peer.getProtocolId();
            sessionId = peer.getSessionId();
            roomPublicKey = peer.getRoomPubkey().asHex();
            turnServer = peer.getTurnServer();
            nipDcVersion = peer.getNipDcVersion();
            lastSeen = peer.getLastSeen();
        }

        public String getPublicKey() {
            return publicKey;
        }

        public String getApplicationId() {
            return applicationId;
        }

        public String getProtocolId() {
            return protocolId;
        }

        public String getSessionId() {
            return sessionId;
        }

        public String getRoomPublicKey() {
            return roomPublicKey;
        }

        public String getTurnServer() {
            return turnServer;
        }

        public int getNipDcVersion() {
            return nipDcVersion;
        }

        public Instant getLastSeen() {
            return lastSeen;
        }

        private NostrRTCPeer detachedCopy() {
            NostrRTCPeer copy = new NostrRTCPeer(
                NostrPublicKey.fromHex(publicKey),
                applicationId,
                protocolId,
                sessionId,
                NostrPublicKey.fromHex(roomPublicKey),
                turnServer,
                nipDcVersion
            );
            copy.setLastSeen(lastSeen);
            return copy;
        }
    }

    public static final class Candidate {

        private final PeerIdentity peer, swapVictim;
        private final Float priority;
        private final String role, state, physicalTransport, outcome;
        private final boolean physicalReady, routedReady, protectedLink;
        private final int failureCount;
        private final long retryAfterMillis;

        Candidate(
            NostrRTCPeer peer,
            Float priority,
            String role,
            String state,
            String physicalTransport,
            boolean physicalReady,
            boolean routedReady,
            boolean protectedLink,
            int failureCount,
            long retryAfterMillis,
            String outcome,
            NostrRTCPeer swapVictim
        ) {
            this.peer = new PeerIdentity(peer);
            this.priority = priority;
            this.role = role;
            this.state = state;
            this.physicalTransport = physicalTransport;
            this.physicalReady = physicalReady;
            this.routedReady = routedReady;
            this.protectedLink = protectedLink;
            this.failureCount = failureCount;
            this.retryAfterMillis = retryAfterMillis;
            this.outcome = outcome;
            this.swapVictim = swapVictim == null ? null : new PeerIdentity(swapVictim);
        }

        /**
         * Detached compatibility copy. Prefer getPeerIdentity for allocation-free snapshot inspection.
         */
        public NostrRTCPeer getPeer() {
            return peer.detachedCopy();
        }

        public PeerIdentity getPeerIdentity() {
            return peer;
        }

        /**
         * Null means this candidate has never had a valid evaluation.
         */
        public Float getPriority() {
            return priority;
        }

        public String getRole() {
            return role;
        }

        public String getState() {
            return state;
        }

        public String getPhysicalTransport() {
            return physicalTransport;
        }

        public boolean isPhysicalReady() {
            return physicalReady;
        }

        public boolean isRoutedReady() {
            return routedReady;
        }

        /**
         * Ring, repair and graph-bridge protection prevents preference-only eviction.
         */
        public boolean isProtectedLink() {
            return protectedLink;
        }

        public int getFailureCount() {
            return failureCount;
        }

        public long getRetryAfterMillis() {
            return retryAfterMillis;
        }

        public String getOutcome() {
            return outcome;
        }

        /**
         * Detached compatibility copy, independent of both live transport state and this snapshot.
         */
        public NostrRTCPeer getSwapVictim() {
            return swapVictim == null ? null : swapVictim.detachedCopy();
        }

        public PeerIdentity getSwapVictimIdentity() {
            return swapVictim;
        }
    }
}
