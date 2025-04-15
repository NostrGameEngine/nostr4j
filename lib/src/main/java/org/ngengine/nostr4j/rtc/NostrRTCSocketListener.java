package org.ngengine.nostr4j.rtc;

import java.nio.ByteBuffer;

import org.ngengine.nostr4j.rtc.signal.signals.NostrRTCIceCandidate;

public interface NostrRTCSocketListener {

    
    void onRTCSocketLocalIceCandidate(NostrRTCSocket socket, NostrRTCIceCandidate candidate);

    void onRTCSocketMessage(NostrRTCSocket socket, ByteBuffer bbf, boolean turn);
    
}
