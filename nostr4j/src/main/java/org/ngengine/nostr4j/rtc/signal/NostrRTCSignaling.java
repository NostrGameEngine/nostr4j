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

import java.io.Closeable;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.ngengine.nostr4j.NostrFilter;
import org.ngengine.nostr4j.NostrPool;
import org.ngengine.nostr4j.NostrSubscription;
import org.ngengine.nostr4j.event.SignedNostrEvent;
import org.ngengine.nostr4j.event.UnsignedNostrEvent;
import org.ngengine.nostr4j.keypair.NostrKeyPair;
import org.ngengine.nostr4j.keypair.NostrPublicKey;
import org.ngengine.nostr4j.listeners.sub.NostrSubEventListener;
import org.ngengine.nostr4j.proto.NostrMessageAck;
import org.ngengine.nostr4j.signer.NostrKeyPairSigner;
import org.ngengine.platform.AsyncExecutor;
import org.ngengine.platform.AsyncTask;
import org.ngengine.platform.NGEPlatform;
import org.ngengine.platform.NGEUtils;
import org.ngengine.platform.RTCSettings;

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

        void onAddAnnounce(NostrRTCAnnounce announce);

        void onUpdateAnnounce(NostrRTCAnnounce announce);

        void onRemoveAnnounce(NostrRTCAnnounce announce, RemoveReason reason);

        void onReceiveOffer(NostrRTCOffer offer);

        void onReceiveAnswer(NostrRTCAnswer answer);

        void onReceiveCandidates(NostrRTCIceCandidate candidate);
    }

    private static final Logger logger = Logger.getLogger(NostrRTCSignaling.class.getName());
    private final NostrPool pool;

    private final NostrRTCLocalPeer localPeer;
    private final Queue<NostrRTCAnnounce> seenAnnounces = NGEUtils.getPlatform().newConcurrentQueue(NostrRTCAnnounce.class);
    private final Collection<NostrRTCAnnounce> seenAnnouncesRO = Collections.unmodifiableCollection(seenAnnounces);
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final RTCSettings settings;
    private final AsyncExecutor executor;
    private final NostrKeyPair roomKeyPair;
    private final NostrKeyPairSigner roomSigner;

    private volatile boolean closed = false;
    private volatile boolean loopStarted = false;
    private volatile NostrSubscription discoverySub;
    private volatile NostrSubscription signalingSub;

    private final NostrSubEventListener listener = new NostrSubEventListener() {
        @Override
        public void onSubEvent(SignedNostrEvent event, boolean stored) {
            NostrRTCSignaling.this.onSubEvent(event, stored);
        }
    };

    public NostrRTCSignaling(RTCSettings settings, NostrRTCLocalPeer localPeer, NostrKeyPair roomKeyPair, NostrPool pool) {
        this.pool = pool;
        this.localPeer = localPeer;
        this.settings = settings;
        this.executor = NGEUtils.getPlatform().newPoolExecutor();
        this.roomKeyPair = roomKeyPair;
        this.roomSigner = new NostrKeyPairSigner(roomKeyPair);
    }

    public Collection<NostrRTCAnnounce> getAnnounces() {
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
        if (event.getPubkey().equals(this.localPeer.getPubkey())) return;
        this.executor.run(() -> {
                String type = event.getFirstTag("t").get(0);
                switch (type) {
                    case "connect":
                        {
                            NostrRTCAnnounce ann = seenAnnounces
                                .stream()
                                .filter(a -> a.getPubkey().equals(event.getPubkey()))
                                .findFirst()
                                .orElse(null);
                            if (ann == null) {
                                NostrRTCAnnounce a = new NostrRTCAnnounce(
                                    event.getPubkey(),
                                    event.getExpiration(),
                                    localPeer.getPublicMisc()
                                );
                                for (Listener listener : listeners) {
                                    try {
                                        listener.onAddAnnounce(a);
                                    } catch (Exception e) {
                                        logger.log(Level.WARNING, "Error in onAddAnnounce", e);
                                    }
                                }
                                seenAnnounces.add(a);
                            } else {
                                assert dbg(() -> logger.finest("Update announce: " + event.getPubkey()));
                                ann.updateExpireAt(event.getExpiration());
                                for (Listener listener : listeners) {
                                    try {
                                        listener.onUpdateAnnounce(ann);
                                    } catch (Exception e) {
                                        logger.log(Level.WARNING, "Error in onUpdateAnnounce", e);
                                    }
                                }
                            }
                            return null;
                        }
                    case "disconnect":
                        {
                            logger.finest("Received disconnect event: " + event.getPubkey());
                            Iterator<NostrRTCAnnounce> it = seenAnnounces.iterator();
                            while (it.hasNext()) {
                                NostrRTCAnnounce announce = it.next();
                                if (!announce.getPubkey().equals(event.getPubkey())) continue;
                                it.remove();
                                logger.finest("Remove announce: " + event.getPubkey());
                                for (Listener listener : listeners) {
                                    try {
                                        listener.onRemoveAnnounce(announce, Listener.RemoveReason.DISCONNECTED);
                                    } catch (Exception e) {
                                        logger.log(Level.WARNING, "Error in onRemoveAnnounce", e);
                                    }
                                }
                            }
                            return null;
                        }
                }

                NGEPlatform platform = NGEUtils.getPlatform();
                decrypt(event.getContent(), event.getPubkey())
                    .catchException(exc -> {
                        logger.warning("Error decrypting event: " + exc.getMessage());
                    })
                    .then(decryptedContent -> {
                        try {
                            Map<String, Object> content = platform.fromJSON(decryptedContent, Map.class);
                            switch (type) {
                                case "offer":
                                    {
                                        logger.finest("Received offer from: " + event.getPubkey());
                                        NostrRTCOffer offer = new NostrRTCOffer(event.getPubkey(), content);
                                        for (Listener listener : listeners) {
                                            try {
                                                listener.onReceiveOffer(offer);
                                            } catch (Exception e) {
                                                logger.log(Level.WARNING, "Error in onReceiveOffer", e);
                                            }
                                        }
                                        return null;
                                    }
                                case "answer":
                                    {
                                        logger.finest("Received answer from: " + event.getPubkey());
                                        NostrRTCAnswer answer = new NostrRTCAnswer(event.getPubkey(), content);
                                        for (Listener listener : listeners) {
                                            try {
                                                listener.onReceiveAnswer(answer);
                                            } catch (Exception e) {
                                                logger.log(Level.WARNING, "Error in onReceiveAnswer", e);
                                            }
                                        }
                                        return null;
                                    }
                                case "candidate":
                                    {
                                        assert dbg(() -> logger.finest("Received candidate event from: " + event.getPubkey()));
                                        NostrRTCIceCandidate candidate = new NostrRTCIceCandidate(event.getPubkey(), content);
                                        for (Listener listener : listeners) {
                                            try {
                                                listener.onReceiveCandidates(candidate);
                                            } catch (Exception e) {
                                                logger.log(Level.WARNING, "Error in onReceiveCandidates", e);
                                            }
                                        }
                                        return null;
                                    }
                            }
                            logger.warning("Unknown event type: " + type);
                            return null;
                        } catch (Exception e) {
                            logger.log(Level.WARNING, "Error processing event", e);
                            return null;
                        }
                    });
                return null;
            });
    }

    public boolean isDiscoveryStarted() {
        return this.discoverySub != null;
    }

    public boolean isSignalingStarted() {
        return this.signalingSub != null;
    }

    public AsyncTask<Void> start(boolean signaling) {
        List<AsyncTask<List<AsyncTask<NostrMessageAck>>>> waitQueue = new ArrayList<>();
        if (!this.isDiscoveryStarted()) {
            this.discoverySub = // listen for connect and disconnect events directed to the room
                this.pool.subscribe(
                        new NostrFilter()
                            .withKind(25050)
                            .withTag("r", this.roomKeyPair.getPublicKey().asHex())
                            .withTag("t", "connect", "disconnect")
                            .limit(0)
                    );
            this.discoverySub.addEventListener(listener);
            waitQueue.add(this.discoverySub.open());
        }

        if (!this.isSignalingStarted() && signaling) {
            NostrPublicKey localpk = this.localPeer.getPubkey();
            this.signalingSub = // listen for offers, answers and candidates directed to the local peer
                this.pool.subscribe(
                        new NostrFilter()
                            .withKind(25050)
                            .withTag("r", this.roomKeyPair.getPublicKey().asHex())
                            .withTag("t", "offer", "answer", "candidate")
                            .withTag("p", localpk.asHex())
                            .limit(0)
                    );
            this.signalingSub.addEventListener(listener);
            waitQueue.add(this.signalingSub.open());
        }

        NGEPlatform platform = NGEUtils.getPlatform();

        return platform
            .awaitAll(waitQueue)
            .compose(acks -> {
                logger.finest("Opened subscriptions: " + acks);
                if (!loopStarted) {
                    loopStarted = true;
                    this.loop();
                }
                return platform.wrapPromise((res, rej) -> {
                    res.accept(null);
                });
            });
    }

    private void loop() {
        this.executor.runLater(
                () -> {
                    // periodically resend announce
                    try {
                        if (isSignalingStarted()) {
                            this.sendAnnounce().await();
                        }
                    } catch (Exception e) {
                        logger.log(Level.WARNING, "Error in loop", e);
                    }

                    // remove all expired announce
                    Instant now = Instant.now();
                    Iterator<NostrRTCAnnounce> it = seenAnnounces.iterator();
                    while (it.hasNext()) {
                        NostrRTCAnnounce announce = it.next();
                        if (announce.getExpireAt().isBefore(now)) {
                            it.remove();
                            for (Listener listener : listeners) {
                                try {
                                    listener.onRemoveAnnounce(announce, Listener.RemoveReason.EXPIRED);
                                } catch (Exception e) {
                                    logger.log(Level.WARNING, "Error in onRemoveAnnounce", e);
                                }
                            }
                        }
                    }
                    if (closed) return null;
                    this.loop();
                    return null;
                },
                settings.getSignalingLoopInterval().toMillis(),
                TimeUnit.MILLISECONDS
            );
    }

    public AsyncTask<List<AsyncTask<NostrMessageAck>>> sendAnnounce() {
        if (this.closed) throw new IllegalStateException("Already closed");
        if (!this.isSignalingStarted()) throw new IllegalStateException("Signaling not started");
        UnsignedNostrEvent connectEvent = new UnsignedNostrEvent();
        connectEvent.withKind(25050);
        connectEvent.createdAt(Instant.now());
        connectEvent.withTag("r", this.roomKeyPair.getPublicKey().asHex());
        connectEvent.withTag("t", "connect");
        connectEvent.withTag("expiration", String.valueOf(Instant.now().plusSeconds(60).getEpochSecond()));
        // logger.fine("Sending announce: " + connectEvent);
        return this.localPeer.getSigner()
            .sign(connectEvent)
            .compose(ev -> {
                return pool.publish(ev);
            });
    }

    /**
     * Send a connection offer to a peer
     * @param offer the offer
     * @param recipient the recipient peer
     * @return the async task that will be completed when the message is sent
     
     */
    public AsyncTask<List<AsyncTask<NostrMessageAck>>> sendOffer(NostrRTCOffer offer, NostrPublicKey recipient) {
        if (this.closed) throw new IllegalStateException("Already closed");
        if (!this.isSignalingStarted()) throw new IllegalStateException("Signaling not started");

        UnsignedNostrEvent event = new UnsignedNostrEvent();
        event.withKind(25050);
        event.createdAt(Instant.now());
        event.withTag("r", this.roomKeyPair.getPublicKey().asHex());
        event.withTag("t", "offer");
        event.withTag("p", recipient.asHex());

        NGEPlatform platform = NGEUtils.getPlatform();
        Map<String, Object> content = offer.get();

        String json = platform.toJSON(content);

        return encrypt(json, recipient)
            .compose(encContent -> {
                event.withContent(encContent);
                logger.finest("Sending offer: " + event + " " + content + " to " + recipient);

                return this.localPeer.getSigner()
                    .sign(event)
                    .compose(ev -> {
                        return pool.publish(ev);
                    });
            });
    }

    /**
     * Send an answer to a peer
     * @param answer the answer
     * @param recipient the recipient peer
     * @return the async task that will be completed when the message is sent
     */
    public AsyncTask<List<AsyncTask<NostrMessageAck>>> sendAnswer(NostrRTCAnswer answer, NostrPublicKey recipient) {
        if (this.closed) throw new IllegalStateException("Already closed");
        if (!this.isSignalingStarted()) throw new IllegalStateException("Signaling not started");
        UnsignedNostrEvent event = new UnsignedNostrEvent();
        event.withKind(25050);
        event.createdAt(Instant.now());
        event.withTag("r", this.roomKeyPair.getPublicKey().asHex());
        event.withTag("t", "answer");
        event.withTag("p", recipient.asHex());

        NGEPlatform platform = NGEUtils.getPlatform();
        Map<String, Object> content = answer.get();

        String json = platform.toJSON(content);

        return encrypt(json, recipient)
            .compose(encContent -> {
                event.withContent(encContent);
                logger.finest("Sending answer: " + event + " " + content + " to " + recipient);

                return this.localPeer.getSigner()
                    .sign(event)
                    .compose(ev -> {
                        return pool.publish(ev);
                    });
            });
    }

    /**
     * Send a candidate to a peer
     * @param candidate the candidate
     * @param recipient the recipient peer
     * @return the async task that will be completed when the message is sent
     */
    public AsyncTask<List<AsyncTask<NostrMessageAck>>> sendCandidates(
        NostrRTCIceCandidate candidate,
        NostrPublicKey recipient
    ) {
        if (this.closed) throw new IllegalStateException("Already closed");
        if (!this.isSignalingStarted()) throw new IllegalStateException("Signaling not started");

        UnsignedNostrEvent event = new UnsignedNostrEvent();
        event.withKind(25050);
        event.createdAt(Instant.now());
        event.withTag("r", this.roomKeyPair.getPublicKey().asHex());
        event.withTag("t", "candidate");
        event.withTag("p", recipient.asHex());

        NGEPlatform platform = NGEUtils.getPlatform();
        Map<String, Object> content = candidate.get();
        String json = platform.toJSON(content);
        return encrypt(json, recipient)
            .compose(encContent -> {
                event.withContent(encContent);
                logger.finest("Sending candidates: " + event + " " + content + " to " + recipient);

                return this.localPeer.getSigner()
                    .sign(event)
                    .compose(ev -> {
                        return pool.publish(ev);
                    });
            });
    }

    /**
     * Close the signaling
     */
    public void close() {
        if (this.closed) throw new IllegalStateException("Already closed");
        logger.fine("Closing signaling");
        this.closed = true;

        UnsignedNostrEvent connectEvent = new UnsignedNostrEvent();
        connectEvent.withKind(25050);
        connectEvent.createdAt(Instant.now());
        connectEvent.withTag("r", this.roomKeyPair.getPublicKey().asHex());
        connectEvent.withTag("t", "disconnect");
        this.localPeer.getSigner()
            .sign(connectEvent)
            .compose(ev -> {
                return pool.publish(ev);
            });

        if (isDiscoveryStarted()) this.discoverySub.close();
        if (isSignalingStarted()) this.signalingSub.close();
        this.executor.close();
    }

    // internal encryption -> encrypts twice: for the peer and the room
    private AsyncTask<String> encrypt(String content, NostrPublicKey recipient) {
        AsyncTask<String> out =
            this.localPeer.getSigner()
                .encrypt(content, recipient)
                .compose(enc -> {
                    return roomSigner.encrypt(enc, recipient);
                });
        return out;
    }

    // internal decryption -> decrypts twice: for the room and the peer
    private AsyncTask<String> decrypt(String content, NostrPublicKey sender) {
        AsyncTask<String> out =
            this.localPeer.getSigner()
                .decrypt(content, roomKeyPair.getPublicKey())
                .compose(enc -> {
                    return this.localPeer.getSigner().decrypt(enc, sender);
                });
        return out;
    }
}
