/**
 * BSD 3-Clause License
 *
 * Copyright (c) 2025, Riccardo Balbo
 */
package org.ngengine.nostr4j.rtc.routing.crypto;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import org.ngengine.nostr4j.keypair.NostrKeyPair;
import org.ngengine.nostr4j.keypair.NostrPublicKey;
import org.ngengine.nostr4j.rtc.routing.CircuitId;
import org.ngengine.nostr4j.rtc.routing.NodeId;
import org.ngengine.nostr4j.rtc.routing.packet.EndToEndControlType;
import org.ngengine.nostr4j.rtc.routing.packet.StatelessControlEnvelope;
import org.ngengine.platform.NGEUtils;

public class TestOnionStatelessControl {

    @Test
    public void testControlResponseUsesNoReverseCircuitState() {
        NodeId d = node(4);
        NodeId e = node(5);
        NodeId f = node(6);
        NodeId a = node(1);
        Map<NodeId, NostrKeyPair> keys = keys(d, e, f, a);
        EndToEndControlCodec codec = new EndToEndControlCodec();
        CircuitId setup = CircuitId.random();
        CircuitId circuit = CircuitId.random();
        byte[] token = new byte[16];
        EndToEndControlCodec.Message confirmation = new EndToEndControlCodec.Message(
            EndToEndControlType.SETUP_CONFIRMED,
            d,
            a,
            setup,
            circuit,
            0L,
            0,
            "",
            token
        );
        ByteBuffer protectedConfirmation = codec.encrypt(confirmation, keys.get(d).getPrivateKey(), keys.get(a).getPublicKey());
        Instant now = Instant.now();
        OnionStatelessControl onion = new OnionStatelessControl();
        StatelessControlEnvelope envelope = onion.build(
            List.of(d, e, f, a),
            publicKeys(keys),
            protectedConfirmation,
            now.plusSeconds(10),
            now
        );

        OnionStatelessControl.PeeledControl atE = onion.peel(envelope, keys.get(e).getPrivateKey(), now);
        assertFalse(atE.isDestination());
        assertEquals(f, atE.getNextHop());
        assertNull(atE.getOrigin());

        OnionStatelessControl.PeeledControl atF = onion.peel(atE.getForwardedEnvelope(), keys.get(f).getPrivateKey(), now);
        assertEquals(a, atF.getNextHop());

        OnionStatelessControl.PeeledControl atA = onion.peel(atF.getForwardedEnvelope(), keys.get(a).getPrivateKey(), now);
        assertTrue(atA.isDestination());
        assertEquals(d, atA.getOrigin());
        EndToEndControlCodec.Message decoded = codec.decrypt(
            atA.getFinalPayload(),
            d,
            a,
            keys.get(a).getPrivateKey(),
            keys.get(d).getPublicKey()
        );
        assertEquals(EndToEndControlType.SETUP_CONFIRMED, decoded.getType());
        assertEquals(setup, decoded.getCorrelationId());
        assertEquals(circuit, decoded.getCircuitId());
        assertArrayEquals(token, decoded.getToken());
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
