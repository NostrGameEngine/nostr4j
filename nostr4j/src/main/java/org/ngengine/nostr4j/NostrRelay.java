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

import static org.ngengine.platform.NGEUtils.dbg;

import java.io.IOException;
import java.time.Instant;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.ngengine.nostr4j.event.SignedNostrEvent;
import org.ngengine.nostr4j.listeners.NostrRelayComponent;
import org.ngengine.nostr4j.proto.NostrMessage;
import org.ngengine.nostr4j.proto.NostrMessageAck;
import org.ngengine.nostr4j.proto.impl.NostrClosedMessage;
import org.ngengine.nostr4j.proto.impl.NostrEOSEMessage;
import org.ngengine.nostr4j.proto.impl.NostrNoticeMessage;
import org.ngengine.nostr4j.proto.impl.NostrOKMessage;
import org.ngengine.nostr4j.utils.ExponentialBackoff;
import org.ngengine.platform.AsyncExecutor;
import org.ngengine.platform.AsyncTask;
import org.ngengine.platform.NGEPlatform;
import org.ngengine.platform.NGEUtils;
import org.ngengine.platform.transport.WebsocketTransport;
import org.ngengine.platform.transport.WebsocketTransportListener;

public final class NostrRelay {

    private static final Logger logger = Logger.getLogger(NostrRelay.class.getName());

    private static final class QueuedMessage {

        final NostrMessage message;
        final Consumer<NostrMessageAck> res;
        final Consumer<Throwable> rej;

        QueuedMessage(NostrMessage message, Consumer<NostrMessageAck> res, Consumer<Throwable> rej) {
            this.message = message;
            this.res = res;
            this.rej = rej;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (obj == null || getClass() != obj.getClass()) return false;
            return false;
        }

        @Override
        public int hashCode() {
            return message.hashCode();
        }
    }

    private WebsocketTransportListener listener = new WebsocketTransportListener() {
        @Override
        public void onConnectionClosedByServer(String reason) {
            NostrRelay.this.onConnectionClosedByServer(reason);
        }

        @Override
        public void onConnectionOpen() {
            NostrRelay.this.onConnectionOpen();
        }

        @Override
        public void onConnectionMessage(String msg) {
            NostrRelay.this.onConnectionMessage(msg);
        }

        @Override
        public void onConnectionClosedByClient(String reason) {
            NostrRelay.this.onConnectionClosedByClient(reason);
        }

        @Override
        public void onConnectionError(Throwable e) {
            NostrRelay.this.onConnectionError(e);
        }
    };

    protected final WebsocketTransport connector;
    protected final String url;
    protected final List<NostrRelayComponent> listeners = new CopyOnWriteArrayList<>();
    protected final Map<String, NostrMessageAck> waitingEventsAck = new ConcurrentHashMap<>();
    protected final AsyncExecutor executor;

    protected final ExponentialBackoff reconnectionBackoff = new ExponentialBackoff();

    protected volatile long ackTimeoutS = TimeUnit.MINUTES.toSeconds(21);
    protected volatile boolean reconnectOnDrop = true;
    protected volatile boolean disconnectedByClient = false;
    protected volatile boolean connected = false;
    protected volatile boolean connecting = false;
    protected volatile boolean firstConnection = false;
    protected volatile boolean verifyEvents = true;
    protected volatile boolean parallelEvents = true;

    protected final Queue<QueuedMessage> messageQueue;
    protected final Queue<Runnable> connectCallbacks;

    protected AtomicReference<AsyncTask> queue = new AtomicReference(null);

    protected transient NostrRelayInfo relayInfo = null;

    public NostrRelay(String url) {
        this(url, NGEUtils.getPlatform().newRelayExecutor());
    }

    public NostrRelay(String url, AsyncExecutor executor) {
        try {
            NGEPlatform platform = NGEUtils.getPlatform();
            this.connector = platform.newTransport();
            this.connector.addListener(listener);
            this.messageQueue = platform.newConcurrentQueue(QueuedMessage.class);
            this.connectCallbacks = platform.newConcurrentQueue(Runnable.class);
            this.url = url;
            this.executor = executor;
        } catch (Exception e) {
            throw new RuntimeException("Error creating NostrRelay", e);
        }
    }

    public NostrRelayInfo getInfo() throws IOException {
        if (relayInfo != null) return relayInfo;
        try {
            relayInfo = NostrRelayInfo.get(url).await();
            return relayInfo;
        } catch (Exception e) {
            throw new IOException("Error getting relay info", e);
        }
    }

    public void setVerifyEvents(boolean verify) {
        this.verifyEvents = verify;
    }

    public boolean isVerifyEvents() {
        return this.verifyEvents;
    }

    public void setAsyncEventsVerification(boolean v) {
        this.parallelEvents = v;
    }

    public boolean isAsyncEventsVerification() {
        return this.parallelEvents;
    }

    protected <T> void runInRelayExecutor(BiConsumer<Consumer<T>, Consumer<Throwable>> runnable, boolean enqueue) {
        NGEPlatform platform = NGEUtils.getPlatform();
        if (!enqueue) {
            platform.promisify(runnable, executor);
        } else {
            synchronized (queue) {
                if (queue.get() == null) {
                    AsyncTask<T> nq = platform.promisify(runnable, executor);
                    queue.set(nq);
                } else {
                    AsyncTask<T> q = queue.get();
                    AsyncTask<T> nq = q.compose(
                        (
                            r -> {
                                return platform.wrapPromise(runnable);
                            }
                        )
                    );
                    queue.set(nq);
                }
            }
        }
    }

    public void setAutoReconnect(boolean reconnect) {
        this.reconnectOnDrop = reconnect;
    }

    public boolean isAutoReconnect() {
        return this.reconnectOnDrop;
    }

    public void setAckTimeout(long time, TimeUnit unit) {
        this.ackTimeoutS = unit.toSeconds(time);
    }

    public long getAckTimeout(TimeUnit outputUnit) {
        return outputUnit.convert(this.ackTimeoutS, TimeUnit.SECONDS);
    }

    public NostrRelay addComponent(NostrRelayComponent listener) {
        assert !listeners.contains(listener);
        this.listeners.add(listener);
        return this;
    }

    public NostrRelay removeComponent(NostrRelayComponent listener) {
        this.listeners.remove(listener);
        return this;
    }

    public <T extends NostrRelayComponent> T getComponent(Class<T> clazz) {
        for (NostrRelayComponent listener : this.listeners) {
            if (clazz.isInstance(listener)) {
                return clazz.cast(listener);
            }
        }
        return null;
    }

    public boolean isConnected() {
        return (this.connected || (!this.disconnectedByClient && this.reconnectOnDrop && this.firstConnection));
    }

    public void beforeSendMessage(NostrMessage message) {
        for (NostrRelayComponent listener : this.listeners) {
            try {
                if (!listener.onRelayBeforeSend(this, message)) {
                    assert dbg(() -> {
                        logger.finer("Message ignored by component: " + this.url);
                    });
                    return;
                }
            } catch (Throwable e) {
                assert dbg(() -> {
                    logger.finer("Message cancelled by component: " + e.getMessage());
                });
                return;
            }
        }
    }

    public void afterSendMessage(NostrMessage message) {
        for (NostrRelayComponent listener : this.listeners) {
            try {
                if (!listener.onRelayAfterSend(this, message)) {
                    assert dbg(() -> {
                        logger.finer("Message ignored by component: " + this.url);
                    });
                    return;
                }
            } catch (Throwable e) {
                assert dbg(() -> {
                    logger.finer("Message cancelled by component: " + e.getMessage());
                });
                return;
            }
        }
    }

    // await for order
    public AsyncTask<NostrMessageAck> sendMessage(NostrMessage message) {
        NGEPlatform platform = NGEUtils.getPlatform();
        return platform.wrapPromise((ores, orej) -> {
            runInRelayExecutor(
                (r0, rj0) -> {
                    try {
                        if (!this.connected) {
                            assert dbg(() -> {
                                logger.finer("Relay not connected, queueing message: " + message.toString());
                            });

                            QueuedMessage q = new QueuedMessage(message, ores, orej);
                            assert !this.messageQueue.contains(q) : "Duplicate message in queue: " + q.message.toString();
                            this.messageQueue.add(q);
                            r0.accept(this);
                            return;
                        }

                        String eventId = message instanceof SignedNostrEvent ? ((SignedNostrEvent) message).getId() : null;

                        NostrMessageAck result = NostrMessage.ack(
                            this,
                            eventId != null ? eventId : null,
                            Instant.now(),
                            (rr, msg) -> {
                                if (eventId != null) {
                                    this.waitingEventsAck.remove(eventId);
                                }
                                assert dbg(() -> {
                                    logger.finest("ack: " + msg + " " + eventId);
                                });
                                ores.accept(rr);
                            },
                            (rr, msg) -> {
                                if (eventId != null) {
                                    this.waitingEventsAck.remove(eventId);
                                }
                                assert dbg(() -> {
                                    logger.finest("ack (rejected): " + msg + " " + eventId);
                                });
                                ores.accept(rr);
                            }
                        );

                        for (NostrRelayComponent listener : this.listeners) {
                            try {
                                if (!listener.onRelaySend(this, message)) {
                                    result.callSuccessCallback("message ignored by component");
                                    r0.accept(this);
                                    return;
                                }
                            } catch (Throwable e) {
                                result.callFailureCallback("message cancelled by component " + e.getMessage());
                                rj0.accept(e);
                                return;
                            }
                        }

                        if (eventId != null) {
                            this.waitingEventsAck.put(eventId, result);
                        }

                        try {
                            String json = NostrMessage.toJSON(message);
                            this.connector.send(json)
                                .catchException(e -> {
                                    // assert dbg(() -> {

                                    // });
                                    logger.log(Level.WARNING, "Error sending message", e);
                                    result.callFailureCallback(e.getMessage());
                                })
                                .then(vo -> {
                                    assert dbg(() -> {
                                        logger.finest("Message sent: " + json);
                                    });
                                    if (eventId == null) {
                                        result.callSuccessCallback("ok");
                                    }
                                    return null;
                                });
                        } catch (Throwable e) {
                            logger.log(Level.WARNING, "Error sending message (0)", e);
                            result.callFailureCallback(e.getMessage());
                        }
                    } catch (Throwable e) {
                        logger.log(Level.WARNING, "Error sending message (1)", e);
                        rj0.accept(e);
                    }
                    r0.accept(this);
                },
                false
            );
        });
    }

    public String getUrl() {
        return url;
    }

    public AsyncTask<NostrRelay> connect() {
        NGEPlatform platform = NGEUtils.getPlatform();

        if (!this.connected && !this.connecting) {
            this.connecting = true;
            return platform.wrapPromise((res, rej) -> {
                runInRelayExecutor(
                    (r0, rj0) -> {
                        this.disconnectedByClient = false;
                        logger.fine("Connecting to relay: " + this.url);
                        for (NostrRelayComponent listener : this.listeners) {
                            try {
                                if (!listener.onRelayConnectRequest(this)) {
                                    logger.finer("Connection ignored by component: " + this.url);
                                    res.accept(this);
                                    r0.accept(this);
                                    return;
                                }
                            } catch (Throwable e) {
                                rej.accept(new Exception("Connection cancelled by component: " + e.getMessage()));
                                rj0.accept(e);
                                return;
                            }
                        }

                        connectCallbacks.add(() -> {
                            res.accept(this);
                        });
                        this.connector.connect(url)
                            .catchException(e -> {
                                this.onConnectionClosedByServer("failed to connect");
                                rej.accept(e);
                            });
                        this.loop();
                        r0.accept(this);
                    },
                    true
                );
            });
        } else {
            return platform.wrapPromise((res, rej) -> {
                res.accept(this);
            });
        }
    }

    public AsyncTask<NostrRelay> disconnect(String reason) {
        this.connected = false;
        this.disconnectedByClient = true;
        this.connector.close(reason);
        AsyncExecutor executor = this.executor;
        NGEPlatform platform = NGEUtils.getPlatform();
        return platform.wrapPromise((ores, orej) -> {
            runInRelayExecutor(
                (rs0, rj0) -> {
                    logger.fine("Disconnecting from relay!!: " + this.url + " reason: " + reason);
                    for (NostrRelayComponent listener : this.listeners) {
                        try {
                            if (!listener.onRelayDisconnectRequest(this, reason)) {
                                logger.finer("Disconnect ignored by component: " + this.url);
                                ores.accept(this);
                                rs0.accept(this);
                                return;
                            }
                        } catch (Throwable e) {
                            orej.accept(new Exception("Disconnect cancelled by component: " + e.getMessage()));
                            rj0.accept(e);
                            return;
                        }
                    }
                    executor.close();
                    ores.accept(this);
                    rs0.accept(this);
                },
                true
            );
        });
    }

    // await for order

    private void onConnectionOpen() {
        runInRelayExecutor(
            (res, rej) -> {
                try {
                    reconnectionBackoff.registerSuccess();
                    logger.fine("Connection opened: " + this.url);
                    for (NostrRelayComponent listener : this.listeners) {
                        try {
                            if (!listener.onRelayConnect(this)) {
                                logger.finer("Connection ignored by component: " + this.url);
                                res.accept(this);
                                return;
                            }
                        } catch (Throwable e) {
                            rej.accept(new Exception("Connection cancelled by component: " + e.getMessage()));
                            return;
                        }
                    }

                    this.connected = true;
                    this.connecting = false;
                    this.firstConnection = true;

                    {
                        Iterator<Runnable> it = this.connectCallbacks.iterator();
                        while (it.hasNext()) {
                            Runnable callback = it.next();
                            it.remove();
                            try {
                                callback.run();
                            } catch (Throwable e) {
                                assert dbg(() -> {
                                    logger.log(Level.WARNING, "Error in connect callback", e);
                                });
                            }
                        }
                    }

                    {
                        Iterator<QueuedMessage> it = this.messageQueue.iterator();

                        while (it.hasNext()) {
                            QueuedMessage q = it.next();
                            it.remove();
                            assert dbg(() -> {
                                logger.finer("Sending queued message: " + q.message);
                            });

                            NostrMessage message = q.message;
                            Consumer<NostrMessageAck> rs = q.res;
                            Consumer<Throwable> rj = q.rej;
                            try {
                                this.sendMessage(message)
                                    .catchException(t -> {
                                        rj.accept(t);
                                    })
                                    .then(v -> {
                                        rs.accept(v);
                                        return null;
                                    });
                            } catch (Throwable e) {
                                rej.accept(e);
                            }
                        }
                    }
                    res.accept(this);
                } catch (Throwable e) {
                    assert dbg(() -> {
                        logger.log(Level.WARNING, "Error in connect callback", e);
                    });
                    rej.accept(e);
                }
            },
            true
        );
    }

    //ordered
    private void onConnectionMessage(String msg) {
        try {
            NGEPlatform platform = NGEUtils.getPlatform();
            assert dbg(() -> {
                logger.finest("Received message: " + msg);
            });
            List<Object> data = platform.fromJSON(msg, List.class);
            String prefix = NGEUtils.safeString(data.get(0));

            // syncher.await();
            NostrMessage rcv = null;
            if (rcv == null) rcv = SignedNostrEvent.parse(data);
            if (rcv == null) rcv = NostrClosedMessage.parse(data);
            if (rcv == null) rcv = NostrEOSEMessage.parse(data);
            if (rcv == null) rcv = NostrOKMessage.parse(data);
            if (rcv == null) rcv = NostrNoticeMessage.parse(data);
            if (rcv == null) throw new Exception("Unknown message type: " + prefix);
            final NostrMessage message = rcv;

            final AsyncTask<Boolean> asyncVerifyPromise = (rcv instanceof SignedNostrEvent && verifyEvents && parallelEvents)
                ? (AsyncTask<Boolean>) ((SignedNostrEvent) rcv).verifyAsync()
                : null;

            runInRelayExecutor(
                (res, rej) -> {
                    try {
                        // handle acks
                        if (message instanceof NostrOKMessage) {
                            NostrOKMessage ok = (NostrOKMessage) message;
                            String eventId = ok.getEventId();
                            boolean success = ok.isSuccess();
                            String eventMessage = ok.getMessage();
                            NostrMessageAck ack = this.waitingEventsAck.get(eventId);
                            if (ack != null) {
                                assert dbg(() -> {
                                    logger.finest(
                                        "Received ack for event: " +
                                        eventId +
                                        " success: " +
                                        success +
                                        " message: " +
                                        eventMessage
                                    );
                                });

                                if (success) {
                                    ack.callSuccessCallback(eventMessage);
                                } else {
                                    ack.callFailureCallback(eventMessage);
                                }
                            } else {
                                assert dbg(() -> {
                                    logger.warning("Received ack for unknown event: " + eventId);
                                });
                            }
                        }

                        if (asyncVerifyPromise != null) {
                            if (!asyncVerifyPromise.await()) {
                                throw new Exception("Event verification failed");
                            }
                        } else if (verifyEvents && message instanceof SignedNostrEvent) {
                            SignedNostrEvent event = (SignedNostrEvent) message;
                            if (!event.verify()) {
                                throw new Exception("Event verification failed");
                            }
                        }

                        // propagate event to listeners
                        for (NostrRelayComponent listener : this.listeners) {
                            try {
                                if (!listener.onRelayMessage(this, message)) {
                                    assert dbg(() -> {
                                        logger.finest("Message ignored by component: " + this.url);
                                    });
                                    res.accept(this);
                                    return;
                                }
                            } catch (Throwable e) {
                                rej.accept(new Exception("Message cancelled by component: " + e.getMessage()));
                                return;
                            }
                        }

                        res.accept(this);
                    } catch (Exception e) {
                        rej.accept(e);
                        assert dbg(() -> {
                            logger.log(Level.WARNING, "Error processing message", e);
                        });
                    }
                },
                true
            );
        } catch (Exception e) {
            logger.severe("Error in onConnectionMessage: " + e.getMessage());
        }
    }

    // await for order
    private void onConnectionClosedByServer(String reason) {
        logger.finer("Connection closed by server: " + this.url + " reason: " + reason);
        boolean wasConnected = this.connected;
        this.connecting = false;
        this.connected = false;

        try {
            runInRelayExecutor(
                (res, rej) -> {
                    if (wasConnected) {
                        for (NostrRelayComponent listener : this.listeners) {
                            try {
                                if (!listener.onRelayDisconnect(this, reason, false)) {
                                    logger.finer("Disconnect ignored by component: " + this.url);
                                    res.accept(this);
                                    return;
                                }
                            } catch (Throwable e) {
                                rej.accept(new Exception("Disconnect cancelled by component: " + e.getMessage()));
                                return;
                            }
                        }
                    }

                    if (this.reconnectOnDrop && !this.disconnectedByClient) {
                        long now = Instant.now().getEpochSecond();
                        reconnectionBackoff.registerFailure();
                        long delay = reconnectionBackoff.getNextAttemptTime(now, TimeUnit.SECONDS);
                        this.executor.runLater(
                                () -> {
                                    this.connect();
                                    return null;
                                },
                                delay,
                                TimeUnit.SECONDS
                            );
                    }

                    res.accept(this);
                },
                true
            );
        } catch (Exception e) {
            logger.severe("Error in onConnectionClosedByServer: " + e.getMessage());
        }
    }

    // await for order
    private void onConnectionClosedByClient(String reason) {
        this.connected = false;
        this.connecting = false;
        logger.finer("Connection closed by client: " + this.url + " reason: " + reason);
        try {
            runInRelayExecutor(
                (res, rej) -> {
                    for (NostrRelayComponent listener : this.listeners) {
                        try {
                            if (!listener.onRelayDisconnect(this, reason, true)) {
                                logger.finer("Disconnect ignored by component: " + this.url);
                                res.accept(this);
                                return;
                            }
                        } catch (Throwable e) {
                            rej.accept(new Exception("Disconnect cancelled by component: " + e.getMessage()));
                            return;
                        }
                    }
                    res.accept(this);
                },
                true
            );
        } catch (Exception e) {
            logger.severe("Error in onConnectionClosedByClient: " + e.getMessage());
        }
    }

    protected void loop() {
        try {
            Instant nowInstant = Instant.now();
            long now = nowInstant.getEpochSecond();

            // remove timeouted acks
            Iterator<Map.Entry<String, NostrMessageAck>> it;

            it = waitingEventsAck.entrySet().iterator();
            while (it.hasNext()) {
                try {
                    Map.Entry<String, NostrMessageAck> entry = it.next();
                    NostrMessageAck ack = entry.getValue();
                    if (ack.getSentAt().getEpochSecond() + ackTimeoutS < now) {
                        assert dbg(() -> {
                            logger.finest("Event Ack timeout: " + ack.getId());
                        });
                        it.remove();
                        ack.callFailureCallback("Event status timeout");
                    }
                } catch (Exception e) {
                    logger.log(Level.WARNING, "Error in loop (1)", e);
                }
            }

            for (NostrRelayComponent listener : this.listeners) {
                try {
                    if (!listener.onRelayLoop(this, nowInstant)) {
                        assert dbg(() -> {
                            logger.finest("Loop ignored by component: " + this.url);
                        });
                        return;
                    }
                } catch (Throwable e) {
                    assert dbg(() -> {
                        logger.finest("Loop cancelled by component: " + e.getMessage());
                    });
                    return;
                }
            }

            if (disconnectedByClient) {
                assert dbg(() -> {
                    logger.finest("Stop loop - disconnected by client: " + this.url);
                });
                return;
            }
        } catch (Throwable e) {
            logger.severe("Error in loop: " + e.getMessage());
        }
        this.executor.runLater(
                () -> {
                    this.loop();
                    return null;
                },
                10,
                TimeUnit.SECONDS
            );
    }

    // await for order
    private void onConnectionError(Throwable e) {
        try {
            runInRelayExecutor(
                (res, rej) -> {
                    for (NostrRelayComponent listener : this.listeners) {
                        try {
                            if (!listener.onRelayError(this, e)) {
                                assert dbg(() -> {
                                    logger.finer("Error ignored by component: " + this.url);
                                });
                                res.accept(this);
                                return;
                            }
                        } catch (Throwable ex) {
                            rej.accept(new Exception("Error cancelled by component: " + ex.getMessage()));
                            return;
                        }
                    }
                },
                true
            );
        } catch (Exception e1) {
            logger.severe("Error in onConnectionError: " + e1.getMessage());
        }
    }
}
