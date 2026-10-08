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

import java.io.Serializable;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public final class RTCSettings implements Cloneable, Serializable {

    private static final long serialVersionUID = 1L;

    public static final Duration SIGNALING_LOOP_INTERVAL = Duration.ofSeconds(5);
    public static final Duration SIGNALING_ANNOUNCE_EXPIRATION = Duration.ofSeconds(60);
    public static final Duration PEER_EXPIRATION = Duration.ofMinutes(5);
    public static final Duration DELAYED_CANDIDATES_INTERVAL = Duration.ofMillis(100);
    public static final Duration ROOM_LOOP_INTERVAL = Duration.ofSeconds(1);
    public static final Duration P2P_TIMEOUT = Duration.ofSeconds(30);
    public static final Duration QUEUED_SEND_TIMEOUT = Duration.ofSeconds(30);
    public static final int DEFAULT_MAX_DIRECT_PEERS = 16;
    public static final int MIN_MAX_DIRECT_PEERS = 2;

    public static final Collection<String> PUBLIC_STUN_SERVERS = Collections.unmodifiableCollection(
        Arrays.asList(
            new String[] {
                "stun.cloudflare.com:3478",
                "stun.l.google.com:19302",
                "stun1.l.google.com:3478",
                "stun4.l.google.com:19302",
                "stun4.l.google.com:5349",
                "stunserver2024.stunprotocol.org:3478",
            }
        )
    );

    public static final Collection<String> DEFAULT_SIGNALING_RELAYS = Collections.unmodifiableCollection(
        Arrays.asList(
            new String[] {
                "wss://relay.damus.io",
                "wss://nostr.rblb.it",
                "wss://relay.ngengine.org",
                "wss://relay2.ngengine.org",
                "wss://nostr.oxtr.dev",
                "wss://nostr.bitcoiner.social",
                "wss://nostr-pub.wellorder.net",
                "wss://relay.snort.social",
            }
        )
    );

    private final Duration signalingLoopInterval;
    private final Duration signalingAnnounceExpiration;
    private final Duration peerExpiration;
    private final Duration delayedCandidatesInterval;
    private final Duration roomLoopInterval;
    private final Duration p2pAttemptTimeout;
    private final Duration queuedSendTimeout;
    private final Duration p2pGiveupTimeout;
    private final int maxDirectPeers;
    private final Duration connectionRetryInitialDelay;
    private final Duration connectionRetryMaxDelay;
    private final float connectionRetryMultiplier;
    private final float connectionRetryJitter;
    private final int maxConcurrentConnectionAttempts;
    private final Duration connectionMinimumLifetime;

    private final Collection<String> stunServers;
    private final List<String> signalingRelays;
    private final String applicationId;
    private final String protocolId;

    private RTCSettings(
        Duration announceInterval,
        Duration peerExpiration,
        Duration delayedCandidatesInterval,
        Duration roomLoopInterval,
        Duration p2pAttemptTimeout,
        Duration queuedSendTimeout,
        Duration signalingAnnounceExpiration,
        int maxDirectPeers,
        Duration connectionRetryInitialDelay,
        Duration connectionRetryMaxDelay,
        float connectionRetryMultiplier,
        float connectionRetryJitter,
        int maxConcurrentConnectionAttempts,
        Duration connectionMinimumLifetime,
        Collection<String> stunServers,
        Collection<String> signalingRelays,
        String applicationId,
        String protocolId
    ) {
        if (maxDirectPeers < MIN_MAX_DIRECT_PEERS) {
            throw new IllegalArgumentException("maxDirectPeers must be at least " + MIN_MAX_DIRECT_PEERS);
        }
        if (maxDirectPeers > 64) {
            throw new IllegalArgumentException("maxDirectPeers must not exceed 64");
        }
        this.signalingLoopInterval = Objects.requireNonNull(announceInterval, "announceInterval");
        this.signalingAnnounceExpiration = Objects.requireNonNull(signalingAnnounceExpiration, "signalingAnnounceExpiration");
        this.peerExpiration = Objects.requireNonNull(peerExpiration, "peerExpiration");
        if (peerExpiration.isNegative()) {
            throw new IllegalArgumentException("peerExpiration must not be negative");
        }
        this.delayedCandidatesInterval = Objects.requireNonNull(delayedCandidatesInterval, "delayedCandidatesInterval");
        this.roomLoopInterval = Objects.requireNonNull(roomLoopInterval, "roomLoopInterval");
        this.p2pAttemptTimeout = Objects.requireNonNull(p2pAttemptTimeout, "p2pAttemptTimeout");
        this.queuedSendTimeout = Objects.requireNonNull(queuedSendTimeout, "queuedSendTimeout");
        this.p2pGiveupTimeout = p2pAttemptTimeout.multipliedBy(4);
        this.maxDirectPeers = maxDirectPeers;
        requirePositiveMillis(connectionRetryInitialDelay, "connectionRetryInitialDelay");
        requirePositiveMillis(connectionRetryMaxDelay, "connectionRetryMaxDelay");
        requirePositiveMillis(connectionMinimumLifetime, "connectionMinimumLifetime");
        if (connectionRetryMaxDelay.compareTo(connectionRetryInitialDelay) < 0) {
            throw new IllegalArgumentException("connectionRetryMaxDelay must be >= connectionRetryInitialDelay");
        }
        if (!Float.isFinite(connectionRetryMultiplier) || connectionRetryMultiplier <= 1f) {
            throw new IllegalArgumentException("connectionRetryMultiplier must be finite and > 1");
        }
        if (!Float.isFinite(connectionRetryJitter) || connectionRetryJitter < 0f || connectionRetryJitter > 0.5f) {
            throw new IllegalArgumentException("connectionRetryJitter must be between 0 and 0.5");
        }
        if (maxConcurrentConnectionAttempts < 1 || maxConcurrentConnectionAttempts > 64) {
            throw new IllegalArgumentException("maxConcurrentConnectionAttempts must be between 1 and 64");
        }
        this.connectionRetryInitialDelay = connectionRetryInitialDelay;
        this.connectionRetryMaxDelay = connectionRetryMaxDelay;
        this.connectionRetryMultiplier = connectionRetryMultiplier;
        this.connectionRetryJitter = connectionRetryJitter;
        this.maxConcurrentConnectionAttempts = maxConcurrentConnectionAttempts;
        this.connectionMinimumLifetime = connectionMinimumLifetime;

        this.stunServers = List.copyOf(stunServers);
        if (this.stunServers.stream().anyMatch(String::isBlank)) {
            throw new IllegalArgumentException("stunServers must not contain blank addresses");
        }
        this.signalingRelays = List.copyOf(signalingRelays);
        if (this.signalingRelays.stream().anyMatch(String::isBlank)) {
            throw new IllegalArgumentException("signalingRelays must not contain blank URLs");
        }
        this.applicationId = applicationId;
        this.protocolId = protocolId;
    }

    private static void requirePositiveMillis(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isNegative() || value.compareTo(Duration.ofDays(1)) > 0 || value.toMillis() < 1L) {
            throw new IllegalArgumentException(name + " must be between 1 ms and 1 day");
        }
    }

    public Duration getConnectionRetryInitialDelay() {
        return connectionRetryInitialDelay;
    }

    /**
     * Returns a copy with {@code connectionRetryInitialDelay} changed.
     */
    public RTCSettings withConnectionRetryInitialDelay(Duration connectionRetryInitialDelay) {
        return new RTCSettings(
            signalingLoopInterval,
            peerExpiration,
            delayedCandidatesInterval,
            roomLoopInterval,
            p2pAttemptTimeout,
            queuedSendTimeout,
            signalingAnnounceExpiration,
            maxDirectPeers,
            connectionRetryInitialDelay,
            connectionRetryMaxDelay,
            connectionRetryMultiplier,
            connectionRetryJitter,
            maxConcurrentConnectionAttempts,
            connectionMinimumLifetime,
            stunServers,
            signalingRelays,
            applicationId,
            protocolId
        );
    }

    public Duration getConnectionRetryMaxDelay() {
        return connectionRetryMaxDelay;
    }

    /**
     * Returns a copy with {@code connectionRetryMaxDelay} changed.
     */
    public RTCSettings withConnectionRetryMaxDelay(Duration connectionRetryMaxDelay) {
        return new RTCSettings(
            signalingLoopInterval,
            peerExpiration,
            delayedCandidatesInterval,
            roomLoopInterval,
            p2pAttemptTimeout,
            queuedSendTimeout,
            signalingAnnounceExpiration,
            maxDirectPeers,
            connectionRetryInitialDelay,
            connectionRetryMaxDelay,
            connectionRetryMultiplier,
            connectionRetryJitter,
            maxConcurrentConnectionAttempts,
            connectionMinimumLifetime,
            stunServers,
            signalingRelays,
            applicationId,
            protocolId
        );
    }

    public float getConnectionRetryMultiplier() {
        return connectionRetryMultiplier;
    }

    /**
     * Returns a copy with {@code connectionRetryMultiplier} changed.
     */
    public RTCSettings withConnectionRetryMultiplier(float connectionRetryMultiplier) {
        return new RTCSettings(
            signalingLoopInterval,
            peerExpiration,
            delayedCandidatesInterval,
            roomLoopInterval,
            p2pAttemptTimeout,
            queuedSendTimeout,
            signalingAnnounceExpiration,
            maxDirectPeers,
            connectionRetryInitialDelay,
            connectionRetryMaxDelay,
            connectionRetryMultiplier,
            connectionRetryJitter,
            maxConcurrentConnectionAttempts,
            connectionMinimumLifetime,
            stunServers,
            signalingRelays,
            applicationId,
            protocolId
        );
    }

    public float getConnectionRetryJitter() {
        return connectionRetryJitter;
    }

    /**
     * Returns a copy with {@code connectionRetryJitter} changed.
     */
    public RTCSettings withConnectionRetryJitter(float connectionRetryJitter) {
        return new RTCSettings(
            signalingLoopInterval,
            peerExpiration,
            delayedCandidatesInterval,
            roomLoopInterval,
            p2pAttemptTimeout,
            queuedSendTimeout,
            signalingAnnounceExpiration,
            maxDirectPeers,
            connectionRetryInitialDelay,
            connectionRetryMaxDelay,
            connectionRetryMultiplier,
            connectionRetryJitter,
            maxConcurrentConnectionAttempts,
            connectionMinimumLifetime,
            stunServers,
            signalingRelays,
            applicationId,
            protocolId
        );
    }

    public int getMaxConcurrentConnectionAttempts() {
        return maxConcurrentConnectionAttempts;
    }

    /**
     * Returns a copy with {@code maxConcurrentConnectionAttempts} changed.
     */
    public RTCSettings withMaxConcurrentConnectionAttempts(int maxConcurrentConnectionAttempts) {
        return new RTCSettings(
            signalingLoopInterval,
            peerExpiration,
            delayedCandidatesInterval,
            roomLoopInterval,
            p2pAttemptTimeout,
            queuedSendTimeout,
            signalingAnnounceExpiration,
            maxDirectPeers,
            connectionRetryInitialDelay,
            connectionRetryMaxDelay,
            connectionRetryMultiplier,
            connectionRetryJitter,
            maxConcurrentConnectionAttempts,
            connectionMinimumLifetime,
            stunServers,
            signalingRelays,
            applicationId,
            protocolId
        );
    }

    public Duration getConnectionMinimumLifetime() {
        return connectionMinimumLifetime;
    }

    /**
     * Returns a copy with {@code connectionMinimumLifetime} changed.
     */
    public RTCSettings withConnectionMinimumLifetime(Duration connectionMinimumLifetime) {
        return new RTCSettings(
            signalingLoopInterval,
            peerExpiration,
            delayedCandidatesInterval,
            roomLoopInterval,
            p2pAttemptTimeout,
            queuedSendTimeout,
            signalingAnnounceExpiration,
            maxDirectPeers,
            connectionRetryInitialDelay,
            connectionRetryMaxDelay,
            connectionRetryMultiplier,
            connectionRetryJitter,
            maxConcurrentConnectionAttempts,
            connectionMinimumLifetime,
            stunServers,
            signalingRelays,
            applicationId,
            protocolId
        );
    }

    public Duration getSignalingAnnounceExpiration() {
        return signalingAnnounceExpiration;
    }

    public Duration getP2pGiveupTimeout() {
        return p2pGiveupTimeout;
    }

    public Duration getSignalingLoopInterval() {
        return signalingLoopInterval;
    }

    public Duration getPeerExpiration() {
        return peerExpiration;
    }

    public Duration getRoomLoopInterval() {
        return roomLoopInterval;
    }

    public Duration getDelayedCandidatesInterval() {
        return delayedCandidatesInterval;
    }

    public Duration getP2pAttemptTimeout() {
        return p2pAttemptTimeout;
    }

    public Duration getQueuedSendTimeout() {
        return queuedSendTimeout;
    }

    public int getMaxDirectPeers() {
        return maxDirectPeers;
    }

    /**
     * Returns the STUN servers used by local peers with these settings.
     *
     * @return an unmodifiable collection of STUN server addresses
     */
    public Collection<String> getStunServers() {
        return stunServers;
    }

    public List<String> getSignalingRelays() {
        return signalingRelays;
    }

    public String getApplicationId() {
        return applicationId;
    }

    public String getProtocolId() {
        return protocolId;
    }

    /**
     * Returns a copy with the interval between signaling announcements changed.
     *
     * @param signalingLoopInterval interval between successive signaling announcements
     * @return a copy with the specified interval
     */
    public RTCSettings withSignalingLoopInterval(Duration signalingLoopInterval) {
        return new RTCSettings(
            signalingLoopInterval,
            peerExpiration,
            delayedCandidatesInterval,
            roomLoopInterval,
            p2pAttemptTimeout,
            queuedSendTimeout,
            signalingAnnounceExpiration,
            maxDirectPeers,
            connectionRetryInitialDelay,
            connectionRetryMaxDelay,
            connectionRetryMultiplier,
            connectionRetryJitter,
            maxConcurrentConnectionAttempts,
            connectionMinimumLifetime,
            new ArrayList<String>(stunServers),
            signalingRelays,
            applicationId,
            protocolId
        );
    }

    /**
     * Returns a copy with the expiration time for signaling announcements changed.
     *
     * @param signalingAnnounceExpiration lifetime of a signaling announcement
     * @return a copy with the specified expiration time
     */
    public RTCSettings withSignalingAnnounceExpiration(Duration signalingAnnounceExpiration) {
        return new RTCSettings(
            signalingLoopInterval,
            peerExpiration,
            delayedCandidatesInterval,
            roomLoopInterval,
            p2pAttemptTimeout,
            queuedSendTimeout,
            signalingAnnounceExpiration,
            maxDirectPeers,
            connectionRetryInitialDelay,
            connectionRetryMaxDelay,
            connectionRetryMultiplier,
            connectionRetryJitter,
            maxConcurrentConnectionAttempts,
            connectionMinimumLifetime,
            new ArrayList<String>(stunServers),
            signalingRelays,
            applicationId,
            protocolId
        );
    }

    /**
     * Returns a copy with the timeout for considering a peer inactive changed.
     *
     * @param peerExpiration inactivity period after which a peer expires
     * @return a copy with the specified peer expiration period
     * @throws NullPointerException if {@code peerExpiration} is null
     * @throws IllegalArgumentException if {@code peerExpiration} is negative
     */
    public RTCSettings withPeerExpiration(Duration peerExpiration) {
        return new RTCSettings(
            signalingLoopInterval,
            peerExpiration,
            delayedCandidatesInterval,
            roomLoopInterval,
            p2pAttemptTimeout,
            queuedSendTimeout,
            signalingAnnounceExpiration,
            maxDirectPeers,
            connectionRetryInitialDelay,
            connectionRetryMaxDelay,
            connectionRetryMultiplier,
            connectionRetryJitter,
            maxConcurrentConnectionAttempts,
            connectionMinimumLifetime,
            new ArrayList<String>(stunServers),
            signalingRelays,
            applicationId,
            protocolId
        );
    }

    /**
     * Returns a copy with the interval used to process delayed ICE candidates changed.
     *
     * @param delayedCandidatesInterval interval between delayed-candidate processing passes
     * @return a copy with the specified interval
     */
    public RTCSettings withDelayedCandidatesInterval(Duration delayedCandidatesInterval) {
        return new RTCSettings(
            signalingLoopInterval,
            peerExpiration,
            delayedCandidatesInterval,
            roomLoopInterval,
            p2pAttemptTimeout,
            queuedSendTimeout,
            signalingAnnounceExpiration,
            maxDirectPeers,
            connectionRetryInitialDelay,
            connectionRetryMaxDelay,
            connectionRetryMultiplier,
            connectionRetryJitter,
            maxConcurrentConnectionAttempts,
            connectionMinimumLifetime,
            new ArrayList<String>(stunServers),
            signalingRelays,
            applicationId,
            protocolId
        );
    }

    /**
     * Returns a copy with the room maintenance loop interval changed.
     *
     * @param roomLoopInterval interval between room maintenance passes
     * @return a copy with the specified interval
     */
    public RTCSettings withRoomLoopInterval(Duration roomLoopInterval) {
        return new RTCSettings(
            signalingLoopInterval,
            peerExpiration,
            delayedCandidatesInterval,
            roomLoopInterval,
            p2pAttemptTimeout,
            queuedSendTimeout,
            signalingAnnounceExpiration,
            maxDirectPeers,
            connectionRetryInitialDelay,
            connectionRetryMaxDelay,
            connectionRetryMultiplier,
            connectionRetryJitter,
            maxConcurrentConnectionAttempts,
            connectionMinimumLifetime,
            new ArrayList<String>(stunServers),
            signalingRelays,
            applicationId,
            protocolId
        );
    }

    /**
     * Returns a copy with the timeout for an individual peer-to-peer connection attempt changed.
     * The give-up timeout is recalculated from this value.
     *
     * @param p2pAttemptTimeout maximum duration of one peer-to-peer connection attempt
     * @return a copy with the specified attempt timeout
     * @throws NullPointerException if {@code p2pAttemptTimeout} is null
     */
    public RTCSettings withP2pAttemptTimeout(Duration p2pAttemptTimeout) {
        return new RTCSettings(
            signalingLoopInterval,
            peerExpiration,
            delayedCandidatesInterval,
            roomLoopInterval,
            p2pAttemptTimeout,
            queuedSendTimeout,
            signalingAnnounceExpiration,
            maxDirectPeers,
            connectionRetryInitialDelay,
            connectionRetryMaxDelay,
            connectionRetryMultiplier,
            connectionRetryJitter,
            maxConcurrentConnectionAttempts,
            connectionMinimumLifetime,
            new ArrayList<String>(stunServers),
            signalingRelays,
            applicationId,
            protocolId
        );
    }

    /**
     * Returns a copy with the maximum time to wait for a queued send changed.
     *
     * @param queuedSendTimeout maximum wait for a queued send to be accepted for transmission
     * @return a copy with the specified queued-send timeout
     */
    public RTCSettings withQueuedSendTimeout(Duration queuedSendTimeout) {
        return new RTCSettings(
            signalingLoopInterval,
            peerExpiration,
            delayedCandidatesInterval,
            roomLoopInterval,
            p2pAttemptTimeout,
            queuedSendTimeout,
            signalingAnnounceExpiration,
            maxDirectPeers,
            connectionRetryInitialDelay,
            connectionRetryMaxDelay,
            connectionRetryMultiplier,
            connectionRetryJitter,
            maxConcurrentConnectionAttempts,
            connectionMinimumLifetime,
            new ArrayList<String>(stunServers),
            signalingRelays,
            applicationId,
            protocolId
        );
    }

    /**
     * Returns a copy with the maximum number of directly connected peers changed.
     *
     * @param maxDirectPeers maximum number of direct peers; must be between
     *        {@value #MIN_MAX_DIRECT_PEERS} and 64, inclusive
     * @return a copy with the specified direct-peer limit
     * @throws IllegalArgumentException if {@code maxDirectPeers} is outside the supported range
     */
    public RTCSettings withMaxDirectPeers(int maxDirectPeers) {
        return new RTCSettings(
            signalingLoopInterval,
            peerExpiration,
            delayedCandidatesInterval,
            roomLoopInterval,
            p2pAttemptTimeout,
            queuedSendTimeout,
            signalingAnnounceExpiration,
            maxDirectPeers,
            connectionRetryInitialDelay,
            connectionRetryMaxDelay,
            connectionRetryMultiplier,
            connectionRetryJitter,
            maxConcurrentConnectionAttempts,
            connectionMinimumLifetime,
            new ArrayList<String>(stunServers),
            signalingRelays,
            applicationId,
            protocolId
        );
    }

    /**
     * Returns a copy configured with the supplied STUN server addresses.
     * The collection is copied when the new settings are created.
     *
     * @param stunServers STUN server addresses, in {@code host:port} form
     * @return a copy configured with these STUN servers
     * @throws NullPointerException if {@code stunServers} or an entry is null
     * @throws IllegalArgumentException if an entry is blank
     */
    public RTCSettings withStunServers(Collection<String> stunServers) {
        return new RTCSettings(
            signalingLoopInterval,
            peerExpiration,
            delayedCandidatesInterval,
            roomLoopInterval,
            p2pAttemptTimeout,
            queuedSendTimeout,
            signalingAnnounceExpiration,
            maxDirectPeers,
            connectionRetryInitialDelay,
            connectionRetryMaxDelay,
            connectionRetryMultiplier,
            connectionRetryJitter,
            maxConcurrentConnectionAttempts,
            connectionMinimumLifetime,
            new ArrayList<String>(stunServers),
            signalingRelays,
            applicationId,
            protocolId
        );
    }

    /**
     * Returns a copy configured with the supplied Nostr signaling relay URLs.
     * The room ensures these URLs on its signaling pool when it is constructed;
     * relay connections proceed asynchronously. An empty collection configures
     * no additional relays, though the room can still use relays already in its pool.
     *
     * @param signalingRelays WebSocket relay URLs; entries must not be null or blank
     * @return a copy configured with these signaling relays
     * @throws NullPointerException if {@code signalingRelays} or an entry is null
     * @throws IllegalArgumentException if an entry is blank
     */
    public RTCSettings withSignalingRelays(Collection<String> signalingRelays) {
        return new RTCSettings(
            signalingLoopInterval,
            peerExpiration,
            delayedCandidatesInterval,
            roomLoopInterval,
            p2pAttemptTimeout,
            queuedSendTimeout,
            signalingAnnounceExpiration,
            maxDirectPeers,
            connectionRetryInitialDelay,
            connectionRetryMaxDelay,
            connectionRetryMultiplier,
            connectionRetryJitter,
            maxConcurrentConnectionAttempts,
            connectionMinimumLifetime,
            stunServers,
            signalingRelays,
            applicationId,
            protocolId
        );
    }

    /**
     * Returns a copy with the application and protocol identifiers replaced.
     * This internal helper does not validate identifiers; public callers must
     * validate them before invoking it.
     *
     * @param applicationId application namespace identifier
     * @param protocolId protocol namespace identifier
     * @return a copy with the specified identifiers
     */
    private RTCSettings withPeerConfiguration(String applicationId, String protocolId) {
        return new RTCSettings(
            signalingLoopInterval,
            peerExpiration,
            delayedCandidatesInterval,
            roomLoopInterval,
            p2pAttemptTimeout,
            queuedSendTimeout,
            signalingAnnounceExpiration,
            maxDirectPeers,
            connectionRetryInitialDelay,
            connectionRetryMaxDelay,
            connectionRetryMultiplier,
            connectionRetryJitter,
            maxConcurrentConnectionAttempts,
            connectionMinimumLifetime,
            stunServers,
            signalingRelays,
            applicationId,
            protocolId
        );
    }

    private static String requireNonBlank(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }

    /**
     * Returns a copy with the application identifier changed.
     *
     * @param applicationId nonblank application namespace identifier
     * @return a copy with the specified application identifier
     * @throws NullPointerException if {@code applicationId} is null
     * @throws IllegalArgumentException if {@code applicationId} is blank
     */
    public RTCSettings withApplicationId(String applicationId) {
        return withPeerConfiguration(requireNonBlank(applicationId, "applicationId"), protocolId);
    }

    /**
     * Returns a copy with the protocol identifier changed.
     *
     * @param protocolId nonblank protocol namespace identifier
     * @return a copy with the specified protocol identifier
     * @throws NullPointerException if {@code protocolId} is null
     * @throws IllegalArgumentException if {@code protocolId} is blank
     */
    public RTCSettings withProtocolId(String protocolId) {
        return withPeerConfiguration(applicationId, requireNonBlank(protocolId, "protocolId"));
    }

    /**
     * Returns a copy with one STUN server appended to the configured list.
     * Duplicate entries are preserved.
     *
     * @param stunServer STUN server address in {@code host:port} form
     * @return a copy with the server appended
     */
    public RTCSettings withStunServer(String stunServer) {
        ArrayList<String> servers = new ArrayList<String>(stunServers);
        servers.add(stunServer);
        return withStunServers(servers);
    }

    /**
     * Returns a copy with one matching STUN server removed from the configured list.
     * If the address occurs more than once, only its first occurrence is removed.
     *
     * @param stunServer STUN server address to remove
     * @return a copy with the first matching server removed, or an unchanged copy
     *         if the address is not configured
     */
    public RTCSettings withoutStunServer(String stunServer) {
        ArrayList<String> servers = new ArrayList<String>(stunServers);
        servers.remove(stunServer);
        return withStunServers(servers);
    }

    private static final RTCSettings DEFAULT = new RTCSettings(
        SIGNALING_LOOP_INTERVAL,
        PEER_EXPIRATION,
        DELAYED_CANDIDATES_INTERVAL,
        ROOM_LOOP_INTERVAL,
        P2P_TIMEOUT,
        QUEUED_SEND_TIMEOUT,
        SIGNALING_ANNOUNCE_EXPIRATION,
        DEFAULT_MAX_DIRECT_PEERS,
        Duration.ofMillis(250),
        Duration.ofSeconds(2),
        2f,
        0.1f,
        4,
        Duration.ofSeconds(30),
        PUBLIC_STUN_SERVERS,
        DEFAULT_SIGNALING_RELAYS,
        null,
        null
    );

    /**
     * Creates settings with the default RTC options and public signaling relays.
     *
     * @param applicationId nonblank application identifier shared by peers in the room
     * @param protocolId nonblank protocol identifier shared by peers in the room
     * @return a new settings instance for the specified application and protocol
     * @throws NullPointerException if either identifier is null
     * @throws IllegalArgumentException if either identifier is blank
     */
    public static RTCSettings getDefault(String applicationId, String protocolId) {
        return DEFAULT.withPeerConfiguration(
            requireNonBlank(applicationId, "applicationId"),
            requireNonBlank(protocolId, "protocolId")
        );
    }

    @Override
    public RTCSettings clone() {
        try {
            return (RTCSettings) super.clone();
        } catch (CloneNotSupportedException e) {
            throw new RuntimeException("Clone not supported", e);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof RTCSettings)) return false;
        RTCSettings that = (RTCSettings) o;
        return (
            Objects.equals(signalingLoopInterval, that.signalingLoopInterval) &&
            Objects.equals(signalingAnnounceExpiration, that.signalingAnnounceExpiration) &&
            Objects.equals(peerExpiration, that.peerExpiration) &&
            Objects.equals(delayedCandidatesInterval, that.delayedCandidatesInterval) &&
            Objects.equals(roomLoopInterval, that.roomLoopInterval) &&
            Objects.equals(p2pAttemptTimeout, that.p2pAttemptTimeout) &&
            Objects.equals(queuedSendTimeout, that.queuedSendTimeout) &&
            maxDirectPeers == that.maxDirectPeers &&
            Objects.equals(connectionRetryInitialDelay, that.connectionRetryInitialDelay) &&
            Objects.equals(connectionRetryMaxDelay, that.connectionRetryMaxDelay) &&
            Float.compare(connectionRetryMultiplier, that.connectionRetryMultiplier) == 0 &&
            Float.compare(connectionRetryJitter, that.connectionRetryJitter) == 0 &&
            maxConcurrentConnectionAttempts == that.maxConcurrentConnectionAttempts &&
            Objects.equals(connectionMinimumLifetime, that.connectionMinimumLifetime) &&
            Objects.equals(stunServers, that.stunServers) &&
            Objects.equals(signalingRelays, that.signalingRelays) &&
            Objects.equals(applicationId, that.applicationId) &&
            Objects.equals(protocolId, that.protocolId)
        );
    }

    @Override
    public int hashCode() {
        return Objects.hash(
            signalingLoopInterval,
            signalingAnnounceExpiration,
            peerExpiration,
            delayedCandidatesInterval,
            roomLoopInterval,
            p2pAttemptTimeout,
            queuedSendTimeout,
            maxDirectPeers,
            connectionRetryInitialDelay,
            connectionRetryMaxDelay,
            connectionRetryMultiplier,
            connectionRetryJitter,
            maxConcurrentConnectionAttempts,
            connectionMinimumLifetime,
            stunServers,
            signalingRelays,
            applicationId,
            protocolId
        );
    }

    @Override
    public String toString() {
        return (
            "RTCSettings{" +
            "announceInterval=" +
            signalingLoopInterval +
            ", announceExpiration=" +
            signalingAnnounceExpiration +
            ", peerExpiration=" +
            peerExpiration +
            ", delayedCandidatesInterval=" +
            delayedCandidatesInterval +
            ", roomLoopInterval=" +
            roomLoopInterval +
            ", p2pAttemptTimeout=" +
            p2pAttemptTimeout +
            ", queuedSendTimeout=" +
            queuedSendTimeout +
            ", maxDirectPeers=" +
            maxDirectPeers +
            ", connectionRetry=" +
            connectionRetryInitialDelay +
            "/" +
            connectionRetryMaxDelay +
            ", retryMultiplier=" +
            connectionRetryMultiplier +
            ", retryJitter=" +
            connectionRetryJitter +
            ", maxConcurrentConnectionAttempts=" +
            maxConcurrentConnectionAttempts +
            ", connectionMinimumLifetime=" +
            connectionMinimumLifetime +
            ", stunServers=" +
            stunServers +
            ", signalingRelays=" +
            signalingRelays +
            ", applicationId=" +
            applicationId +
            ", protocolId=" +
            protocolId +
            '}'
        );
    }
}
