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
import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
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
import org.ngengine.platform.ExecutionQueue;
import org.ngengine.platform.NGEPlatform;
import org.ngengine.platform.NGEUtils;
import org.ngengine.platform.transport.WebsocketTransport;
import org.ngengine.platform.transport.WebsocketTransportListener;

public final class NostrRelay {

    private static final Logger logger = Logger.getLogger(NostrRelay.class.getName());

    public enum Status {
        INITIALIZE_CONNECTION,
        WAITING_FOR_CONNECTION,
        TRYING_TO_CONNECT,
        CONNECTED,
        DISCONNECTED,
        NEW,
    }

    private static final class QueuedMessage {

        final NostrMessage message;
        final Consumer<NostrMessageAck> res;
        final Consumer<Throwable> rej;
        final int failures;

        QueuedMessage(NostrMessage message, Consumer<NostrMessageAck> res, Consumer<Throwable> rej, int failures) {
            this.message = message;
            this.res = res;
            this.rej = rej;
            this.failures = failures;
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

    private interface ConnectionCallback {
        void call(Throwable error);
    }

    protected final WebsocketTransport connector;
    protected final String url;
    protected final List<NostrRelayComponent> listeners = new CopyOnWriteArrayList<>();
    protected final Map<String, NostrMessageAck> waitingEventsAck = new ConcurrentHashMap<>();
    protected final AsyncExecutor executor;
    protected final ExecutionQueue excQueue;

    protected final ExponentialBackoff reconnectionBackoff = new ExponentialBackoff();

    protected volatile long ackTimeoutS = TimeUnit.MINUTES.toSeconds(21);
    protected volatile boolean enableAutoReconnect = true;
    protected volatile int maxSendFailures = 5;
    protected volatile boolean verifyEvents = true;
    protected volatile boolean parallelEvents = true;

    protected Status currentStatus = Status.NEW;
    protected Instant statusSince = Instant.now();
    protected Duration statusTimeout = Duration.ofSeconds(120);
    protected boolean reconnect = false;
    protected String markForDisconnection = null;

    protected synchronized void setStatus(Status s) {
        this.currentStatus = s;
        this.statusSince = Instant.now();
        assert dbg(() -> {
            logger.fine("Relay status changed: " + this.url + " " + s);
        });
    }

    public synchronized Status getStatus() {
        return this.currentStatus;
    }

    protected synchronized boolean isStatusTimeout() {
        Status status = getStatus();
        if (status != Status.TRYING_TO_CONNECT && status != Status.WAITING_FOR_CONNECTION) return false;
        return Duration.between(this.statusSince, Instant.now()).compareTo(this.statusTimeout) > 0;
    }

    protected final Queue<QueuedMessage> messageQueue;
    protected final Queue<ConnectionCallback> connectCallbacks;
    protected final Queue<ConnectionCallback> disconnectCallbacks;

    protected transient NostrRelayInfo relayInfo = null;

    public NostrRelay(String url) {
        this(url, NGEUtils.getPlatform().newAsyncExecutor(NostrRelay.class));
    }

    public NostrRelay(String url, AsyncExecutor executor) {
        try {
            NGEPlatform platform = NGEUtils.getPlatform();
            this.connector = platform.newTransport();
            this.connector.addListener(listener);
            this.messageQueue = platform.newConcurrentQueue(QueuedMessage.class);
            this.connectCallbacks = platform.newConcurrentQueue(ConnectionCallback.class);
            this.disconnectCallbacks = platform.newConcurrentQueue(ConnectionCallback.class);
            this.url = url;
            this.executor = executor;
            this.excQueue = NGEPlatform.get().newExecutionQueue();
            this.baseLoop();
        } catch (Exception e) {
            throw new RuntimeException("Error creating NostrRelay", e);
        }
    }

    // make sure the loop is called periodically even if no event is triggered
    protected void baseLoop() {
        this.executor.runLater(
                () -> {
                    loop();
                    baseLoop();
                    return null;
                },
                100,
                TimeUnit.MILLISECONDS
            );
    }

    protected <T> void runInRelayExecutor(BiConsumer<Consumer<T>, Consumer<Throwable>> runnable, boolean enqueue) {
        NGEPlatform platform = NGEUtils.getPlatform();
        if (!enqueue) {
            platform.promisify(runnable, executor);
        } else {
            this.excQueue.enqueue((res, rej) -> {
                    platform
                        .promisify(runnable, executor)
                        .then(v -> {
                            res.accept(v);
                            return null;
                        })
                        .catchException(e -> {
                            rej.accept(e);
                        });
                });
        }
    }

    public void resetConnection() {
        setStatus(reconnect ? Status.WAITING_FOR_CONNECTION : Status.DISCONNECTED);
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

    public void setAutoReconnect(boolean reconnect) {
        this.enableAutoReconnect = reconnect;
    }

    public boolean isAutoReconnect() {
        return this.enableAutoReconnect;
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
        return getStatus() != Status.DISCONNECTED;
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

    public AsyncTask<NostrMessageAck> sendMessage(NostrMessage message) {
        return sendMessage(message, 0);
    }

    protected AsyncTask<NostrMessageAck> sendMessage(NostrMessage message, int failures) {
        NGEPlatform platform = NGEUtils.getPlatform();
        return platform.wrapPromise((ores, orej) -> {
            runInRelayExecutor(
                (rr0, rxj0) -> {
                    try {
                        if (getStatus() != Status.CONNECTED) {
                            assert dbg(() -> {
                                logger.finer("Relay not connected, queueing message: " + message.toString());
                            });

                            QueuedMessage q = new QueuedMessage(message, ores, orej, failures);
                            this.messageQueue.add(q);
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
                                    return;
                                }
                            } catch (Throwable e) {
                                result.callFailureCallback("message cancelled by component " + e.getMessage());
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
                                    if (failures + 1 >= maxSendFailures) {
                                        logger.log(Level.WARNING, "Error sending message", e);
                                        result.callFailureCallback(e.getMessage());
                                    } else {
                                        if (eventId != null) {
                                            waitingEventsAck.remove(eventId);
                                        }
                                        logger.log(Level.WARNING, "Error sending message, will retry", e);
                                        QueuedMessage q = new QueuedMessage(message, ores, orej, failures + 1);
                                        this.messageQueue.add(q);
                                        loop();
                                    }
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
                            if (eventId != null) this.waitingEventsAck.remove(eventId);
                            if (failures + 1 >= maxSendFailures) {
                                result.callFailureCallback(e.getMessage());
                            } else {
                                this.messageQueue.add(new QueuedMessage(message, ores, orej, failures + 1));
                                loop();
                            }
                        }
                    } catch (Throwable e) {
                        logger.log(Level.WARNING, "Error sending message (1)", e);
                    } finally {
                        rr0.accept(this);
                    }
                },
                true
            );
            loop();
        });
    }

    public String getUrl() {
        return url;
    }

    public AsyncTask<NostrRelay> connect() {
        NGEPlatform platform = NGEUtils.getPlatform();
        return platform.wrapPromise((res, rej) -> {
            runInRelayExecutor(
                (r0, rj0) -> {
                    reconnect = enableAutoReconnect;
                    try {
                        Status status = getStatus();
                        if (status == Status.DISCONNECTED || status == Status.NEW) {
                            setStatus(Status.INITIALIZE_CONNECTION);
                        }
                        connectCallbacks.add(err -> {
                            if (err != null) {
                                rej.accept(err);
                            } else {
                                res.accept(this);
                            }
                        });
                    } finally {
                        r0.accept(this);
                    }
                },
                true
            );
            loop();
        });
    }

    protected boolean isMarkedForDisconnection() {
        return this.markForDisconnection != null;
    }

    public AsyncTask<NostrRelay> disconnect(String reason) {
        return disconnect(reason, false);
    }

    public AsyncTask<NostrRelay> disconnect(String reason, boolean reconnect) {
        NGEPlatform platform = NGEUtils.getPlatform();
        return platform.wrapPromise((ores, orej) -> {
            runInRelayExecutor(
                (rs0, rj0) -> {
                    try {
                        this.reconnect = reconnect;
                        markForDisconnection = reason;
                        disconnectCallbacks.add(err -> {
                            if (err != null) {
                                orej.accept(err);
                            } else {
                                ores.accept(this);
                            }
                        });
                    } catch (Throwable e) {
                        logger.log(Level.WARNING, "Error disconnecting", e);
                    } finally {
                        rs0.accept(this);
                    }
                    // ores.accept(this);
                },
                true
            );
            loop();
        });
    }

    private void onConnectionOpen() {
        runInRelayExecutor(
            (res, rej) -> {
                try {
                    logger.fine("Connection opened: " + this.url);
                    for (NostrRelayComponent listener : this.listeners) {
                        try {
                            if (!listener.onRelayConnect(this)) {
                                logger.finer("Connection ignored by component: " + this.url);
                                break;
                            }
                        } catch (Throwable e) {
                            logger.finer("Connection cancelled by component: " + e.getMessage());
                        }
                    }

                    setStatus(Status.CONNECTED);
                    reconnectionBackoff.registerSuccess();
                } catch (Throwable e) {
                    assert dbg(() -> {
                        logger.log(Level.WARNING, "Error in connect callback", e);
                    });
                } finally {
                    res.accept(this);
                }
            },
            true
        );
        loop();
    }

    private void onConnectionMessage(String msg) {
        try {
            NGEPlatform platform = NGEUtils.getPlatform();
            assert dbg(() -> {
                logger.finest("Received message: " + msg);
            });
            List<Object> data = platform.fromJSON(msg, List.class);
            String prefix = NGEUtils.safeString(data.get(0));

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
                (r0, rj0) -> {
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
                                    return;
                                }
                            } catch (Throwable e) {
                                logger.log(Level.WARNING, "Message cancelled by component: " + e.getMessage(), e);
                                return;
                            }
                        }
                    } catch (Exception e) {
                        assert dbg(() -> {
                            logger.log(Level.WARNING, "Error processing message", e);
                        });
                    } finally {
                        r0.accept(this);
                    }
                },
                true
            );
            loop();
        } catch (Exception e) {
            logger.severe("Error in onConnectionMessage: " + e.getMessage());
        }
    }

    private void onConnectionClosedByServer(String reason) {
        logger.finer("Connection closed by server: " + this.url + " reason: " + reason);
        try {
            runInRelayExecutor(
                (r0, rj0) -> {
                    try {
                        Status oldStatus = getStatus();
                        if (oldStatus == Status.DISCONNECTED) {
                            return;
                        }
                        if (oldStatus == Status.CONNECTED) {
                            for (NostrRelayComponent listener : this.listeners) {
                                try {
                                    if (!listener.onRelayDisconnect(this, reason, false)) {
                                        logger.finer("Disconnect ignored by component: " + this.url);
                                        break;
                                    }
                                } catch (Throwable e) {
                                    logger.log(Level.WARNING, "Disconnect cancelled by component: " + e.getMessage(), e);
                                }
                            }
                        }
                        resetConnection();
                    } finally {
                        r0.accept(this);
                    }
                },
                true
            );
            loop();
        } catch (Exception e) {
            logger.severe("Error in onConnectionClosedByServer: " + e.getMessage());
        }
    }

    private void onConnectionClosedByClient(String reason) {
        logger.finer("Connection closed by client: " + this.url + " reason: " + reason);
        try {
            runInRelayExecutor(
                (r0, rj0) -> {
                    try {
                        for (NostrRelayComponent listener : this.listeners) {
                            try {
                                if (!listener.onRelayDisconnect(this, reason, true)) {
                                    logger.finer("Disconnect ignored by component: " + this.url);
                                    return;
                                }
                            } catch (Throwable e) {
                                logger.log(Level.WARNING, "Disconnect cancelled by component: " + e.getMessage(), e);
                                return;
                            }
                        }
                        resetConnection();
                    } finally {
                        r0.accept(this);
                    }
                },
                true
            );
            loop();
        } catch (Exception e) {
            logger.severe("Error in onConnectionClosedByClient: " + e.getMessage());
        }
    }

    protected void loop() {
        runInRelayExecutor(
            (r0, ej0) -> {
                try {
                    Instant nowInstant = Instant.now();
                    long now = nowInstant.getEpochSecond();

                    if (isStatusTimeout()) {
                        try {
                            this.connector.close("connection timeout");
                        } catch (Throwable ignore) {}
                        resetConnection();
                    }

                    // remove timeouted acks
                    try {
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
                                    if (waitingEventsAck.remove(entry.getKey(), entry.getValue())) {
                                        ack.callFailureCallback("Event status timeout");
                                    }
                                }
                            } catch (Exception e) {
                                logger.log(Level.WARNING, "Error when cleaning ack", e);
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
                    } catch (Throwable e) {
                        logger.log(Level.SEVERE, "Error when cleaning acks", e);
                    }

                    Status status = getStatus();
                    if (status == Status.NEW && isMarkedForDisconnection()) {
                        setStatus(Status.DISCONNECTED);
                    }

                    status = getStatus();
                    if (status == Status.INITIALIZE_CONNECTION) {
                        boolean canConnect = true;
                        for (NostrRelayComponent listener : this.listeners) {
                            try {
                                if (!listener.onRelayConnectRequest(this)) {
                                    logger.finer("Connection ignored by component: " + this.url);
                                    canConnect = false;
                                }
                            } catch (Throwable e) {
                                logger.finer("Connection cancelled by component: " + e.getMessage());
                                canConnect = false;
                            }
                        }
                        if (canConnect) {
                            setStatus(Status.WAITING_FOR_CONNECTION);
                        } else {
                            if (!reconnect) setStatus(Status.DISCONNECTED);
                        }
                    }

                    status = getStatus();
                    if (status == Status.WAITING_FOR_CONNECTION) {
                        setStatus(Status.TRYING_TO_CONNECT);
                        Duration delay = reconnectionBackoff.getDelay(Instant.now());
                        reconnectionBackoff.registerAttempt();
                        if (delay.toMillis() == 0) {
                            this.connector.connect(this.url)
                                .catchException(e -> {
                                    logger.log(Level.WARNING, "Error connecting to relay: " + this.url, e);
                                    runInRelayExecutor(
                                        (a, b) -> {
                                            try {
                                                resetConnection();
                                            } finally {
                                                a.accept(this);
                                            }
                                        },
                                        true
                                    );
                                    loop();
                                });
                        } else {
                            this.executor.runLater(
                                    () -> {
                                        if (getStatus() != Status.TRYING_TO_CONNECT) {
                                            return null;
                                        }
                                        this.connector.connect(this.url)
                                            .catchException(e -> {
                                                logger.log(Level.WARNING, "Error connecting to relay: " + this.url, e);
                                                runInRelayExecutor(
                                                    (a, b) -> {
                                                        try {
                                                            resetConnection();
                                                        } finally {
                                                            a.accept(this);
                                                        }
                                                    },
                                                    true
                                                );
                                                loop();
                                            });
                                        return null;
                                    },
                                    delay.toMillis(),
                                    TimeUnit.MILLISECONDS
                                );
                        }
                    }

                    status = getStatus();
                    if (!this.connectCallbacks.isEmpty() && status == Status.CONNECTED) {
                        ConnectionCallback cb;
                        while ((cb = this.connectCallbacks.poll()) != null) {
                            try {
                                cb.call(null);
                            } catch (Throwable e) {
                                assert dbg(() -> {
                                    logger.log(Level.WARNING, "Error in connect callback", e);
                                });
                            }
                        }
                    }

                    status = getStatus();
                    if (status == Status.CONNECTED && markForDisconnection != null) {
                        boolean canDisconnect = true;
                        logger.fine("Disconnecting from relay: " + this + " reason: " + markForDisconnection);
                        for (NostrRelayComponent listener : this.listeners) {
                            try {
                                if (!listener.onRelayDisconnectRequest(this, markForDisconnection)) {
                                    logger.finer("Disconnect ignored by component: " + this.url);
                                    canDisconnect = false;
                                }
                            } catch (Throwable e) {
                                canDisconnect = false;
                            }
                        }
                        if (canDisconnect) {
                            try {
                                this.connector.close("client disconnect");
                            } catch (Throwable err) {
                                logger.log(Level.WARNING, "Error disconnecting", err);
                            }
                            resetConnection();
                            markForDisconnection = null;
                        }
                    }

                    status = getStatus();
                    if (!this.disconnectCallbacks.isEmpty() && status == Status.DISCONNECTED) {
                        ConnectionCallback cb;
                        while ((cb = this.disconnectCallbacks.poll()) != null) {
                            try {
                                cb.call(status != Status.DISCONNECTED ? new Exception("connected") : null);
                            } catch (Throwable e) {
                                assert dbg(() -> {
                                    logger.log(Level.WARNING, "Error in connect callback", e);
                                });
                            }
                        }
                    }

                    status = getStatus();
                    if (status == Status.CONNECTED && !this.messageQueue.isEmpty()) {
                        QueuedMessage q1;
                        while ((q1 = this.messageQueue.poll()) != null) {
                            QueuedMessage q = q1;
                            assert dbg(() -> {
                                logger.finer("Sending queued message: " + q.message);
                            });
                            NostrMessage message = q.message;
                            Consumer<NostrMessageAck> rs = q.res;
                            Consumer<Throwable> rj = q.rej;
                            int failures = q.failures;
                            try {
                                this.sendMessage(message, failures)
                                    .catchException(rj::accept)
                                    .then(v -> {
                                        rs.accept(v);
                                        return null;
                                    });
                            } catch (Throwable e) {
                                logger.log(Level.WARNING, "Error sending queued message", e);
                            }
                        }
                    }
                } catch (Throwable e) {
                    logger.severe("Error in loop: " + e.getMessage());
                } finally {
                    r0.accept(this);
                }
            },
            true
        );
    }

    private void onConnectionError(Throwable e) {
        try {
            runInRelayExecutor(
                (r0, rj0) -> {
                    try {
                        for (NostrRelayComponent listener : this.listeners) {
                            try {
                                if (!listener.onRelayError(this, e)) {
                                    assert dbg(() -> {
                                        logger.finer("Error ignored by component: " + this.url);
                                    });
                                    break;
                                }
                            } catch (Throwable ex) {
                                logger.log(Level.WARNING, "Error cancelled by component: " + ex.getMessage(), ex);
                            }
                        }
                        resetConnection();
                    } finally {
                        r0.accept(this);
                    }
                },
                true
            );
            loop();
        } catch (Exception e1) {
            logger.severe("Error in onConnectionError: " + e1.getMessage());
        }
    }

    @Override
    public String toString() {
        return "NostrRelay{" + "url='" + url + '\'' + ", status=" + getStatus() + "} @" + this.hashCode();
    }
}
