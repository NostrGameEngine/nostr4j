package org.ngengine.nostr4j.transport.rtc;

public interface RTCSessionDescription {
    String getSdp();
    String getType();
}
