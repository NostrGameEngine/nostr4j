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
import org.ngengine.nostr4j.transport.NostrTransport;

public class WebsocketTransport implements NostrTransport, WebSocket.Listener{
    private volatile CompletableFuture<WebSocket> ws;
    private final List<TransportListener> listeners = new CopyOnWriteArrayList<>();
    private volatile int maxMessageSize = 1024;
    private final StringBuilder aggregator = new StringBuilder();
    private final JVMAsyncPlatform platform;

    public WebsocketTransport(JVMAsyncPlatform platform){
        this.platform = platform;
    }

 
    @Override
    public AsyncTask<Void> ensureConnect(String url){
        return this.platform.promisify((res,rej)->{
            try{
                if(this.ws == null){
                    this.ws = HttpClient
                        .newHttpClient()
                        .newWebSocketBuilder()
                        .buildAsync(URI.create(url), this);
                    res.accept(null);
                } else{
                    res.accept(null);
                }
            }catch(Exception e){
                rej.accept(e);
            }
        });
    }

    @Override
    public AsyncTask<Void> close(String reason){
        return this.platform.promisify((res,rej)->{
            try{
                if(this.ws != null){
                    final String r = reason !=null ? reason :  "Closed by client";
                    CompletableFuture<WebSocket> wsc = this.ws;
                    this.ws = null;
                    for(TransportListener listener : this.listeners){
                        listener.onConnectionClosedByClient(reason);
                    }
                    wsc.thenAccept(ws -> {
                        ws.sendClose(WebSocket.NORMAL_CLOSURE, r);
                        res.accept(null);
                    });
                    wsc.exceptionally(e->{
                        rej.accept(e);
                        return null;
                    });
                }else{
                    res.accept(null);
                }   
            }catch(Exception e){
                rej.accept(e);
            }
        });
    }
    
    @Override
    public CompletionStage<?> onText(WebSocket webSocket,  CharSequence data, boolean last) {
        aggregator.append(data);
        if(last){
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
    public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason)  {
        if(this.ws!=null){
            this.ws = null;
            for (TransportListener listener : this.listeners) {
                listener.onConnectionClosedByServer(reason);
            }
        }
        return WebSocket.Listener.super.onClose(webSocket, statusCode, reason);
    }

    @Override
        public AsyncTask<Void> send(String message) {
        return this.platform.promisify((res,rej)->{
            try{
                int l = message.length();
                int sent = 0;
                CompletableFuture<WebSocket> ws = this.ws;
                do{
                    int end = Math.min(sent + this.maxMessageSize, l);
                    String chunk = message.substring(sent, end);
                    sent = end;
                    boolean last = sent == l;
                    ws = ws.thenCompose(wsc->{
                        return wsc.sendText(chunk, last);
                    });            
                }while(sent < l);         
                ws.thenAccept(wsc->{
                    res.accept(null);
                });
                ws.exceptionally(e->{
                    rej.accept(e);
                    return null;
                });
            }catch(Exception e){
                rej.accept(e);
            }
        });
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
        return this.platform.promisify((res, rej) -> {
            try {
                HttpClient client = HttpClient.newHttpClient();
                HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .GET()
                    .build();
                
                client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .thenAccept(response -> {
                        int statusCode = response.statusCode();
                        if (statusCode >= 200 && statusCode < 300) {
                            res.accept(response.body());
                        } else {
                            rej.accept(new IOException("HTTP error: " + statusCode + " " + 
                                response.body()));
                        }
                    })
                    .exceptionally(e -> {
                        rej.accept(e);
                        return null;
                    });
            } catch (Exception e) {
                rej.accept(e);
            }
        });
    }
        
}
