/**
 * BSD 3-Clause License
 *
 * Copyright (c) 2025, Riccardo Balbo
 */
package org.ngengine.nostr4j.rtc.routing;

import java.nio.ByteBuffer;
import org.ngengine.nostr4j.rtc.NostrRTCChannel;
import org.ngengine.platform.AsyncTask;

/**
 * Internal channel integration point. It is not exposed by room, socket, or
 * channel public APIs.
 */
public interface InternalRoutedTransport {
    boolean isRouteReady(NostrRTCChannel channel);

    boolean shouldUseRoute(NostrRTCChannel channel);

    int maximumNormalFrameBytes(NostrRTCChannel channel);

    AsyncTask<Boolean> writeRouted(NostrRTCChannel channel, ByteBuffer normalFrame);
}
