package org.ngengine.nostr4j.transport;

import java.util.function.BiConsumer;

import org.ngengine.nostr4j.NostrRelay;

public class NostrMessageAck {
    public final long sentAt;
    public boolean success;
    public String message;
    public final String id;
    protected final NostrRelay relay;    
    protected final BiConsumer<NostrMessageAck, String> successCallback;
    protected final BiConsumer<NostrMessageAck, String> failureCallback;

    NostrMessageAck(
        NostrRelay relay,
        String id, 
        long sentAt,
        BiConsumer<NostrMessageAck, String> successCallback, 
        BiConsumer<NostrMessageAck, String> failureCallback
    ) {
        this.id = id;
        this.sentAt = sentAt;
        this.successCallback = successCallback;
        this.failureCallback = failureCallback;
        this.relay = relay;
    }

    public void callSuccessCallback(String message) {
        if (successCallback != null) {
            successCallback.accept(this, message);
        }
    }

    public  void callFailureCallback(String message) {
        if (failureCallback != null) {
            failureCallback.accept(this, message);
        }
    }

    public  void setSuccess(boolean success) {
        this.success = success;
    }

    public  void setMessage(String message) {
        this.message = message;
    }

    public NostrRelay get() throws Throwable {
        if (success)
            return relay;
        throw new Exception(message);
    }
    

}
