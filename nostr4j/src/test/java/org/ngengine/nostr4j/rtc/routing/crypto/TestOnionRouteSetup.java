/**
 * BSD 3-Clause License
 *
 * Copyright (c) 2025, Riccardo Balbo
 */
package org.ngengine.nostr4j.rtc.routing.crypto;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import org.ngengine.nostr4j.keypair.NostrKeyPair;
import org.ngengine.nostr4j.keypair.NostrPublicKey;
import org.ngengine.nostr4j.rtc.routing.CircuitTable;
import org.ngengine.nostr4j.rtc.routing.NodeId;
import org.ngengine.nostr4j.rtc.routing.RouteTransportProfile;
import org.ngengine.nostr4j.rtc.routing.packet.RouteSetupEnvelope;
import org.ngengine.platform.NGEUtils;

public class TestOnionRouteSetup {

    @Test
    public void testFourNodeSetupRevealsOnlyNextHopAndInstallsForwardingState() {
        NodeId a = node(1);
        NodeId b = node(2);
        NodeId c = node(3);
        NodeId d = node(4);
        Map<NodeId, NostrKeyPair> keys = keys(a, b, c, d);
        Instant now = Instant.now();
        Instant expires = now.plusSeconds(30);
        OnionRouteSetup onion = new OnionRouteSetup();
        OnionRouteSetup.BuiltSetup built = onion.build(
            List.of(a, b, c, d),
            publicKeys(keys),
            RouteTransportProfile.RELIABLE_ORDERED,
            expires,
            now
        );
        CircuitTable atB = new CircuitTable();
        CircuitTable atC = new CircuitTable();

        OnionRouteSetup.PeeledSetup bLayer = onion.peel(built.getEnvelope(), keys.get(b).getPrivateKey(), now);
        assertFalse(bLayer.isDestination());
        assertEquals(c, bLayer.getNextHop());
        assertNull(bLayer.getSource());
        atB.install(a, built.getCircuitId(), c, bLayer.getProfile(), expires, now);

        OnionRouteSetup.PeeledSetup cLayer = onion.peel(bLayer.getForwardedEnvelope(), keys.get(c).getPrivateKey(), now);
        assertFalse(cLayer.isDestination());
        assertEquals(d, cLayer.getNextHop());
        assertNull(cLayer.getSource());
        atC.install(b, built.getCircuitId(), d, cLayer.getProfile(), expires, now);

        OnionRouteSetup.PeeledSetup dLayer = onion.peel(cLayer.getForwardedEnvelope(), keys.get(d).getPrivateKey(), now);
        assertTrue(dLayer.isDestination());
        assertEquals(a, dLayer.getSource());
        assertNull(dLayer.getNextHop());
        assertNull(dLayer.getForwardedEnvelope());
        assertEquals(c, atB.find(a, built.getCircuitId(), now).getNextDirectPeer());
        assertEquals(d, atC.find(b, built.getCircuitId(), now).getNextDirectPeer());
    }

    @Test
    public void testWrongKeyTamperingExpiryAndHopLimitAreRejected() {
        NodeId a = node(10);
        NodeId b = node(11);
        Map<NodeId, NostrKeyPair> keys = keys(a, b);
        Instant now = Instant.now();
        OnionRouteSetup onion = new OnionRouteSetup();
        OnionRouteSetup.BuiltSetup built = onion.build(
            List.of(a, b),
            publicKeys(keys),
            RouteTransportProfile.UNRELIABLE_UNORDERED,
            now.plusSeconds(10),
            now
        );
        assertThrows(SecurityException.class, () -> onion.peel(built.getEnvelope(), new NostrKeyPair().getPrivateKey(), now));

        ByteBuffer encoded = built.getEnvelope().encode();
        byte[] tamperedBytes = new byte[encoded.remaining()];
        encoded.get(tamperedBytes);
        tamperedBytes[tamperedBytes.length - 1] ^= 1;
        RouteSetupEnvelope tampered = RouteSetupEnvelope.decode(ByteBuffer.wrap(tamperedBytes));
        assertThrows(SecurityException.class, () -> onion.peel(tampered, keys.get(b).getPrivateKey(), now));
        assertThrows(
            IllegalArgumentException.class,
            () -> onion.peel(built.getEnvelope(), keys.get(b).getPrivateKey(), now.plusSeconds(11))
        );
    }

    private static Map<NodeId, NostrKeyPair> keys(NodeId... nodes) {
        Map<NodeId, NostrKeyPair> result = new HashMap<NodeId, NostrKeyPair>();
        for (NodeId node : nodes) result.put(node, new NostrKeyPair());
        return result;
    }

    private static Map<NodeId, NostrPublicKey> publicKeys(Map<NodeId, NostrKeyPair> keys) {
        Map<NodeId, NostrPublicKey> result = new HashMap<NodeId, NostrPublicKey>();
        for (Map.Entry<NodeId, NostrKeyPair> entry : keys.entrySet()) {
            result.put(entry.getKey(), entry.getValue().getPublicKey());
        }
        return result;
    }

    private static NodeId node(int value) {
        byte[] bytes = new byte[NodeId.SIZE];
        bytes[NodeId.SIZE - 1] = (byte) value;
        return NodeId.fromHex(NGEUtils.bytesToHex(bytes));
    }
}
