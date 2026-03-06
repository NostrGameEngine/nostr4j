package org.ngengine.nostr4j.rtc.turn;

import java.nio.ByteBuffer;

import org.ngengine.nostr4j.rtc.NostrRTCChannel;

public interface NostrTURNChannelListener {
    void onTurnChannelReady(NostrTURNChannel channel);

    void onTurnChannelClosed(NostrTURNChannel channel, String reason);

    void onTurnChannelError(
        NostrTURNChannel channel,
        Throwable e
    );

    void onTurnChannelMessage(
        NostrTURNChannel channel,
        ByteBuffer payload
    );
}
