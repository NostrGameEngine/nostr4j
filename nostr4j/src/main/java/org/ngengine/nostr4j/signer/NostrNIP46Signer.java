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
package org.ngengine.nostr4j.signer;

import static org.ngengine.platform.NGEUtils.dbg;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.ngengine.nostr4j.NostrFilter;
import org.ngengine.nostr4j.NostrPool;
import org.ngengine.nostr4j.NostrRelay;
import org.ngengine.nostr4j.NostrSubscription;
import org.ngengine.nostr4j.event.SignedNostrEvent;
import org.ngengine.nostr4j.event.UnsignedNostrEvent;
import org.ngengine.nostr4j.keypair.NostrKeyPair;
import org.ngengine.nostr4j.keypair.NostrPublicKey;
import org.ngengine.nostr4j.listeners.sub.NostrSubEventListener;
import org.ngengine.nostr4j.nip44.Nip44;
import org.ngengine.nostr4j.nip46.BunkerUrl;
import org.ngengine.nostr4j.nip46.Nip46AppMetadata;
import org.ngengine.nostr4j.nip46.NostrconnectUrl;
import org.ngengine.nostr4j.proto.NostrMessageAck;
import org.ngengine.nostr4j.utils.UniqueId;
import org.ngengine.platform.AsyncExecutor;
import org.ngengine.platform.AsyncTask;
import org.ngengine.platform.NGEPlatform;
import org.ngengine.platform.NGEUtils;

/**
 * NIP-46 Signer
 * A signer that supports nostrconnect:// and bunker:// flows.
 * The signer can listen to nostrconnect:// and attempt a bunker:// connection at the same time,
 * the first one that succeeds will cancel the other.
 */
public class NostrNIP46Signer implements NostrSigner, NostrSubEventListener {

    private static final long serialVersionUID = 1L;

    private static final Logger logger = Logger.getLogger(NostrNIP46Signer.class.getName());

    private static class ResponseListener {

        public final String method;
        public final Consumer<String> onSuccess;
        public final Consumer<Throwable> onError;
        public final Instant expiredAt;
        public Predicate<String> verifyPayload = null;

        @Override
        public String toString() {
            return "ResponseListener: " + method;
        }

        /**
         * Cancel with error
         * @param reason
         */
        void cancel(String reason) {
            onError.accept(new Exception("Request cancelled: " + reason));
        }

        ResponseListener(String method, Consumer<String> onSuccess, Consumer<Throwable> onError, Duration expireAfter) {
            this.method = method;
            this.onSuccess = onSuccess;
            this.onError = onError;
            this.expiredAt = Instant.now().plus(expireAfter);
        }
    }

    private static class PendingChallenge {

        public final Instant createdAt;
        private final Consumer<Throwable> close;

        public void close(Throwable t) {
            this.close.accept(t);
        }

        PendingChallenge(String url, Consumer<Throwable> close) {
            this.createdAt = Instant.now();
            this.close = close;
        }
    }

    private final Nip46AppMetadata metadata;
    private final NostrPublicKey transportPubkey;
    private final NostrKeyPairSigner transportSigner;

    private transient volatile NostrPool pool;
    private transient volatile Map<String, ResponseListener> listeners;
    private transient volatile NostrSubscription subscription;
    private transient volatile BiFunction<String, String, Consumer<Throwable>> challengeHandler;
    private transient volatile Map<String, PendingChallenge> pendingChallenges;
    private transient volatile AsyncExecutor executor;
    private transient volatile boolean closed = false;
    private transient volatile NostrPublicKey cachedPublicKey = null;

    private Set<String> relays = new HashSet<>();
    private Duration requestsTimeout = Duration.ofSeconds(30);
    private Duration challengesTimeout = Duration.ofSeconds(30);

    private volatile NostrPublicKey signerPubkey;
    private volatile NostrconnectUrl connectUrl;
    private volatile boolean connected = false;

    public NostrNIP46Signer(Nip46AppMetadata metadata, NostrKeyPair clientKeyPair) {
        // this.challengeHandler = challengeHandler;

        if (clientKeyPair == null) {
            clientKeyPair = new NostrKeyPair();
        }

        this.metadata = metadata;
        this.transportPubkey = clientKeyPair.getPublicKey();
        this.transportSigner = new NostrKeyPairSigner(clientKeyPair);
    }

    @Override
    public String toString() {
        return "nip46-signer: " + this.transportPubkey.asHex();
    }

    /**
     * Set the timeout for requests in this signer.
     * @param timeout timeout (default 30 seconds)
     */
    public void setRequestsTimeout(Duration timeout) {
        this.requestsTimeout = timeout;
    }

    // internal loop (runs in signer executor)
    private void loop() {
        try {
            // cancel all expired pending challenges
            Instant now = Instant.now();
            if (this.pendingChallenges != null) {
                Iterator<Entry<String, PendingChallenge>> it = pendingChallenges.entrySet().iterator();
                while (it.hasNext()) {
                    Entry<String, PendingChallenge> entry = it.next();
                    if (entry.getValue().createdAt.plus(challengesTimeout).isBefore(now)) {
                        logger.fine("Cancelling expired challenge: " + entry.getValue());
                        entry.getValue().close.accept(new Exception("Challenge expired"));
                        it.remove();
                    }
                }
            }

            // cancel all expired listeners
            if (this.listeners != null) {
                Iterator<Entry<String, ResponseListener>> it2 = listeners.entrySet().iterator();
                while (it2.hasNext()) {
                    Entry<String, ResponseListener> entry = it2.next();
                    if (entry.getValue().expiredAt.isBefore(now)) {
                        logger.fine("Cancelling expired request: " + entry.getValue());
                        entry.getValue().cancel("Request expired");
                        it2.remove();
                    }
                }
            }
        } catch (Exception e) {
            logger.warning("Error in loop: " + e.getMessage());
        }

        if (closed) return;
        this.executor.runLater(
                () -> {
                    loop();
                    return null;
                },
                10,
                TimeUnit.SECONDS
            );
    }

    /**
     * Close this signer and all its resources.
     */
    public AsyncTask<NostrSigner> close() {
        NGEPlatform platform = NGEUtils.getPlatform();
        return platform.wrapPromise((res, rej) -> {
            try {
                if (closed) {
                    logger.finer("Already closed: " + this);
                    res.accept(this);
                    return;
                }
                logger.fine("Closing signer: " + this);
                closed = true;
                if (this.pool != null) {
                    this.pool.close();
                }
                if (this.subscription != null) {
                    this.subscription.close();
                }
                if (this.listeners != null) {
                    for (ResponseListener l : listeners.values()) {
                        l.cancel("Closed");
                    }
                }

                if (this.pendingChallenges != null) {
                    for (PendingChallenge c : pendingChallenges.values()) {
                        c.close.accept(new Exception("Closed"));
                    }
                }
                res.accept(this);
                if (this.executor != null) {
                    this.executor.close();
                }
            } catch (Exception e) {
                rej.accept(e);
            }
        });
    }

    /**
     * Connect to a remote signer using the bunker flow
     * @param bunker the bunker url (use {@link BunkerUrl#parse(String)} to parse a url)
     * @return an async tasks that will complete with this signer after the connection is established
     */
    public AsyncTask<NostrNIP46Signer> connect(BunkerUrl bunker) {
        if (connected) return NGEPlatform
            .get()
            .wrapPromise((res, rej) -> {
                rej.accept(new Exception("Already connected"));
            });

        for (String relay : bunker.relays) {
            this.relays.add(relay);
        }
        logger.fine("Connecting to bunker: " + bunker + " relays: " + this.relays);
        this.signerPubkey = bunker.pubkey;
        List<String> params = new ArrayList<>();
        params.add(this.signerPubkey.asHex());
        params.add(bunker.secret);
        if (this.metadata.getPerms() != null) {
            params.add(String.join(",", this.metadata.getPerms()));
        }
        connectUrl = new NostrconnectUrl(this.transportPubkey, bunker.relays, bunker.secret, this.metadata);
        return sendRPC("connect", params, requestsTimeout)
            .then(r -> {
                logger.fine("Connected to bunker: " + bunker + " relays: " + this.relays);
                connected = true;
                // cancel every other remaining connect attempt
                Iterator<Entry<String, ResponseListener>> it = listeners.entrySet().iterator();
                while (it.hasNext()) {
                    Entry<String, ResponseListener> entry = it.next();
                    if (entry.getValue().method.equals("connect")) {
                        logger.finer("Cancelling other connect request: " + entry.getValue());
                        entry.getValue().cancel("connected via bunker");
                        it.remove();
                    }
                }
                return this;
            });
    }

    /**
     * Listen for spontaneous connection using the nostrconnect flow.
     * @param relays the relays to use for the connection
     * @param onUrl the callback to call when nostrconnect url is ready and can be displayed to the user
     * @param timeout the timeout for the connection
     * @return an async task that will complete with this signer after the connection is established
     */
    public AsyncTask<NostrNIP46Signer> listen(List<String> relays, Consumer<NostrconnectUrl> onUrl, Duration timeout) {
        NGEPlatform platform = NGEUtils.getPlatform();

        if (connected) return platform.wrapPromise((res, rej) -> {
            rej.accept(new Exception("Already connected"));
        });

        String secret = NGEUtils.bytesToHex(platform.randomBytes(32));
        for (String relay : relays) {
            this.relays.add(relay);
        }

        connectUrl = new NostrconnectUrl(this.transportPubkey, relays, secret, this.metadata);

        onUrl.accept(connectUrl);
        logger.fine("Listening for nostrconnect: " + connectUrl + " relays: " + this.relays);

        AsyncTask<NostrNIP46Signer> out = check()
            .compose(r -> {
                return waitForResponse(
                    "connect",
                    "nostrconnect",
                    payload -> {
                        boolean v = payload.equals(secret); // nostr connect requires the payload to be == secret
                        assert dbg(() ->
                            logger.fine("Received nostrconnect payload: " + payload + " secret: " + secret + " valid: " + v)
                        );
                        return v;
                    },
                    timeout
                )
                    .then(s -> {
                        logger.fine("Received nostrconnect payload: " + s + " relays: " + this.relays);
                        connected = true;
                        // cancel every other remaining connect attempt
                        Iterator<Entry<String, ResponseListener>> it = listeners.entrySet().iterator();
                        while (it.hasNext()) {
                            Entry<String, ResponseListener> entry = it.next();
                            if (entry.getValue().method.equals("connect")) {
                                logger.finer("Cancelling other connect request: " + entry.getValue());
                                entry.getValue().cancel("connected via nostrconnect");
                                it.remove();
                            }
                        }
                        return this;
                    });
            });
        return out;
    }

    /**
     * Set the challenge handler for this signer.
     * <p>
     * This handler will be called when a challenge is received from the remote signer.
     * The handler should return a Consumer that will be called when the challenge is completed.
     * The Consumer will receive a Throwable if the challenge failed, or null if it succeeded.
     * </p>
     * @param challengeHandler  the challenge handler, first parameter is the type, second is the content
     * @param challengesTimeout timeout for the challenge
     */
    public void setChallengeHandler(
        BiFunction<String, String, Consumer<Throwable>> challengeHandler,
        Duration challengesTimeout
    ) {
        this.challengeHandler = challengeHandler;
        this.challengesTimeout = challengesTimeout;
    }

    /*
     * Make sure everything is started and
     * connected. Called internally.
     * Everything enqueued to this will run in the signer executor.
     */
    private AsyncTask<List<AsyncTask<NostrMessageAck>>> check() {
        if (closed) throw new RuntimeException("Closed");
        NGEPlatform p = NGEUtils.getPlatform();

        if (this.executor == null) { // DCL
            synchronized (this) {
                if (this.executor == null) {
                    logger.fine("Creating executor");
                    this.executor = p.newSignerExecutor();
                    this.loop();
                }
            }
        }

        return p.promisify(
            (res, rej) -> {
                try {
                    if (this.listeners == null) {
                        synchronized (this) {
                            if (this.listeners == null) {
                                logger.finest("Creating listeners map");
                                this.listeners = new ConcurrentHashMap<>();
                            }
                        }
                    }

                    if (this.pendingChallenges == null) {
                        synchronized (this) {
                            if (this.pendingChallenges == null) {
                                logger.finest("Creating pending challenges map");
                                this.pendingChallenges = new ConcurrentHashMap<>();
                            }
                        }
                    }

                    if (this.pool == null) {
                        synchronized (this) {
                            if (this.pool == null) {
                                logger.finest("Creating pool");
                                this.pool = new NostrPool();
                            }
                        }
                    }

                    for (String relay : relays) {
                        if (!this.pool.getRelays().stream().anyMatch(s -> s.getUrl().equals(relay))) {
                            this.pool.connectRelay(new NostrRelay(relay));
                        }
                    }

                    if (this.subscription == null) {
                        synchronized (this) {
                            if (this.subscription == null) {
                                NostrFilter filter = new NostrFilter()
                                    .withKind(24133)
                                    .withTag("p", this.transportPubkey.asHex())
                                    .limit(100)
                                    .since(Instant.now().minusSeconds(60));
                                logger.finest("Creating subscription for filter: " + filter);
                                this.subscription = this.pool.subscribe(filter);
                                this.subscription.addListener(this);
                            }
                        }
                    }

                    if (!this.subscription.isOpened()) {
                        synchronized (this) {
                            if (!this.subscription.isOpened()) {
                                logger.finest("Opening subscription: " + this.subscription);
                                this.subscription.open()
                                    .catchException(exc -> {
                                        rej.accept(exc);
                                    })
                                    .then(all -> {
                                        logger.fine("Subscription opened: " + this.subscription);
                                        res.accept(all);
                                        return null;
                                    });
                            }
                        }
                    } else {
                        assert dbg(() -> logger.finest("Subscription already opened: " + this.subscription));
                    }

                    try {
                        res.accept(new ArrayList<>());
                    } catch (Exception e) {
                        rej.accept(e);
                    }
                } catch (Exception e) {
                    rej.accept(e);
                }
            },
            this.executor
        );
    }

    @Override
    public void onSubEvent(SignedNostrEvent event, boolean stored) {
        try {
            if (event.getKind() != 24133) {
                logger.warning("Received unexpected event with kind: " + event.getKind());
                return;
            }

            NostrPublicKey pubkey = null;
            boolean isSpontaneousConnection = false;
            if (!event.getPubkey().equals(this.signerPubkey)) {
                // if we are receiving an event from an unknown signer, we assume it is a spontaneous connection
                // but only if we are not already connected
                if (!this.connected) {
                    assert dbg(() ->
                        logger.fine(
                            "Received event from unknown signer: " +
                            event.getPubkey() +
                            " != " +
                            this.signerPubkey +
                            " initializing spontaneous connection flow"
                        )
                    );

                    pubkey = event.getPubkey(); // from unknown signer
                    isSpontaneousConnection = true;
                } else {
                    // we got connected in the meantime, so we need to bail
                    throw new Exception(
                        "Received event from unknown signer: " +
                        event.getPubkey() +
                        " but spontaneous connection is not allowed since we are already connected"
                    );
                }
            } else {
                assert dbg(() ->
                    logger.fine("Received event from known signer: " + event.getPubkey() + " == " + this.signerPubkey)
                );
                pubkey = this.signerPubkey; // from known signer
            }

            // decrypt content
            String content = event.getContent();
            byte[] conversationKey = Nip44.getConversationKeySync(this.transportSigner.getKeyPair().getPrivateKey(), pubkey);
            String decryptedContent = Nip44.decryptSync(content, conversationKey);

            assert dbg(() -> logger.finer("Received response: " + decryptedContent));
            // parse content
            Map<String, Object> response = NGEUtils.getPlatform().fromJSON(decryptedContent, Map.class);

            // get the id from the content, unless we are dealing with a spontaneous connection
            // a spontaneous nostrconnect connection has an hardcoded id, because
            // we are routing it to a single special response listener for all spontaneous
            // connections
            String id = isSpontaneousConnection ? "nostrconnect" : NGEUtils.safeString(response.get("id"));

            String error = NGEUtils.safeString(response.get("error"));
            String result = NGEUtils.safeString(response.get("result"));

            // get the listener for this response
            ResponseListener listener = listeners.get(id);
            if (listener == null) {
                logger.warning("No listener for id: " + id);
                // a response for something we don't want?
                throw new Exception("No listener for id: " + id);
            }

            // if the listener has a verifyPayload function, we need to call it
            // to ensure the payload is what we expect
            if (listener.verifyPayload != null) {
                assert dbg(() -> logger.fine("Verifying payload " + id + " with method: " + listener.method));
                if (!listener.verifyPayload.test(result)) { // this is used e.g. to verify the secret of spontaneous
                    // connections
                    logger.warning("Invalid payload for id: " + id + " with method: " + listener.method);
                    throw new Exception("Invalid payload for id: " + id);
                }
            } else {
                if (isSpontaneousConnection) {
                    throw new Exception("Spontaneous connection without payload verification are forbidden");
                }
            }

            boolean isChallenge = result.equals("auth_url");
            if (isChallenge) {
                logger.fine(
                    "Received challenge for id: " + id + " with method: " + listener.method + " " + result + " " + error
                );
                // if is a challenge, we call the challenge handler
                if (challengeHandler != null) {
                    logger.finest("Calling challenge handler for id: " + id + " with method: " + listener.method);

                    // just making sure we are not spamming the challenge.
                    if (pendingChallenges.containsKey(id)) {
                        logger.warning("Challenge already pending for id: " + id);
                        throw new Exception("Challenge already pending for id: " + id);
                    }

                    Consumer<Throwable> close = challengeHandler.apply(result, error);

                    logger.finest("Challenge handler returned: " + close);
                    PendingChallenge challenge = new PendingChallenge(result, close);
                    pendingChallenges.put(id, challenge);
                } else {
                    logger.warning("Received challenge, but no handler set");
                    throw new Exception("Challenge received, but no handler set");
                }
                // we won't remove the listener as we will receive another reply for the same request once
                // the challenge is completed
            } else {
                // if there is a pending challenge for this request, we kill it
                PendingChallenge challenge = pendingChallenges.remove(id);
                if (challenge != null) {
                    logger.fine("Closing challenge for id: " + id + " with method: " + listener.method);
                    challenge.close(null);
                }

                // we remove the listener as we are about to call it.
                listeners.remove(id);

                // call the listener
                if (!error.isBlank()) {
                    logger.finest("Error for id: " + id + " with method: " + listener.method + " " + result + " " + error);
                    listener.onError.accept(new Exception(error));
                } else {
                    assert dbg(() -> logger.finest("Success for id: " + id + " with method: " + listener.method + " " + result)
                    );
                    if (isSpontaneousConnection) {
                        if (this.connected) {
                            // we almost connected, but a bunker connection was triggered in the meantime, so we need to bail
                            logger.warning(
                                "Received spontaneous connection response, but we are already got connected in the meantime"
                            );
                            listener.onError.accept(new Exception("Already connected"));
                        } else {
                            // if we are registering a spontaneous connection, we need to set the signer
                            // pubkey
                            logger.fine("Registering signer pubkey for spontaneous connection: " + pubkey);
                            this.signerPubkey = pubkey;
                            listener.onSuccess.accept(result);
                        }
                    } else {
                        listener.onSuccess.accept(result);
                    }
                }
            }
        } catch (Exception e) {
            logger.log(Level.WARNING, "Error processing event", e);
        }
    }

    /**
     * Wait for a response from the remote signer.
     * Used internally
     */
    private AsyncTask<String> waitForResponse(String method, String id, Predicate<String> verifyPayload, Duration timeout) {
        NGEPlatform platform = NGEUtils.getPlatform();
        assert dbg(() -> logger.finest("Waiting for response: " + method + " id: " + id + " timeout: " + timeout));
        return platform.wrapPromise((res, rej) -> {
            ResponseListener listener = new ResponseListener(method, res, rej, timeout);
            listener.verifyPayload = verifyPayload;
            listeners.put(id, listener);
        });
    }

    private AsyncTask<String> waitForResponse(String method, String id, Duration timeout) {
        return waitForResponse(method, id, null, timeout);
    }

    /**
     * Send a request to the remote signer.
     * This is used internally by the signer, but can also be used by the application
     * to send custom requests to the remote signer.
     * @param method the method to call
     * @param params the parameters to pass to the method (usually a collection or map)
     * @param timeout the timeout for the request
     * @return an async task that will complete with the result of the request
     */
    public AsyncTask<String> sendRPC(String method, Object params, Duration timeout) {
        return check()
            .compose(r -> {
                try {
                    NGEPlatform platform = NGEUtils.getPlatform();
                    String requestId = UniqueId.getNext();

                    Map<String, Object> reqBody = new HashMap<>();
                    reqBody.put("id", requestId);
                    reqBody.put("method", method);
                    reqBody.put("params", params);

                    UnsignedNostrEvent event = new UnsignedNostrEvent()
                        .withKind(24133)
                        .createdAt(Instant.now())
                        .withTag("p", this.signerPubkey.asHex())
                        .withContent(platform.toJSON(reqBody));

                    assert dbg(() -> logger.finer("Sending request: " + event));

                    AsyncTask<String> response = Nip44
                        .getConversationKey(this.transportSigner.getKeyPair().getPrivateKey(), this.signerPubkey)
                        .compose(conversationKey -> {
                            return Nip44.encrypt(event.getContent(), conversationKey);
                        })
                        .compose(encryptedContent -> {
                            event.withContent(encryptedContent);

                            // we need to start waiting for the response before we publish the event
                            // to make sure we don't miss the response if it comes in before we have a
                            // chance to wait for it
                            AsyncTask<String> res = this.waitForResponse(method, requestId, timeout);

                            this.transportSigner.sign(event)
                                .then(signed -> {
                                    this.pool.publish(signed);
                                    return null;
                                });
                            return res;
                        });
                    return response;
                } catch (Exception e) {
                    throw new RuntimeException("Failed to send RPC request", e);
                }
            });
    }

    @Override
    public AsyncTask<SignedNostrEvent> sign(UnsignedNostrEvent event) {
        String method = "sign_event";
        NGEPlatform platform = NGEUtils.getPlatform();

        Map<String, Object> params = new HashMap<>();
        params.put("kind", event.getKind());
        params.put("content", event.getContent());
        params.put("tags", event.getTagRows());
        params.put("created_at", event.getCreatedAt().getEpochSecond());
        return sendRPC(method, List.of(platform.toJSON(params)), requestsTimeout)
            .then(signed -> {
                try {
                    return new SignedNostrEvent(platform.fromJSON(signed, Map.class));
                } catch (Exception e) {
                    throw new RuntimeException("Failed to sign event", e);
                }
            });
    }

    @Override
    public AsyncTask<String> encrypt(String message, NostrPublicKey publicKey, NostrSigner.EncryptAlgo algo) {
        String method;
        switch (algo) {
            case NIP04:
                method = "nip04_encrypt";
                break;
            default:
            case NIP44:
                method = "nip44_encrypt";
                break;
        }
        Collection<Object> params = new ArrayList<>();
        params.add(publicKey.asHex());
        params.add(message);
        return sendRPC(method, params, requestsTimeout);
    }

    @Override
    public AsyncTask<String> decrypt(String message, NostrPublicKey publicKey, NostrSigner.EncryptAlgo algo) {
        String method;
        switch (algo) {
            case NIP04:
                method = "nip04_decrypt";
                break;
            default:
            case NIP44:
                method = "nip44_decrypt";
                break;
        }
        Collection<Object> params = new ArrayList<>();
        params.add(publicKey.asHex());
        params.add(message);
        return sendRPC(method, params, requestsTimeout);
    }

    @Override
    public AsyncTask<NostrPublicKey> getPublicKey() {
        if (cachedPublicKey != null) {
            return NGEPlatform
                .get()
                .wrapPromise((res, rej) -> {
                    res.accept(cachedPublicKey);
                });
        }
        String method = "get_public_key";
        Collection<Object> params = new ArrayList<>();
        return sendRPC(method, params, requestsTimeout)
            .then(publicKey -> {
                cachedPublicKey = NostrPublicKey.fromHex(publicKey);
                return cachedPublicKey;
            });
    }
}
