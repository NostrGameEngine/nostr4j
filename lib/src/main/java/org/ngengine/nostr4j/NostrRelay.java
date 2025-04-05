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

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.ngengine.nostr4j.NostrRelay.QueuedMessage;
import org.ngengine.nostr4j.NostrSubscription.NostrSubCloseMessage;
import org.ngengine.nostr4j.event.SignedNostrEvent;
import org.ngengine.nostr4j.listeners.NostrRelayListener;
import org.ngengine.nostr4j.listeners.TransportListener;
import org.ngengine.nostr4j.platform.AsyncTask;
import org.ngengine.nostr4j.platform.NostrExecutor;
import org.ngengine.nostr4j.platform.Platform;
import org.ngengine.nostr4j.transport.NostrMessage;
import org.ngengine.nostr4j.transport.NostrMessageAck;
import org.ngengine.nostr4j.transport.NostrTransport;
import org.ngengine.nostr4j.utils.ExponentialBackoff;
import org.ngengine.nostr4j.utils.NostrUtils;

public class NostrRelay implements TransportListener {

    private static final Logger logger = Logger.getLogger(
        NostrRelay.class.getName()
    );

    static class QueuedMessage {

        final NostrMessage message;
        final Consumer<NostrMessageAck> res;
        final Consumer<Throwable> rej;

        QueuedMessage(
            NostrMessage message,
            Consumer<NostrMessageAck> res,
            Consumer<Throwable> rej
        ) {
            this.message = message;
            this.res = res;
            this.rej = rej;
        }
    }

    protected final List<QueuedMessage> messageQueue = new ArrayList<>();
    protected final NostrTransport connector;
    protected final String url;
    protected final List<NostrRelayListener> listeners = new ArrayList<>();
    protected final Map<String, NostrMessageAck> waitingClosesAck =
        new ConcurrentHashMap<>();
    protected final Map<String, NostrMessageAck> waitingEventsAck =
        new ConcurrentHashMap<>();
    protected final NostrExecutor executor;
    protected final CopyOnWriteArrayList<String> subTracker =
        new CopyOnWriteArrayList<>();
    protected final ExponentialBackoff reconnectionBackoff =
        new ExponentialBackoff();

    protected volatile long ackTimeoutS = TimeUnit.MINUTES.toSeconds(21);
    protected volatile long lastAction;
    protected volatile boolean reconnectOnDrop = true;
    protected volatile boolean disconnectedByClient = false;
    protected volatile boolean connected = false;
    protected volatile long keepAliveTime = TimeUnit.MINUTES.toSeconds(2);

    public NostrRelay(String url) {
        try {
            Platform platform = NostrUtils.getPlatform();
            this.connector = platform.newTransport();
            this.connector.addListener(this);
            this.executor = platform.newRelayExecutor();
            this.url = url;
        } catch (Exception e) {
            throw new RuntimeException("Error creating NostrRelay", e);
        }
    }

    public NostrExecutor getExecutor() {
        return this.executor;
    }

    public void setKeepAliveTime(long time, TimeUnit unit) {
        this.keepAliveTime = unit.toSeconds(time);
    }

    public long getKeepAliveTime(TimeUnit outputUnit) {
        return outputUnit.convert(this.keepAliveTime, TimeUnit.SECONDS);
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

    public void addListener(NostrRelayListener listener) {
        this.listeners.add(listener);
    }

    public void removeListener(NostrRelayListener listener) {
        this.listeners.remove(listener);
    }

    public boolean isConnected() {
        return (
            this.connected ||
            (!this.disconnectedByClient && this.reconnectOnDrop)
        );
    }

    public AsyncTask<NostrMessageAck> sendMessage(NostrMessage message) {
        // ensure the relay is connected
        this.connect();
        ///
        Platform platform = NostrUtils.getPlatform();

        if (!this.connected) {
            logger.log(
                Level.FINE,
                "Relay not connected, queueing message: " + message.toString()
            );
            return platform.promisify((res, rej) -> {
                this.messageQueue.add(new QueuedMessage(message, res, rej));
            }, getExecutor());
        }
        return platform.promisify((res, rej) -> {
            try {
                if (message instanceof NostrSubscription) {
                    // track sub if not already tracked, otherwise ignore
                    // this servers two purposes:
                    // 1. track how many subscriptions are active to decide when to disconnect the relay
                    // 2. make the code upstream simpler as it won't have to worry about which subscriptions
                    //    are active on which relay or when the relay was added to the pool etc
                    //    you just subscribe to everything and the relay will automatically ignore duplicates
                    if (
                        subTracker.addIfAbsent(
                            ((NostrSubscription) message).getSubId()
                        )
                    ) {
                        logger.log(
                            Level.FINE,
                            "Tracking new subscription: " +
                            ((NostrSubscription) message).getSubId()
                        );
                    } else {
                        logger.log(
                            Level.FINE,
                            "Subscription already tracked: " +
                            ((NostrSubscription) message).getSubId()
                        );
                        new Exception().printStackTrace();
                        return;
                    }
                } else if (
                    message instanceof NostrSubscription.NostrSubCloseMessage
                ) {
                    subTracker.remove(
                        (
                            (NostrSubscription.NostrSubCloseMessage) message
                        ).getId()
                    );
                    logger.log(
                        Level.FINE,
                        "Untracking subscription: " +
                        (
                            (NostrSubscription.NostrSubCloseMessage) message
                        ).getId()
                    );
                }

                String json = NostrMessage.toJSON(message);
                String eventId = message instanceof SignedNostrEvent
                    ? ((SignedNostrEvent) message).getId()
                    : null;
                String closeId = message instanceof NostrSubCloseMessage
                    ? ((NostrSubCloseMessage) message).getId()
                    : null;
                logger.log(Level.FINE, "Sending message: " + json);
                NostrMessageAck result = NostrMessage.ack(
                    this,
                    eventId != null ? eventId : closeId,
                    platform.getTimestampSeconds(),
                    (rr, msg) -> {
                        if (eventId != null) {
                            this.waitingEventsAck.remove(eventId);
                        } else if (closeId != null) {
                            this.waitingClosesAck.remove(closeId);
                        }
                        logger.log(Level.FINE, "Received ack: " + msg);
                        rr.setMessage(msg);
                        rr.setSuccess(true);
                        res.accept(rr);
                    },
                    (rr, msg) -> {
                        if (eventId != null) {
                            this.waitingEventsAck.remove(eventId);
                        } else if (closeId != null) {
                            this.waitingClosesAck.remove(closeId);
                        }
                        logger.log(
                            Level.FINE,
                            "Received ack (rejected): " + msg
                        );
                        rr.setMessage(msg);
                        rr.setSuccess(false);
                        res.accept(rr);
                    }
                );

                if (eventId != null) {
                    this.waitingEventsAck.put(eventId, result);
                } else if (closeId != null) {
                    this.waitingClosesAck.put(closeId, result);
                }

                try {
                    this.connector.send(json)
                        .exceptionally(e -> {
                            logger.log(
                                Level.FINE,
                                "Error sending message: " + e.getMessage()
                            );
                            result.callFailureCallback(e.getMessage());
                        })
                        .then(vo -> {
                            logger.log(Level.FINE, "Message sent: " + json);
                            if (eventId == null && closeId == null) {
                                result.callSuccessCallback("ok");
                            }
                            return null;
                        });
                } catch (Throwable e) {
                    logger.log(
                        Level.FINE,
                        "Error sending message (0): " + e.getMessage()
                    );
                    result.callFailureCallback(e.getMessage());
                }
            } catch (Throwable e) {
                rej.accept(e);
            }
        }, getExecutor());
    }

    public String getUrl() {
        return url;
    }

    public void connect() {
        if (!this.connected) {
            this.disconnectedByClient = false;
            logger.log(Level.FINE, "Connecting to relay: " + this.url);
            this.keepAlive();
            this.connector.ensureConnect(url);
            this.loop();
        }
    }

    public void disconnect(String reason) {
        this.connected = false;
        this.disconnectedByClient = true;
        logger.log(
            Level.FINE,
            "Disconnecting from relay: " + this.url + " reason: " + reason
        );
        this.subTracker.clear(); // clear all subscriptions
        this.connector.close(reason);
    }

    @Override
    public void onConnectionOpen() {
        this.connected = true;

        logger.log(Level.FINE, "Connection opened: " + this.url);

    
        QueuedMessage messages[] =
            this.messageQueue.toArray(
                    new QueuedMessage[this.messageQueue.size()]
                );
        this.messageQueue.clear();

        for (NostrRelayListener listener : this.listeners) {
            listener.onRelayConnect(this);
        }
     
        for (QueuedMessage q : messages) {
            logger.fine("Sending queued message: " + q.message);
            NostrMessage message = q.message;
            Consumer<NostrMessageAck> res = q.res;
            Consumer<Throwable> rej = q.rej;
            try {
                this.sendMessage(message)
                    .exceptionally(t -> {
                        rej.accept(t);
                    })
                    .then(v -> {
                        res.accept(v);
                        return null;
                    });
            } catch (Throwable e) {
                rej.accept(e);
            }
        }
    }

    @Override
    public void onConnectionMessage(String msg) {
        try {
            if (msg.isEmpty()) return;
            Platform platform = NostrUtils.getPlatform();
            logger.log(Level.FINE, "Received message: " + msg);
            List<Object> data = platform.fromJSON(msg, List.class);
            String prefix = NostrUtils.safeString(data.get(0));

            // handle acks
            switch (prefix) {
                case "OK":
                    {
                        String eventId = NostrUtils.safeString(data.get(1));
                        boolean success = (Boolean) data.get(2);
                        String eventMessage = data.size() > 3
                            ? NostrUtils.safeString(data.get(3))
                            : "";
                        NostrMessageAck ack =
                            this.waitingEventsAck.get(eventId);
                        if (ack != null) {
                            logger.log(
                                Level.FINE,
                                "Received ack for event: " + eventId
                            );
                            ack.setSuccess(success);
                            ack.setMessage(eventMessage);
                            if (success) {
                                ack.callSuccessCallback(eventMessage);
                            } else {
                                ack.callFailureCallback(eventMessage);
                            }
                        } else {
                            logger.log(
                                Level.FINE,
                                "Received ack for unknown event: " + eventId
                            );
                        }
                        break;
                    }
                case "CLOSED":
                    {
                        String subId = NostrUtils.safeString(data.get(1));
                        this.subTracker.remove(subId);
                        String message = data.size() > 2
                            ? NostrUtils.safeString(data.get(2))
                            : "";
                        NostrMessageAck ack = this.waitingClosesAck.get(subId);
                        if (ack != null) {
                            logger.log(
                                Level.FINE,
                                "Received ack for close: " + subId
                            );
                            ack.setSuccess(true);
                            ack.setMessage(message);
                            ack.callSuccessCallback(message);
                        } else {
                            logger.log(
                                Level.FINE,
                                "Received ack for unknown close: " + subId
                            );
                        }
                        break;
                    }
                case "EVENT":
                    {
                        logger.log(Level.FINE, "Received event: " + msg);
                        this.keepAlive();
                        break;
                    }
            }

            // propagate event to listeners
            for (NostrRelayListener listener : this.listeners) {
                listener.onRelayMessage(this, data);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public void onConnectionClosedByServer(String reason) {
        this.connected = false;
        logger.log(
            Level.FINE,
            "Connection closed by server: " + this.url + " reason: " + reason
        );
        this.subTracker.clear(); // clear all subscriptions
        if (this.reconnectOnDrop && !this.disconnectedByClient) {
            long now = TimeUnit.MILLISECONDS.toSeconds(
                System.currentTimeMillis()
            );
            long delay = reconnectionBackoff.getNextAttemptTime(
                now,
                TimeUnit.SECONDS
            );
            this.executor.runLater(
                    () -> {
                        this.connect();

                        return null;
                    },
                    delay,
                    TimeUnit.SECONDS
                );
        }
    }

    @Override
    public void onConnectionClosedByClient(String reason) {
        this.connected = false;
        logger.log(
            Level.FINE,
            "Connection closed by client: " + this.url + " reason: " + reason
        );
        this.subTracker.clear(); // clear all subscriptions
    }

    protected void loop() {
        long now = TimeUnit.MILLISECONDS.toSeconds(System.currentTimeMillis());

        // remove timeouted acks
        Iterator<Map.Entry<String, NostrMessageAck>> it = waitingClosesAck
            .entrySet()
            .iterator();
        while (it.hasNext()) {
            try {
                Map.Entry<String, NostrMessageAck> entry = it.next();
                NostrMessageAck ack = entry.getValue();
                if (ack.sentAt + ackTimeoutS < now) {
                    logger.log(Level.FINE, "Close Ack timeout: " + ack.id);
                    it.remove();
                    ack.callFailureCallback("Event status timeout");
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        it = waitingEventsAck.entrySet().iterator();
        while (it.hasNext()) {
            try {
                Map.Entry<String, NostrMessageAck> entry = it.next();
                NostrMessageAck ack = entry.getValue();
                if (ack.sentAt + ackTimeoutS < now) {
                    logger.log(Level.FINE, "Event Ack timeout: " + ack.id);
                    it.remove();
                    ack.callFailureCallback("Event status timeout");
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        // disconnect if no active subscriptions and last action is older than 2 minutes

        if (
            this.subTracker.isEmpty() && now - this.lastAction > keepAliveTime
        ) {
            logger.log(
                Level.FINE,
                "Disconnecting from relay: " + this.url + " for inactivity"
            );
            this.disconnect("timeout");
        }

        if (disconnectedByClient) {
            logger.log(
                Level.FINE,
                "Stop loop - disconnected by client: " + this.url
            );
            return;
        }
        this.executor.runLater(
                () -> {
                    this.loop();
                    return null;
                },
                1,
                TimeUnit.SECONDS
            );
    }

    public void keepAlive() {
        this.lastAction =
            TimeUnit.MILLISECONDS.toSeconds(System.currentTimeMillis());
        logger.log(Level.FINE, "Keep alive: " + this.url);
    }
}
