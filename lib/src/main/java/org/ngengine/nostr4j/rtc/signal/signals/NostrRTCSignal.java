package org.ngengine.nostr4j.rtc.signal.signals;

import java.io.Serializable;
import java.util.Map;

public interface NostrRTCSignal extends Serializable{
        public Map<String, Object> get();
}
