/**
 * BSD 3-Clause License
 *
 * Copyright (c) 2025, Riccardo Balbo
 */
package org.ngengine.nostr4j.rtc.routing.crypto;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import java.nio.ByteBuffer;
import java.time.Instant;
import org.junit.Test;
import org.ngengine.nostr4j.keypair.NostrKeyPair;
import org.ngengine.nostr4j.rtc.routing.CircuitId;
import org.ngengine.nostr4j.rtc.routing.NodeId;
import org.ngengine.nostr4j.rtc.routing.RouteTransportProfile;
import org.ngengine.nostr4j.rtc.routing.packet.RoutedDataFrame;
import org.ngengine.nostr4j.rtc.routing.packet.RoutedFrameType;
import org.ngengine.platform.NGEUtils;

public class TestRoutePayloadCrypto {

    @Test
    public void testCompatibleKeysTamperingWrongDestinationAndRouteIndependentReuse() {
        NostrKeyPair sourceKeys = new NostrKeyPair();
        NostrKeyPair destinationKeys = new NostrKeyPair();
        NodeId source = node(1);
        NodeId destination = node(2);
        RoutePayloadCrypto sourceCrypto = new RoutePayloadCrypto(sourceKeys);
        RoutePayloadCrypto destinationCrypto = new RoutePayloadCrypto(destinationKeys);
        ByteBuffer normal = normalFrame(42L, 0, 1, new byte[] { 7, 8, 9 });
        RoutePayloadCrypto.EncryptedPayload encrypted = sourceCrypto.encrypt(
            source,
            destination,
            destinationKeys.getPublicKey(),
            "game",
            RouteTransportProfile.RELIABLE_ORDERED,
            normal,
            token(3)
        );
        assertEquals(1L, sourceCrypto.getEncryptionCount());

        RoutedDataFrame firstRoute = new RoutedDataFrame(
            RoutedFrameType.DATA,
            1,
            CircuitId.random(),
            CircuitId.random(),
            Instant.now().plusSeconds(10),
            encrypted.getCiphertext()
        );
        RoutedDataFrame retryRoute = new RoutedDataFrame(
            RoutedFrameType.DATA,
            1,
            CircuitId.random(),
            CircuitId.random(),
            Instant.now().plusSeconds(10),
            encrypted.getCiphertext()
        );
        assertEquals(1L, sourceCrypto.getEncryptionCount());
        assertEquals(
            NGEUtils.bytesToHex(bytes(firstRoute.getCiphertext())),
            NGEUtils.bytesToHex(bytes(retryRoute.getCiphertext()))
        );

        RoutePayloadCrypto.DecryptedPayload decrypted = destinationCrypto.decrypt(
            source,
            destination,
            sourceKeys.getPublicKey(),
            encrypted.getCiphertext()
        );
        assertEquals("game", decrypted.getLogicalChannel());
        assertEquals(42L, decrypted.getPacketId());
        assertEquals(NGEUtils.bytesToHex(bytes(normal)), NGEUtils.bytesToHex(bytes(decrypted.getNormalFrame())));

        assertThrows(
            SecurityException.class,
            () ->
                new RoutePayloadCrypto(new NostrKeyPair())
                    .decrypt(source, destination, sourceKeys.getPublicKey(), encrypted.getCiphertext())
        );
        byte[] tampered = bytes(encrypted.getCiphertext());
        tampered[tampered.length - 1] ^= 1;
        assertThrows(
            SecurityException.class,
            () -> destinationCrypto.decrypt(source, destination, sourceKeys.getPublicKey(), ByteBuffer.wrap(tampered))
        );
    }

    @Test
    public void testRoutingKeyRotationInvalidatesOldConversationAndUsesNewKey() {
        NostrKeyPair sourceKeys = new NostrKeyPair();
        NostrKeyPair oldDestination = new NostrKeyPair();
        NostrKeyPair newDestination = new NostrKeyPair();
        NodeId source = node(10);
        NodeId destination = node(11);
        RoutePayloadCrypto sourceCrypto = new RoutePayloadCrypto(sourceKeys);
        RoutePayloadCrypto.EncryptedPayload oldPayload = sourceCrypto.encrypt(
            source,
            destination,
            oldDestination.getPublicKey(),
            "rotation",
            RouteTransportProfile.UNRELIABLE_UNORDERED,
            normalFrame(1L, 0, 1, new byte[] { 1 }),
            null
        );
        assertThrows(
            SecurityException.class,
            () ->
                new RoutePayloadCrypto(newDestination)
                    .decrypt(source, destination, sourceKeys.getPublicKey(), oldPayload.getCiphertext())
        );
        RoutePayloadCrypto.EncryptedPayload newPayload = sourceCrypto.encrypt(
            source,
            destination,
            newDestination.getPublicKey(),
            "rotation",
            RouteTransportProfile.UNRELIABLE_UNORDERED,
            normalFrame(2L, 0, 1, new byte[] { 2 }),
            null
        );
        RoutePayloadCrypto.DecryptedPayload decrypted = new RoutePayloadCrypto(newDestination)
            .decrypt(source, destination, sourceKeys.getPublicKey(), newPayload.getCiphertext());
        assertEquals(2L, decrypted.getPacketId());
        assertEquals(1, sourceCrypto.getCachedConversationCount());
    }

    @Test
    public void testDecodedForwardingViewRetainsIncomingBackingStorage() {
        RoutedDataFrame frame = new RoutedDataFrame(
            RoutedFrameType.DATA,
            0,
            CircuitId.random(),
            CircuitId.random(),
            Instant.now().plusSeconds(10),
            ByteBuffer.wrap(new byte[] { 1, 2, 3 })
        );
        ByteBuffer encoded = frame.encode();
        byte[] wire = bytes(encoded);
        RoutedDataFrame decoded = RoutedDataFrame.decode(ByteBuffer.wrap(wire), Instant.now());
        ByteBuffer forwarded = decoded.forwardingView();
        int last = wire.length - 1;
        wire[last] = 99;
        assertEquals(99, forwarded.get(last) & 0xff);
    }

    private static ByteBuffer normalFrame(long packetId, int fragmentId, int fragmentCount, byte[] payload) {
        ByteBuffer frame = ByteBuffer.allocate(12 + payload.length);
        frame.putLong(packetId);
        frame.putShort((short) fragmentId);
        frame.putShort((short) fragmentCount);
        frame.put(payload);
        frame.flip();
        return frame.asReadOnlyBuffer();
    }

    private static byte[] token(int seed) {
        byte[] token = new byte[RoutePayloadCrypto.ACK_TOKEN_BYTES];
        token[token.length - 1] = (byte) seed;
        return token;
    }

    private static byte[] bytes(ByteBuffer input) {
        ByteBuffer data = input.asReadOnlyBuffer();
        byte[] bytes = new byte[data.remaining()];
        data.get(bytes);
        return bytes;
    }

    private static NodeId node(int value) {
        byte[] bytes = new byte[NodeId.SIZE];
        bytes[NodeId.SIZE - 1] = (byte) value;
        return NodeId.fromHex(NGEUtils.bytesToHex(bytes));
    }
}
