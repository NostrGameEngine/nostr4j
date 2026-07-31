/**
 * BSD 3-Clause License
 *
 * Copyright (c) 2025, Riccardo Balbo
 */
package org.ngengine.nostr4j.rtc.routing;

import org.ngengine.nostr4j.rtc.signal.NostrRTCProtocolVersion;

public final class RoutingProtocol {

    public static final String VERSION = NostrRTCProtocolVersion.serialize(NostrRTCProtocolVersion.CURRENT_NIP_DC_VERSION);
    public static final int TOPOLOGY_EVENT_KIND = 30350;
    public static final String TOPOLOGY_EVENT_TYPE = "dc-topology";

    private RoutingProtocol() {}
}
