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
package org.ngengine.nostr4j.rtc.signal;

import static org.ngengine.platform.NGEUtils.dbg;

import jakarta.annotation.Nullable;
import java.io.Closeable;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.ngengine.nostr4j.NostrFilter;
import org.ngengine.nostr4j.NostrPool;
import org.ngengine.nostr4j.NostrSubscription;
import org.ngengine.nostr4j.RTCSettings;
import org.ngengine.nostr4j.event.SignedNostrEvent;
import org.ngengine.nostr4j.keypair.NostrKeyPair;
import org.ngengine.nostr4j.keypair.NostrPublicKey;
import org.ngengine.nostr4j.listeners.sub.NostrSubEoseListener;
import org.ngengine.nostr4j.listeners.sub.NostrSubEventListener;
import org.ngengine.nostr4j.proto.NostrMessageAck;
import org.ngengine.nostr4j.signer.NostrKeyPairSigner;
import org.ngengine.platform.AsyncExecutor;
import org.ngengine.platform.AsyncTask;
import org.ngengine.platform.NGEPlatform;
import org.ngengine.platform.NGEUtils;
import org.ngengine.platform.transport.RTCTransportIceCandidate;

/**
 * Handles peer signaling
 */
public class NostrRTCSignaling implements Closeable {

    public static interface Listener {
        enum RemoveReason {
            EXPIRED,
            DISCONNECTED,
            UNKNOWN,
        }

        void onAddAnnounce(NostrRTCConnectSignal announce);

        void onUpdateAnnounce(NostrRTCConnectSignal announce);

        void onRemoveAnnounce(NostrRTCConnectSignal announce, RemoveReason reason);

        void onReceiveOffer(NostrRTCOfferSignal offer);

        void onReceiveAnswer(NostrRTCAnswerSignal answer);

        void onReceiveCandidates(NostrRTCRouteSignal candidate);
    }

    private static final Logger logger = Logger.getLogger(NostrRTCSignaling.class.getName());
    private static final int MAX_PRESENCE_PEERS = 2048;
    private static final int MAX_QUEUED_SIGNALS = 64;
    private final java.util.concurrent.atomic.AtomicInteger pendingSignals = new java.util.concurrent.atomic.AtomicInteger();
    private final NostrPool pool;
    private final Object presenceLock = new Object();
    private final Map<NostrRTCPeer, PresenceState> latestPresence = new LinkedHashMap<>();
    private volatile boolean storedPresenceReady;

    private static final class PresenceState {

        final Instant createdAt;
        final Instant expiresAt;
        final String id;
        final NostrRTCSignal signal;
        boolean pending;

        PresenceState(SignedNostrEvent event, NostrRTCSignal signal, boolean pending) {
            this.createdAt = event.getCreatedAt();
            this.expiresAt = event.getExpiration();
            this.id = event.getId();
            this.signal = signal;
            this.pending = pending;
        }

        boolean supersedes(PresenceState previous) {
            int time = createdAt.compareTo(previous.createdAt);
            if (time != 0) return time > 0;
            // Disconnect wins a same-second tie so a replay cannot resurrect a closed session.
            boolean disconnect = signal instanceof NostrRTCDisconnectSignal;
            boolean previousDisconnect = previous.signal instanceof NostrRTCDisconnectSignal;
            if (disconnect != previousDisconnect) return disconnect;
            return id.compareTo(previous.id) < 0;
        }
    }

    private final NostrRTCLocalPeer localPeer;
    private final List<NostrRTCConnectSignal> seenAnnounces = new CopyOnWriteArrayList<>();
    private final Collection<NostrRTCConnectSignal> seenAnnouncesRO = Collections.unmodifiableCollection(seenAnnounces);
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final RTCSettings settings;
    private final AsyncExecutor executor;
    private final NostrKeyPair roomKeyPair;
    private final NostrKeyPairSigner roomSigner;
    private final String appId;
    private final String protocolId;
    private final boolean strfryLimitWorkaround = true;

    private volatile boolean closed = false;
    private volatile boolean closeResourcesClosed = false;
    private volatile boolean loopStarted = false;
    private volatile NostrSubscription discoverySub;
    private volatile NostrSubscription signalingSub;
    private volatile String advMessage = "";
    private volatile AsyncTask<Void> disconnectPublishTask;

    private final NostrSubEventListener listener = new NostrSubEventListener() {
        @Override
        public void onSubEvent(NostrSubscription sub, SignedNostrEvent event, boolean stored) {
            NostrRTCSignaling.this.onSubEvent(event, stored);
        }
    };

    public NostrRTCSignaling(
        RTCSettings settings,
        String appId,
        String protocolId,
        NostrRTCLocalPeer localPeer,
        NostrKeyPair roomKeyPair,
        NostrPool pool
    ) {
        this.pool = Objects.requireNonNull(pool, "Pool cannot be null");
        this.localPeer = Objects.requireNonNull(localPeer, "Local peer cannot be null");
        this.settings = Objects.requireNonNull(settings, "Settings cannot be null");
        this.executor = NGEUtils.getPlatform().newAsyncExecutor(NostrRTCSignaling.class);
        this.roomKeyPair = Objects.requireNonNull(roomKeyPair, "Room key pair cannot be null");
        this.roomSigner = new NostrKeyPairSigner(roomKeyPair);
        this.appId = Objects.requireNonNull(appId, "App ID cannot be null");
        this.protocolId = Objects.requireNonNull(protocolId, "Protocol ID cannot be null");
    }

    public void setPublicAdvertiseMessage(String message) {
        this.advMessage = message;
    }

    public Collection<NostrRTCConnectSignal> getAnnounces() {
        return seenAnnouncesRO;
    }

    public NostrRTCSignaling addListener(Listener listener) {
        listeners.add(listener);
        return this;
    }

    public NostrRTCSignaling removeListener(Listener listener) {
        listeners.remove(listener);
        return this;
    }

    protected void onSubEvent(SignedNostrEvent event, boolean stored) {
        if (closed) return;
        String eventType = event.getFirstTagFirstValue("t");
        if (stored && !"connect".equals(eventType) && !"disconnect".equals(eventType)) return;
        if (!event.isCurrent()) return;
        if (event.getPubkey().equals(this.localPeer.getPubkey())) return;
        if (pendingSignals.incrementAndGet() > MAX_QUEUED_SIGNALS) {
            pendingSignals.decrementAndGet();
            return;
        }
        this.executor.run(() -> {
                try {
                    if (closed || !event.isCurrent() || !matchesScope(event)) {
                        return null;
                    }
                    String type = event.getFirstTagFirstValue("t");
                    if (type == null || type.isEmpty()) {
                        return null;
                    }

                    if ("connect".equals(type) || "disconnect".equals(type)) {
                        NostrRTCSignal presence = "connect".equals(type)
                            ? new NostrRTCConnectSignal(localPeer.getSigner(), roomKeyPair, event)
                            : new NostrRTCDisconnectSignal(localPeer.getSigner(), roomKeyPair, event);
                        acceptPresence(event, presence, stored);
                        return null;
                    }

                    // handle offers and routes
                    NGEPlatform platform = NGEUtils.getPlatform();
                    switch (type) {
                        case "offer":
                            {
                                if (!isDirectedToLocalPeer(event)) return null;
                                logger.finest("Received offer from: " + event.getPubkey());
                                NostrRTCOfferSignal offer = new NostrRTCOfferSignal(localPeer.getSigner(), roomKeyPair, event);
                                offer.await();
                                mergeAdvertisedVersion(offer);
                                for (Listener listener : listeners) {
                                    try {
                                        listener.onReceiveOffer(offer);
                                    } catch (Throwable e) {
                                        logger.log(Level.WARNING, "Error in onReceiveOffer", e);
                                    }
                                }
                                return null;
                            }
                        case "answer":
                            {
                                if (!isDirectedToLocalPeer(event)) return null;
                                logger.finest("Received answer from: " + event.getPubkey());
                                NostrRTCAnswerSignal answer = new NostrRTCAnswerSignal(
                                    localPeer.getSigner(),
                                    roomKeyPair,
                                    event
                                );
                                answer.await();
                                mergeAdvertisedVersion(answer);
                                for (Listener listener : listeners) {
                                    try {
                                        listener.onReceiveAnswer(answer);
                                    } catch (Throwable e) {
                                        logger.log(Level.WARNING, "Error in onReceiveAnswer", e);
                                    }
                                }
                                return null;
                            }
                        case "route":
                            {
                                if (!isDirectedToLocalPeer(event)) return null;
                                assert dbg(() -> logger.finest("Received candidate event from: " + event.getPubkey()));
                                NostrRTCRouteSignal route = new NostrRTCRouteSignal(localPeer.getSigner(), roomKeyPair, event);
                                route.await();
                                mergeAdvertisedVersion(route);
                                for (Listener listener : listeners) {
                                    try {
                                        listener.onReceiveCandidates(route);
                                    } catch (Throwable e) {
                                        logger.log(Level.WARNING, "Error in onReceiveCandidates", e);
                                    }
                                }
                                return null;
                            }
                    }
                    return null;
                } finally {
                    if (pendingSignals.decrementAndGet() == 0 && storedPresenceReady) flushStoredPresence();
                }
            })
            .catchException(error -> logger.fine("Ignoring invalid RTC signaling event"));
    }

    private void acceptPresence(SignedNostrEvent event, NostrRTCSignal signal, boolean stored) {
        synchronized (presenceLock) {
            if (closed || !event.isCurrent()) return;
            NostrRTCPeer peer = signal.getPeer();
            PresenceState next = new PresenceState(event, signal, stored);
            PresenceState previous = latestPresence.get(peer);
            if (previous != null && !next.supersedes(previous)) return;
            if (previous == null && latestPresence.size() >= MAX_PRESENCE_PEERS) {
                // Evict an inactive watermark rather than retaining unbounded session history.
                Set<NostrRTCPeer> active = new HashSet<>();
                for (NostrRTCConnectSignal announce : seenAnnounces) active.add(announce.getPeer());
                NostrRTCPeer inactive = latestPresence
                    .keySet()
                    .stream()
                    .filter(candidate -> !latestPresence.get(candidate).pending)
                    .filter(candidate -> !active.contains(candidate))
                    .findFirst()
                    .orElse(null);
                if (inactive == null) return;
                latestPresence.remove(inactive);
            }
            latestPresence.put(peer, next);
            if (!stored) {
                next.pending = false;
                applyPresence(signal);
            }
        }
    }

    /**
     * Finish a stored discovery batch; subsequent relay batches still cannot roll state backwards.
     */
    protected void onDiscoveryEose() {
        storedPresenceReady = true;
        flushStoredPresence();
    }

    private void flushStoredPresence() {
        synchronized (presenceLock) {
            if (closed || pendingSignals.get() != 0) return;
            Instant now = Instant.now();
            for (PresenceState state : new ArrayList<>(latestPresence.values())) {
                if (!state.pending) continue;
                state.pending = false;
                if (state.expiresAt.isBefore(now)) continue;
                applyPresence(state.signal);
            }
        }
    }

    private void applyPresence(NostrRTCSignal signal) {
        NostrRTCConnectSignal current = seenAnnounces
            .stream()
            .filter(a -> a.getPeer().equals(signal.getPeer()))
            .findFirst()
            .orElse(null);
        if (signal instanceof NostrRTCConnectSignal) {
            NostrRTCConnectSignal received = (NostrRTCConnectSignal) signal;
            if (current == null) {
                if (seenAnnounces.size() >= MAX_PRESENCE_PEERS) return;
                seenAnnounces.add(received);
                for (Listener listener : listeners) {
                    try {
                        listener.onAddAnnounce(received);
                    } catch (Throwable error) {
                        logger.log(Level.WARNING, "Error in onAddAnnounce", error);
                    }
                }
            } else {
                current.updateExpireAt(received.getExpireAt());
                current.getPeer().mergeAuthenticatedAnnouncement(received.getPeer());
                for (Listener listener : listeners) {
                    try {
                        listener.onUpdateAnnounce(current);
                    } catch (Throwable error) {
                        logger.log(Level.WARNING, "Error in onUpdateAnnounce", error);
                    }
                }
            }
        } else if (current != null) {
            seenAnnounces.remove(current);
            for (Listener listener : listeners) {
                try {
                    listener.onRemoveAnnounce(current, Listener.RemoveReason.DISCONNECTED);
                } catch (Throwable error) {
                    logger.log(Level.WARNING, "Error in onRemoveAnnounce", error);
                }
            }
        }
    }

    private void mergeAdvertisedVersion(NostrRTCSignal signal) {
        NostrRTCConnectSignal announcement = seenAnnounces
            .stream()
            .filter(candidate -> candidate.getPeer().equals(signal.getPeer()))
            .findFirst()
            .orElse(null);
        if (announcement != null) {
            signal.getPeer().mergeAuthenticatedAnnouncement(announcement.getPeer());
        }
    }

    public boolean isDiscoveryStarted() {
        return this.discoverySub != null;
    }

    public boolean isSignalingStarted() {
        return this.signalingSub != null;
    }

    public AsyncTask<Void> start(boolean signaling) {
        if (!this.isDiscoveryStarted()) {
            NostrFilter discoveryFilter = new NostrFilter()
                .withKind(25050)
                .withTag("t", "connect", "disconnect")
                .withTag("P", this.roomKeyPair.getPublicKey().asHex())
                .limit(MAX_PRESENCE_PEERS);
            if (!this.strfryLimitWorkaround) {
                discoveryFilter = discoveryFilter.withTag("i", this.protocolId).withTag("y", this.appId);
            }
            this.discoverySub = // listen for connect and disconnect events directed to the room
                this.pool.subscribe(discoveryFilter);
            this.discoverySub.addEventListener(listener);
            this.discoverySub.addListener((NostrSubEoseListener) (sub, relay, everywhere) -> onDiscoveryEose());
            this.discoverySub.open();
        }

        if (!this.isSignalingStarted() && signaling) {
            NostrPublicKey localpk = this.localPeer.getPubkey();
            NostrFilter signalingFilter = new NostrFilter()
                .withKind(25050)
                .withTag("t", "offer", "answer", "route")
                .withTag("P", this.roomKeyPair.getPublicKey().asHex())
                .withTag("p", localpk.asHex())
                .since(Instant.now().minus(1, ChronoUnit.SECONDS)) // only listen for new events
                .limit(1);
            if (!this.strfryLimitWorkaround) {
                signalingFilter = signalingFilter.withTag("i", this.protocolId).withTag("y", this.appId);
            }
            this.signalingSub = // listen for offers, answers and candidates directed to the local peer
                this.pool.subscribe(signalingFilter);
            this.signalingSub.addEventListener(listener);
            this.signalingSub.open();
        }

        NGEPlatform platform = NGEUtils.getPlatform();

        logger.finest("Opened subscriptions");
        if (!loopStarted) {
            loopStarted = true;
            this.loop();
        }
        return platform.wrapPromise((res, rej) -> {
            res.accept(null);
        });
    }

    private void loop() {
        this.executor.runLater(
                () -> {
                    if (closed) return null;
                    // periodically resend announce
                    try {
                        if (isSignalingStarted()) {
                            this.sendAnnounce(advMessage).await();
                        }
                    } catch (Exception e) {
                        logger.log(Level.WARNING, "Error in loop", e);
                    }

                    synchronized (presenceLock) {
                        // remove all expired announce
                        Instant now = Instant.now();
                        for (NostrRTCConnectSignal announce : seenAnnounces) {
                            if (announce.getExpireAt().isBefore(now)) {
                                seenAnnounces.remove(announce);
                                for (Listener listener : listeners) {
                                    try {
                                        listener.onRemoveAnnounce(announce, Listener.RemoveReason.EXPIRED);
                                    } catch (Throwable e) {
                                        logger.log(Level.WARNING, "Error in onRemoveAnnounce", e);
                                    }
                                }
                            }
                        }
                    }
                    this.loop();
                    return null;
                },
                settings.getSignalingLoopInterval().toMillis(),
                TimeUnit.MILLISECONDS
            );
    }

    public AsyncTask<List<AsyncTask<NostrMessageAck>>> sendAnnounce(String message) {
        if (this.closed) throw new IllegalStateException("Already closed");
        if (!this.isSignalingStarted()) throw new IllegalStateException("Signaling not started");
        NostrRTCConnectSignal signal = new NostrRTCConnectSignal(
            localPeer.getSigner(),
            roomKeyPair,
            localPeer,
            Instant.now().plus(settings.getSignalingAnnounceExpiration()),
            message
        );
        return signal
            .toEvent(null)
            .compose(ev -> {
                return pool.publish(ev);
            });
    }

    /**
     * Send a connection offer to a peer
     * @param offer the offer
     * @param recipient the recipient peer
     * @return the async task that completes when the pool acknowledgement policy succeeds
     
     */
    public AsyncTask<List<AsyncTask<NostrMessageAck>>> sendOffer(String offer, NostrPublicKey recipient) {
        if (this.closed) throw new IllegalStateException("Already closed");
        if (!this.isSignalingStarted()) throw new IllegalStateException("Signaling not started");

        NostrRTCOfferSignal signal = new NostrRTCOfferSignal(localPeer.getSigner(), roomKeyPair, localPeer, offer);

        return signal
            .toEvent(recipient)
            .compose(ev -> {
                return pool.publish(ev);
            });
    }

    /**
     * Send an answer to a peer
     * @return the async task that completes when the pool acknowledgement policy succeeds
     */
    public AsyncTask<List<AsyncTask<NostrMessageAck>>> sendAnswer(String sdp, NostrPublicKey recipient) {
        if (this.closed) throw new IllegalStateException("Already closed");
        if (!this.isSignalingStarted()) throw new IllegalStateException("Signaling not started");

        NostrRTCAnswerSignal signal = new NostrRTCAnswerSignal(localPeer.getSigner(), roomKeyPair, localPeer, sdp);

        return signal
            .toEvent(recipient)
            .compose(ev -> {
                return pool.publish(ev);
            });
    }

    /**
     * Send a candidate to a peer
     * @return the async task that completes when the pool acknowledgement policy succeeds
     */
    public AsyncTask<List<AsyncTask<NostrMessageAck>>> sendRoutes(
        Collection<RTCTransportIceCandidate> candidates,
        @Nullable String turnServer,
        NostrPublicKey recipient
    ) {
        if (this.closed) throw new IllegalStateException("Already closed");
        if (!this.isSignalingStarted()) throw new IllegalStateException("Signaling not started");

        NostrRTCRouteSignal signal = new NostrRTCRouteSignal(
            localPeer.getSigner(),
            roomKeyPair,
            localPeer,
            candidates,
            turnServer
        );

        return signal
            .toEvent(recipient)
            .compose(ev -> {
                return pool.publish(ev);
            });
    }

    /**
     * Recheck the attempt after asynchronous signing, before handing a signal to the relay pool.
     */
    public AsyncTask<List<AsyncTask<NostrMessageAck>>> sendBoundSignal(
        NostrRTCSignal signal,
        NostrPublicKey recipient,
        BooleanSupplier active
    ) {
        if (closed || !isSignalingStarted() || !active.getAsBoolean()) {
            return AsyncTask.failed(new IllegalStateException("Inactive signaling attempt"));
        }
        return signal
            .toEvent(recipient)
            .compose(event -> {
                if (closed || !active.getAsBoolean()) return AsyncTask.failed(
                    new IllegalStateException("Obsolete signaling attempt")
                );
                return pool.publish(event);
            });
    }

    public void close() {
        close("Closed by peer");
    }

    /**
     * Close the signaling.
     *
     * <p>The disconnect event must be signed and handed to the relay pool before
     * subscriptions and the signaling executor are closed. In particular, signing
     * may be asynchronous on browser and remote-signer platforms. Relay
     * acknowledgements are deliberately not awaited.</p>
     */
    public void close(String message) {
        AsyncTask<Void> publishTask;
        synchronized (this) {
            if (disconnectPublishTask == null) {
                logger.fine("Closing signaling");
                this.closed = true;
                NostrRTCDisconnectSignal signal = new NostrRTCDisconnectSignal(
                    localPeer.getSigner(),
                    roomKeyPair,
                    localPeer,
                    message
                );
                disconnectPublishTask =
                    signal
                        .toEvent(null)
                        .then(event -> {
                            pool
                                .publish(event)
                                .catchException(error ->
                                    logger.log(Level.WARNING, "Relay rejected RTC disconnect signal", error)
                                );
                            return null;
                        });
            }
            publishTask = disconnectPublishTask;
        }

        try {
            publishTask.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.log(Level.WARNING, "Interrupted while publishing RTC disconnect signal", e);
        } catch (Exception e) {
            logger.log(Level.WARNING, "Could not publish RTC disconnect signal", e);
        } finally {
            closeResources();
        }
    }

    private synchronized void closeResources() {
        if (closeResourcesClosed) {
            return;
        }
        closeResourcesClosed = true;
        if (isDiscoveryStarted()) this.discoverySub.close();
        if (isSignalingStarted()) this.signalingSub.close();
        synchronized (presenceLock) {
            latestPresence.clear();
        }
        this.executor.close();
    }

    private boolean matchesScope(SignedNostrEvent event) {
        String roomPubkey = this.roomKeyPair.getPublicKey().asHex();
        return (
            roomPubkey.equals(event.getFirstTagFirstValue("P")) &&
            this.protocolId.equals(event.getFirstTagFirstValue("i")) &&
            this.appId.equals(event.getFirstTagFirstValue("y"))
        );
    }

    private boolean isDirectedToLocalPeer(SignedNostrEvent event) {
        return this.localPeer.getPubkey().asHex().equals(event.getFirstTagFirstValue("p"));
    }
}
