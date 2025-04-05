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
package org.ngengine.nostr4j.platform.jvm;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import org.ngengine.nostr4j.listeners.TransportListener;
import org.ngengine.nostr4j.platform.AsyncTask;
import org.ngengine.nostr4j.platform.NostrExecutor;
import org.ngengine.nostr4j.transport.NostrTransport;

public class WebsocketTransport implements NostrTransport, WebSocket.Listener {

    private volatile CompletableFuture<WebSocket> ws;
    private final List<TransportListener> listeners =
        new CopyOnWriteArrayList<>();
    private volatile int maxMessageSize = 1024;
    private final StringBuilder aggregator = new StringBuilder();
    private final JVMAsyncPlatform platform;
    private final NostrExecutor executor;

    public WebsocketTransport(
        JVMAsyncPlatform platform,
        NostrExecutor executor
    ) {
        this.platform = platform;
        this.executor = executor;
    }

    @Override
    public AsyncTask<Void> ensureConnect(String url) {
        return this.platform.promisify(
                (res, rej) -> {
                    try {
                        if (this.ws == null) {
                            this.ws =
                                HttpClient
                                    .newHttpClient()
                                    .newWebSocketBuilder()
                                    .buildAsync(URI.create(url), this);
                            res.accept(null);
                        } else {
                            res.accept(null);
                        }
                    } catch (Exception e) {
                        rej.accept(e);
                    }
                },
                executor
            );
    }

    @Override
    public AsyncTask<Void> close(String reason) {
        return this.platform.promisify(
                (res, rej) -> {
                    try {
                        if (this.ws != null) {
                            final String r = reason != null
                                ? reason
                                : "Closed by client";
                            CompletableFuture<WebSocket> wsc = this.ws;
                            this.ws = null;
                            for (TransportListener listener : this.listeners) {
                                listener.onConnectionClosedByClient(reason);
                            }
                            wsc.thenAccept(ws -> {
                                ws.sendClose(WebSocket.NORMAL_CLOSURE, r);
                                res.accept(null);
                            });
                            wsc.exceptionally(e -> {
                                rej.accept(e);
                                return null;
                            });
                        } else {
                            res.accept(null);
                        }
                    } catch (Exception e) {
                        rej.accept(e);
                    }
                },
                executor
            );
    }

    @Override
    public CompletionStage<?> onText(
        WebSocket webSocket,
        CharSequence data,
        boolean last
    ) {
        aggregator.append(data);
        if (last) {
            String message = aggregator.toString();
            for (TransportListener listener : this.listeners) {
                listener.onConnectionMessage(message);
            }
            aggregator.setLength(0);
        }
        return WebSocket.Listener.super.onText(webSocket, data, last);
    }

    @Override
    public void onOpen(WebSocket webSocket) {
        for (TransportListener listener : this.listeners) {
            listener.onConnectionOpen();
        }
        WebSocket.Listener.super.onOpen(webSocket);
    }

    @Override
    public CompletionStage<?> onClose(
        WebSocket webSocket,
        int statusCode,
        String reason
    ) {
        if (this.ws != null) {
            this.ws = null;
            for (TransportListener listener : this.listeners) {
                listener.onConnectionClosedByServer(reason);
            }
        }
        return WebSocket.Listener.super.onClose(webSocket, statusCode, reason);
    }

    CompletableFuture<?> queue = CompletableFuture.completedFuture(null);

    @Override
    public AsyncTask<Void> send(String message) {
        return this.platform.promisify(
                (res, rej) -> {
                    try {
                        final CompletableFuture<WebSocket> wsReference =
                            this.ws;
                        if (wsReference == null) {
                            rej.accept(
                                new IllegalStateException(
                                    "WebSocket is not connected"
                                )
                            );
                            return;
                        }
                        wsReference.thenAccept(ws -> {
                            int messageLength = message.length();
                            if (messageLength <= this.maxMessageSize) {
                                queue =
                                    queue.thenCompose(n -> {
                                        return ws
                                            .sendText(message, true)
                                            .thenAccept(r -> res.accept(null))
                                            .exceptionally(e -> {
                                                rej.accept(e);
                                                return null;
                                            });
                                    });
                                return;
                            } else {
                                int l = message.length();
                                int sent = 0;
                                do {
                                    int end = Math.min(
                                        sent + this.maxMessageSize,
                                        l
                                    );
                                    String chunk = message.substring(sent, end);
                                    sent = end;
                                    boolean last = sent == l;
                                    queue =
                                        queue.thenCompose(n -> {
                                            return ws
                                                .sendText(chunk, last)
                                                .exceptionally(e -> {
                                                    rej.accept(e);
                                                    return null;
                                                });
                                        });
                                    if (last) {
                                        queue =
                                            queue.thenAccept(r ->
                                                res.accept(null)
                                            );
                                    }
                                } while (sent < l);
                            }
                        });
                    } catch (Exception e) {
                        rej.accept(e);
                    }
                },
                executor
            );
    }

    @Override
    public void addListener(TransportListener listener) {
        this.listeners.add(listener);
    }

    @Override
    public void removeListener(TransportListener listener) {
        this.listeners.remove(listener);
    }

    public AsyncTask<String> httpGet(String url) {
        return this.platform.promisify(
                (res, rej) -> {
                    try {
                        HttpClient client = HttpClient.newHttpClient();
                        HttpRequest request = HttpRequest
                            .newBuilder()
                            .uri(URI.create(url))
                            .GET()
                            .build();

                        client
                            .sendAsync(
                                request,
                                HttpResponse.BodyHandlers.ofString()
                            )
                            .thenAccept(response -> {
                                int statusCode = response.statusCode();
                                if (statusCode >= 200 && statusCode < 300) {
                                    res.accept(response.body());
                                } else {
                                    rej.accept(
                                        new IOException(
                                            "HTTP error: " +
                                            statusCode +
                                            " " +
                                            response.body()
                                        )
                                    );
                                }
                            })
                            .exceptionally(e -> {
                                rej.accept(e);
                                return null;
                            });
                    } catch (Exception e) {
                        rej.accept(e);
                    }
                },
                executor
            );
    }
}
