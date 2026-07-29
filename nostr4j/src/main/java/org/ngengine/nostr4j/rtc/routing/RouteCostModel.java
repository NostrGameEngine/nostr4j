/**
 * BSD 3-Clause License
 *
 * Copyright (c) 2025, Riccardo Balbo
 */
package org.ngengine.nostr4j.rtc.routing;

import org.ngengine.nostr4j.rtc.routing.topology.TopologyTransport;

/**
 * Internal deterministic edge-cost policy. Costs are deliberately based only on
 * locally verifiable transport classes and hop count.
 */
public final class RouteCostModel {

    public static final RouteCostModel DEFAULT = new RouteCostModel(10, 35, 50, 5, 1_000);

    private final int rtcCost;
    private final int turnCost;
    private final int unknownCost;
    private final int hopCost;
    private final int failedRoutePenalty;

    public RouteCostModel(int rtcCost, int turnCost, int unknownCost, int hopCost, int failedRoutePenalty) {
        if (rtcCost < 0 || turnCost < 0 || unknownCost < 0 || hopCost < 0 || failedRoutePenalty < 0) {
            throw new IllegalArgumentException("Route costs must be non-negative");
        }
        this.rtcCost = rtcCost;
        this.turnCost = turnCost;
        this.unknownCost = unknownCost;
        this.hopCost = hopCost;
        this.failedRoutePenalty = failedRoutePenalty;
    }

    public int edgeCost(TopologyTransport transport) {
        int transportCost;
        if (transport == TopologyTransport.RTC) {
            transportCost = rtcCost;
        } else if (transport == TopologyTransport.TURN) {
            transportCost = turnCost;
        } else {
            transportCost = unknownCost;
        }
        return Math.addExact(transportCost, hopCost);
    }

    public int getFailedRoutePenalty() {
        return failedRoutePenalty;
    }
}
