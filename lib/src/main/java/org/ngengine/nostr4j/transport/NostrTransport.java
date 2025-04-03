package org.ngengine.nostr4j.transport;


import org.ngengine.nostr4j.listeners.TransportListener;
import org.ngengine.nostr4j.platform.AsyncTask;

public interface NostrTransport {
    public AsyncTask<Void> close(String reason);
    
    public AsyncTask<Void> ensureConnect(String url);
    
    public  AsyncTask<Void>  send(String message);

    public void addListener(TransportListener listener);
    
    public void removeListener(TransportListener listener);


    public AsyncTask<String> httpGet(String url);

}
