package org.ngengine.nostr4j.rtc.listeners;

import java.nio.ByteBuffer;

import org.ngengine.nostr4j.rtc.NostrRTCChannel;

public interface NostrRTCChannelListener {
    void onRTCSocketMessage(
        NostrRTCChannel channel, 
        ByteBuffer bbf,
        boolean isTurn
    );

    void onRTCChannelError(
        NostrRTCChannel channel,
        Throwable e
    );

    
    void onRTCChannelClosed(
        NostrRTCChannel channel
    );
    
    void onRTCBufferedAmountLow(
        NostrRTCChannel channel
    );

  
 
}
