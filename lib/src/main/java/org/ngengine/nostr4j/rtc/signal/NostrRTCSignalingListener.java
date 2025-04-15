package org.ngengine.nostr4j.rtc.signal;

import org.ngengine.nostr4j.rtc.signal.signals.NostrRTCAnnounce;
import org.ngengine.nostr4j.rtc.signal.signals.NostrRTCAnswer;
import org.ngengine.nostr4j.rtc.signal.signals.NostrRTCIceCandidate;
import org.ngengine.nostr4j.rtc.signal.signals.NostrRTCOffer;

public interface NostrRTCSignalingListener {
  public static enum RemoveReason {
            EXPIRED,
            DISCONNECTED,
            UNKNOWN
        }

        void onAddAnnounce(NostrRTCAnnounce announce);

        void onUpdateAnnounce(NostrRTCAnnounce announce);

        void onRemoveAnnounce(NostrRTCAnnounce announce, RemoveReason reason);
        
        void onReceiveOffer(NostrRTCAnnounce announce, NostrRTCOffer offer);

        void onReceiveAnswer(NostrRTCAnnounce announce, NostrRTCAnswer answer);
        void onReceiveCandidates(NostrRTCAnnounce announce, NostrRTCIceCandidate candidate);
 
    
}
