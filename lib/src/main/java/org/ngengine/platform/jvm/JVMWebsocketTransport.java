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
package org.ngengine.platform.jvm;

import static org.ngengine.platform.NGEUtils.dbg;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.ngengine.platform.AsyncTask;
import org.ngengine.platform.transport.WebsocketTransport;
import org.ngengine.platform.transport.WebsocketTransportListener;

public class JVMWebsocketTransport implements WebsocketTransport, WebSocket.Listener {

    private static final Logger logger = Logger.getLogger(JVMWebsocketTransport.class.getName());
    private static final int DEFAULT_MAX_MESSAGE_SIZE = 65_536;
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final int BUFFER_INITIAL_SIZE = 8192;

    private volatile WebSocket openWebSocket;
    private static final int maxMessageSize = DEFAULT_MAX_MESSAGE_SIZE;
    private final StringBuilder messageBuffer = new StringBuilder(BUFFER_INITIAL_SIZE);

    private final List<WebsocketTransportListener> listeners = new CopyOnWriteArrayList<>();
    private final JVMAsyncPlatform platform;
    private final HttpClient httpClient;
    private final Executor executor;

    private final AtomicReference<CompletableFuture<?>> queue = new AtomicReference<>(CompletableFuture.completedFuture(null));

    public JVMWebsocketTransport(JVMAsyncPlatform platform, Executor executor) {
        this.platform = platform;
        this.executor = executor;
        HttpClient.Builder b = HttpClient
            .newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .executor(executor);
        this.httpClient = b.build();
    }

    @Override
    public boolean isConnected() {
        return this.openWebSocket != null;
    }

    public <T> AsyncTask<T> enqueue(BiConsumer<Consumer<T>, Consumer<Throwable>> task) {
        return platform.wrapPromise((res, rej) -> {
            synchronized (queue) {
                queue.getAndUpdate(currentQueue ->
                    currentQueue.thenComposeAsync(
                        r -> {
                            CompletableFuture<T> future = new CompletableFuture<>();
                            try {
                                task.accept(
                                    r0 -> {
                                        future.complete(r0);
                                        res.accept(r0);
                                    },
                                    e -> {
                                        future.completeExceptionally(e);
                                        rej.accept(e);
                                    }
                                );
                            } catch (Exception e) {
                                future.completeExceptionally(e);
                            }
                            return future;
                        },
                        executor
                    )
                );
            }
        });
    }

    @Override
    public AsyncTask<Void> connect(String url) {
        logger.finest("Connecting to WebSocket: " + url);
        return this.platform.wrapPromise((res, rej) -> {
                httpClient
                    .newWebSocketBuilder()
                    .connectTimeout(CONNECT_TIMEOUT)
                    .buildAsync(URI.create(url), this)
                    .handle((r, e) -> {
                        if (e != null) {
                            logger.log(Level.WARNING, "WebSocket connection error", e);
                            rej.accept(e);
                        } else {
                            logger.finest("WebSocket connected: " + url);
                            res.accept(null);
                        }
                        return null;
                    });
            });
    }

    @Override
    public AsyncTask<Void> close(String reason) {
        logger.finest("Closing WebSocket: " + reason);

        WebSocket ws = this.openWebSocket;
        this.openWebSocket = null;

        for (WebsocketTransportListener listener : listeners) {
            try {
                listener.onConnectionClosedByClient(reason);
            } catch (Exception e) {
                logger.warning("Error in close listener: " + e);
            }
        }

        if (ws != null) {
            enqueue((res, rej) -> {
                try {
                    final String r = reason != null ? reason : "Closed by client";
                    ws
                        .sendClose(WebSocket.NORMAL_CLOSURE, r)
                        .handle((result, error) -> {
                            if (error != null) {
                                rej.accept(error);
                            } else {
                                res.accept(null);
                            }
                            return null;
                        });
                } catch (Exception e) {
                    rej.accept(e);
                }
            });
        }

        return platform.wrapPromise((res, rej) -> {
            res.accept(null);
        });
    }

    @Override
    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
        messageBuffer.append(data);
        if (last) {
            String message = messageBuffer.toString();
            messageBuffer.setLength(0);
            for (WebsocketTransportListener listener : listeners) {
                try {
                    listener.onConnectionMessage(message);
                } catch (Exception e) {
                    logger.warning("Error in message listener: " + e);
                }
            }
        }
        return WebSocket.Listener.super.onText(webSocket, data, last);
    }

    @Override
    public void onOpen(WebSocket webSocket) {
        logger.finest("WebSocket opened");
        assert this.openWebSocket == null : "WebSocket already open";
        this.openWebSocket = webSocket;
        for (WebsocketTransportListener listener : listeners) {
            try {
                listener.onConnectionOpen();
            } catch (Exception e) {
                logger.warning("Error in open listener: " + e);
            }
        }
        webSocket.request(1);
    }

    @Override
    public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
        logger.finest("WebSocket closed: " + statusCode + " " + reason);
        this.openWebSocket = null;
        for (WebsocketTransportListener listener : listeners) {
            try {
                listener.onConnectionClosedByServer(reason);
            } catch (Exception e) {
                logger.warning("Error in close listener: " + e);
            }
        }
        return WebSocket.Listener.super.onClose(webSocket, statusCode, reason);
    }

    @Override
    public void onError(WebSocket webSocket, Throwable error) {
        logger.warning("WebSocket error: " + error);
        for (WebsocketTransportListener listener : listeners) {
            try {
                listener.onConnectionError(error);
            } catch (Exception e) {
                logger.warning("Error in error listener: " + e);
            }
        }
        WebSocket.Listener.super.onError(webSocket, error);
    }

    @Override
    public AsyncTask<Void> send(String message) {
        WebSocket ws = this.openWebSocket;

        return platform.wrapPromise((res, rej) -> {
            try {
                if (ws == null) {
                    rej.accept(new IOException("WebSocket not connected"));
                    return;
                }
                try {
                    int messageLength = message.length();
                    // Send entirely or in chunks if needed
                    // note: we don't need to wait for the message to be sent
                    // as long as it is enqueued we can assume it is sent
                    if (messageLength <= maxMessageSize) {
                        enqueue((rs0, rj0) -> {
                            assert dbg(() -> {
                                logger.finest("Sending full message: " + message.length());
                            });
                            ws
                                .sendText(message, true)
                                .handle((result, error) -> {
                                    if (error != null) {
                                        rej.accept(error);
                                        rj0.accept(error);
                                    } else {
                                        res.accept(null);
                                        rs0.accept(null);
                                    }
                                    return null;
                                });
                        });
                    } else {
                        enqueue((rs0, rj0) -> {
                            int position = 0;
                            CompletableFuture<WebSocket> future = CompletableFuture.completedFuture(null);
                            while (position < messageLength) {
                                // send in chunks
                                int start = position;
                                int end = Math.min(position + maxMessageSize, messageLength);
                                assert dbg(() -> {
                                    logger.finest("Sending chunk: " + start + " " + end);
                                });
                                final String chunk = message.substring(start, end);
                                final boolean isLast = end >= messageLength;
                                // chain chunks
                                future =
                                    future.thenComposeAsync(
                                        r -> {
                                            return ws.sendText(chunk, isLast);
                                        },
                                        executor
                                    );
                                position = end;
                            }
                            future.handle((result, error) -> {
                                if (error != null) {
                                    rej.accept(error);
                                    rj0.accept(error);
                                } else {
                                    res.accept(null);
                                    rs0.accept(null);
                                }
                                return null;
                            });
                        });
                    }
                } catch (Exception e) {
                    rej.accept(e);
                }
            } catch (Exception e) {
                rej.accept(e);
            }
        });
    }

    @Override
    public void addListener(WebsocketTransportListener listener) {
        assert !listeners.contains(listener) : "Listener already added";
        if (listener != null) {
            listeners.add(listener);
        }
    }

    @Override
    public void removeListener(WebsocketTransportListener listener) {
        listeners.remove(listener);
    }
}
