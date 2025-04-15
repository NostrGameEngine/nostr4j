package org.ngengine.nostr4j.rtc.turn;

import java.nio.ByteBuffer;

import org.ngengine.nostr4j.rtc.NostrRTCPeer;

public interface NostrTURNListener {

    void onTurnPacket(NostrRTCPeer peer, ByteBuffer data);
}
