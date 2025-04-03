package org.ngengine.nostr4j.listeners;


public interface TransportListener {
    public void onConnectionClosedByServer(String reason);
    public void onConnectionOpen();
    public void onConnectionMessage(String msg);

    public void onConnectionClosedByClient(String reason);
    
}
