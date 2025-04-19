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
import org.ngengine.nostr4j.platform.AsyncTask;
import org.ngengine.nostr4j.platform.NostrExecutor;
import org.ngengine.nostr4j.platform.Platform;
import org.ngengine.nostr4j.rtc.NostrRTCSettings;
import org.ngengine.nostr4j.signer.NostrKeyPairSigner;
import org.ngengine.nostr4j.transport.NostrMessageAck;
import org.ngengine.nostr4j.utils.NostrUtils;
import static org.ngengine.nostr4j.utils.NostrUtils.dbg;

/**
 * Handles peer signaling 
 */
public class NostrRTCSignaling implements NostrSubEventListener, Closeable {

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
    private final NostrKeyPair roomKeyPair;
    private final NostrRTCLocalPeer localPeer;
    private final NostrKeyPairSigner roomSigner;
    private final Queue<NostrRTCAnnounce> seenAnnounces = NostrUtils.getPlatform().newConcurrentQueue(NostrRTCAnnounce.class);
    private final Collection<NostrRTCAnnounce> seenAnnouncesRO = Collections.unmodifiableCollection(seenAnnounces);
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final NostrRTCSettings settings;
    private final NostrExecutor executor;

    private volatile boolean closed = false;
    private NostrSubscription sub1;
    private NostrSubscription sub2;

    public NostrRTCSignaling(NostrRTCSettings settings, NostrRTCLocalPeer localPeer, NostrKeyPair roomKeyPair, NostrPool pool) {
        this.pool = pool;
        this.roomKeyPair = roomKeyPair;
        this.roomSigner = new NostrKeyPairSigner(roomKeyPair);
        this.localPeer = localPeer;
        this.settings = settings;
        this.executor = NostrUtils.getPlatform().newPoolExecutor();
    }

    public Collection<NostrRTCAnnounce> getAnnounces() {
        return seenAnnouncesRO;
    }

    public void addListener(Listener listener) {
        listeners.add(listener);
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    // internal- runs in executor thread
    @Override
    public void onSubEvent(SignedNostrEvent event, boolean stored) {
        if (closed) return;
        if (event.getPubkey().equals(this.localPeer.getPubkey())) return;
        this.executor.run(() -> {
                String type = event.getTag("t")[1];
                switch (type) {
                    case "connect":
                        {
                            NostrRTCAnnounce ann = seenAnnounces
                                .stream()
                                .filter(a -> a.getPubkey().equals(event.getPubkey()))
                                .findFirst()
                                .orElse(null);
                            if (ann == null) {
                                NostrRTCAnnounce a = new NostrRTCAnnounce(event.getPubkey(), event.getExpirationTimestamp());
                                for (Listener listener : listeners) {
                                    listener.onAddAnnounce(a);
                                }
                                seenAnnounces.add(a);
                            } else {
                                assert dbg(()->logger.finest("Update announce: " + event.getPubkey()));
                                ann.updateExpireAt(event.getExpirationTimestamp());
                                for (Listener listener : listeners) {
                                    listener.onUpdateAnnounce(ann);
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
                                    listener.onRemoveAnnounce(announce, Listener.RemoveReason.DISCONNECTED);
                                }
                            }
                            return null;
                        }
                }

                Platform platform = NostrUtils.getPlatform();
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
                                            listener.onReceiveOffer(offer);
                                        }                                        
                                        return null;
                                    }
                                case "answer":
                                    {
                                        logger.finest("Received answer from: " + event.getPubkey());                                     
                                        NostrRTCAnswer answer = new NostrRTCAnswer(event.getPubkey(), content);
                                        for (Listener listener : listeners) {
                                            listener.onReceiveAnswer(answer);
                                        }
                                        return null;
                                    }
                                case "candidate":
                                    {
                                        assert dbg(()->logger.finest("Received candidate event from: " + event.getPubkey()));                                     
                                        NostrRTCIceCandidate candidate = new NostrRTCIceCandidate(event.getPubkey(), content);
                                        for (Listener listener : listeners) {
                                            listener.onReceiveCandidates(candidate);
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

    public AsyncTask<Void> start() {
        this.closed = false;
        Platform platform = NostrUtils.getPlatform();
        NostrPublicKey localpk = this.localPeer.getPubkey();
        this.sub1 =
            this.pool.subscribe(
                    new NostrFilter().kind(25050).tag("r", roomKeyPair.getPublicKey().asHex()).tag("t", "connect", "disconnect")
                );
        this.sub2 =
            this.pool.subscribe(
                    new NostrFilter()
                        .kind(25050)
                        .tag("r", roomKeyPair.getPublicKey().asHex())
                        .tag("t", "offer", "answer", "candidate")
                        .tag("p", localpk.asHex())
                );
        this.sub1.listenEvent(this);
        this.sub2.listenEvent(this);
        AsyncTask<List<NostrMessageAck>> open1 = this.sub1.open();
        AsyncTask<List<NostrMessageAck>> open2 = this.sub2.open();
        return platform
            .awaitAll(List.of(open1, open2))
            .compose(acks -> {
                logger.finest("Opened subscriptions: " + acks);
                this.loop();
                return platform.wrapPromise((res, rej) -> {
                    res.accept(null);
                });
            });
    }

    // internal- runs in executor thread
    private void loop() {
        this.executor.runLater(
                () -> {
                    try {
                        this.sendAnnounce().await();
                    } catch (Exception e) {
                        logger.log(Level.WARNING,"Error in loop", e);
                    }

                    // remove all expired announces
                    Instant now = Instant.now();
                    Iterator<NostrRTCAnnounce> it = seenAnnounces.iterator();
                    while (it.hasNext()) {
                        NostrRTCAnnounce announce = it.next();
                        if (announce.getExpireAt().isBefore(now)) {
                            it.remove();
                            for (Listener listener : listeners) {
                                listener.onRemoveAnnounce(announce, Listener.RemoveReason.EXPIRED);
                            }
                        }
                    }
                    if (closed) return null;
                    this.loop();
                    return null;
                },
                settings.getAnnounceInterval().toMillis(),
                TimeUnit.MILLISECONDS
            );
    }

    public AsyncTask<List<NostrMessageAck>> sendAnnounce() {
        AsyncTask<List<NostrMessageAck>> out = null;
        UnsignedNostrEvent connectEvent = new UnsignedNostrEvent();
        connectEvent.setKind(25050);
        connectEvent.setCreatedAt(Instant.now());
        connectEvent.setTag("r", this.roomKeyPair.getPublicKey().asHex());
        connectEvent.setTag("t", "connect");
        connectEvent.setTag("expiration", String.valueOf(Instant.now().plusSeconds(60).getEpochSecond()));
        // logger.fine("Sending announce: " + connectEvent);
        out =
            this.localPeer.getSigner()
                .sign(connectEvent)
                .compose(ev -> {
                    return pool.send(ev);
                });
        return out;
    }

    public AsyncTask<List<NostrMessageAck>> sendOffer(NostrRTCOffer offer, NostrPublicKey recipient) throws Exception {
        if (this.closed) throw new IllegalStateException("Already closed");

        UnsignedNostrEvent event = new UnsignedNostrEvent();
        event.setKind(25050);
        event.setCreatedAt(Instant.now());
        event.setTag("r", this.roomKeyPair.getPublicKey().asHex());
        event.setTag("t", "offer");
        event.setTag("p", recipient.asHex());

        Platform platform = NostrUtils.getPlatform();
        Map<String, Object> content = offer.get();

        String json = platform.toJSON(content);

        return encrypt(json, recipient)
            .compose(encContent -> {
                event.setContent(encContent);
                logger.finest("Sending offer: " + event + " " + content + " to " + recipient);

                return this.localPeer.getSigner()
                    .sign(event)
                    .compose(ev -> {
                        return pool.send(ev);
                    });
            });
    }

    public AsyncTask<List<NostrMessageAck>> sendAnswer(NostrRTCAnswer answer, NostrPublicKey recipient) throws Exception {
        if (this.closed) throw new IllegalStateException("Already closed");

        UnsignedNostrEvent event = new UnsignedNostrEvent();
        event.setKind(25050);
        event.setCreatedAt(Instant.now());
        event.setTag("r", this.roomKeyPair.getPublicKey().asHex());
        event.setTag("t", "answer");
        event.setTag("p", recipient.asHex());

        Platform platform = NostrUtils.getPlatform();
        Map<String, Object> content = answer.get();

        String json = platform.toJSON(content);

        return encrypt(json, recipient)
            .compose(encContent -> {
                event.setContent(encContent);
                logger.finest("Sending answer: " + event + " " + content + " to " + recipient);

                return this.localPeer.getSigner()
                    .sign(event)
                    .compose(ev -> {
                        return pool.send(ev);
                    });
            });
    }

    public AsyncTask<List<NostrMessageAck>> sendCandidates(NostrRTCIceCandidate candidate, NostrPublicKey recipient)
        throws Exception {
         if (this.closed) throw new IllegalStateException("Already closed");

        UnsignedNostrEvent event = new UnsignedNostrEvent();
        event.setKind(25050);
        event.setCreatedAt(Instant.now());
        event.setTag("r", this.roomKeyPair.getPublicKey().asHex());
        event.setTag("t", "candidate");
        event.setTag("p", recipient.asHex());

        Platform platform = NostrUtils.getPlatform();
        Map<String, Object> content = candidate.get();
        String json = platform.toJSON(content);
        return encrypt(json, recipient)
            .compose(encContent -> {
                event.setContent(encContent);
                logger.finest("Sending candidates: " + event + " " + content + " to " + recipient);

                return this.localPeer.getSigner()
                    .sign(event)
                    .compose(ev -> {
                        return pool.send(ev);
                    });
            });
    }

    public void close() {
        if (this.closed) throw new IllegalStateException("Already closed");
        logger.fine("Closing signaling");
        this.closed = true;
        UnsignedNostrEvent connectEvent = new UnsignedNostrEvent();
        connectEvent.setKind(25050);
        connectEvent.setCreatedAt(Instant.now());
        connectEvent.setTag("r", this.roomKeyPair.getPublicKey().asHex());
        connectEvent.setTag("t", "disconnect");
        this.localPeer.getSigner()
            .sign(connectEvent)
            .compose(ev -> {
                return pool.send(ev);
            });
        this.sub1.close();
        this.sub2.close();
        this.executor.close();
    }

    private AsyncTask<String> encrypt(String content, NostrPublicKey recipient) {
        AsyncTask<String> out =
            this.localPeer.getSigner()
                .encrypt(content, recipient)
                .compose(enc -> {
                    return roomSigner.encrypt(enc, recipient);
                });
        return out;
    }

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
