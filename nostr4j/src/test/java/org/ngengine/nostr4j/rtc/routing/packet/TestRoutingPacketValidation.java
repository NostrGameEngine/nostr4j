/**
 * BSD 3-Clause License
 *
 * Copyright (c) 2025, Riccardo Balbo
 */
package org.ngengine.nostr4j.rtc.routing.packet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import java.nio.ByteBuffer;
import java.time.Instant;
import org.junit.Test;
import org.ngengine.nostr4j.keypair.NostrKeyPair;
import org.ngengine.nostr4j.rtc.routing.CircuitId;
import org.ngengine.nostr4j.rtc.routing.InternalRoutingChannels;
import org.ngengine.nostr4j.rtc.routing.NodeId;
import org.ngengine.nostr4j.rtc.routing.RouteTransportProfile;
import org.ngengine.nostr4j.rtc.routing.RoutingLimits;
import org.ngengine.nostr4j.rtc.routing.broadcast.BroadcastFrame;
import org.ngengine.platform.NGEUtils;

public class TestRoutingPacketValidation {

    private static final Instant NOW = Instant.ofEpochSecond(2_000_000_000L);

    @Test
    public void testRoutedFrameRejectsEveryTruncationTrailingBytesVersionFlagsAndExpiry() {
        RoutedDataFrame valid = new RoutedDataFrame(
            RoutedFrameType.DATA,
            1,
            CircuitId.random(),
            CircuitId.random(),
            NOW.plusSeconds(30),
            ByteBuffer.wrap(new byte[] { 1, 2, 3 })
        );
        byte[] wire = bytes(valid.encode());
        assertEquals(3, RoutedDataFrame.decode(ByteBuffer.wrap(wire), NOW).getCiphertext().remaining());
        for (int length = 0; length < wire.length; length++) {
            final int truncatedLength = length;
            assertThrows(
                IllegalArgumentException.class,
                () -> RoutedDataFrame.decode(ByteBuffer.wrap(wire, 0, truncatedLength), NOW)
            );
        }
        byte[] trailing = new byte[wire.length + 1];
        System.arraycopy(wire, 0, trailing, 0, wire.length);
        assertThrows(IllegalArgumentException.class, () -> RoutedDataFrame.decode(ByteBuffer.wrap(trailing), NOW));

        byte[] badVersion = wire.clone();
        badVersion[4] = 2;
        assertThrows(IllegalArgumentException.class, () -> RoutedDataFrame.decode(ByteBuffer.wrap(badVersion), NOW));
        byte[] badFlags = wire.clone();
        badFlags[6] = (byte) 0x80;
        assertThrows(IllegalArgumentException.class, () -> RoutedDataFrame.decode(ByteBuffer.wrap(badFlags), NOW));

        assertThrows(
            IllegalArgumentException.class,
            () ->
                RoutedDataFrame.decode(
                    new RoutedDataFrame(
                        RoutedFrameType.DATA,
                        0,
                        CircuitId.random(),
                        CircuitId.random(),
                        NOW,
                        ByteBuffer.wrap(new byte[] { 1 })
                    )
                        .encode(),
                    NOW
                )
        );
        assertThrows(
            IllegalArgumentException.class,
            () ->
                RoutedDataFrame.decode(
                    new RoutedDataFrame(
                        RoutedFrameType.DATA,
                        0,
                        CircuitId.random(),
                        CircuitId.random(),
                        NOW.plusSeconds(RoutingLimits.MAX_CIRCUIT_LIFETIME_SECONDS + 1L),
                        ByteBuffer.wrap(new byte[] { 1 })
                    )
                        .encode(),
                    NOW
                )
        );
        assertThrows(
            IllegalArgumentException.class,
            () ->
                new RoutedDataFrame(
                    RoutedFrameType.DATA,
                    0,
                    CircuitId.random(),
                    CircuitId.random(),
                    NOW.plusSeconds(1),
                    ByteBuffer.allocate(RoutingLimits.MAX_ROUTED_FRAME_BYTES)
                )
        );
    }

    @Test
    public void testControlAndSetupEnvelopesRejectExpiredOrExcessiveLifetimes() {
        NostrKeyPair key = new NostrKeyPair();
        RouteSetupEnvelope setup = new RouteSetupEnvelope(
            CircuitId.random(),
            CircuitId.random(),
            NOW.plusSeconds(30),
            1,
            key.getPublicKey(),
            ByteBuffer.wrap(new byte[] { 1 })
        );
        assertEquals(1, RouteSetupEnvelope.decode(setup.encode(), NOW).getRemainingHops());
        assertThrows(
            IllegalArgumentException.class,
            () ->
                RouteSetupEnvelope.decode(
                    new RouteSetupEnvelope(
                        CircuitId.random(),
                        CircuitId.random(),
                        NOW.plusSeconds(RoutingLimits.MAX_CIRCUIT_LIFETIME_SECONDS + 1L),
                        1,
                        key.getPublicKey(),
                        ByteBuffer.wrap(new byte[] { 1 })
                    )
                        .encode(),
                    NOW
                )
        );

        StatelessControlEnvelope control = new StatelessControlEnvelope(
            CircuitId.random(),
            NOW.plusSeconds(30),
            1,
            key.getPublicKey(),
            ByteBuffer.wrap(new byte[] { 1 })
        );
        assertEquals(1, StatelessControlEnvelope.decode(control.encode(), NOW).getRemainingHops());
        assertThrows(
            IllegalArgumentException.class,
            () ->
                StatelessControlEnvelope.decode(
                    new StatelessControlEnvelope(
                        CircuitId.random(),
                        NOW.plusSeconds(RoutingLimits.MAX_CIRCUIT_LIFETIME_SECONDS + 1L),
                        1,
                        key.getPublicKey(),
                        ByteBuffer.wrap(new byte[] { 1 })
                    )
                        .encode(),
                    NOW
                )
        );
        key.destroy();
    }

    @Test
    public void testBroadcastRejectsReservedChannelAndExcessiveExpiry() {
        NodeId origin = node(1);
        String snapshot = NGEUtils.bytesToHex(new byte[32]);
        assertThrows(
            IllegalArgumentException.class,
            () ->
                new BroadcastFrame(
                    origin,
                    CircuitId.random(),
                    snapshot,
                    InternalRoutingChannels.CONTROL,
                    RouteTransportProfile.RELIABLE_ORDERED,
                    1,
                    NOW.plusSeconds(30),
                    ByteBuffer.wrap(new byte[] { 1 })
                )
        );
        BroadcastFrame future = new BroadcastFrame(
            origin,
            CircuitId.random(),
            snapshot,
            "game",
            RouteTransportProfile.RELIABLE_ORDERED,
            1,
            NOW.plusSeconds(RoutingLimits.MAX_CIRCUIT_LIFETIME_SECONDS + 1L),
            ByteBuffer.wrap(new byte[] { 1 })
        );
        assertThrows(IllegalArgumentException.class, () -> BroadcastFrame.decode(future.encode(), NOW));

        BroadcastFrame valid = new BroadcastFrame(
            origin,
            CircuitId.random(),
            snapshot,
            "x",
            RouteTransportProfile.UNRELIABLE_UNORDERED,
            1,
            NOW.plusSeconds(30),
            ByteBuffer.wrap(new byte[] { 1 })
        );
        byte[] malformedUtf8 = bytes(valid.encode());
        malformedUtf8[114] = (byte) 0x80;
        assertThrows(IllegalArgumentException.class, () -> BroadcastFrame.decode(ByteBuffer.wrap(malformedUtf8), NOW));
    }

    private static byte[] bytes(ByteBuffer input) {
        ByteBuffer data = input.asReadOnlyBuffer();
        byte[] result = new byte[data.remaining()];
        data.get(result);
        return result;
    }

    private static NodeId node(int value) {
        byte[] bytes = new byte[NodeId.SIZE];
        bytes[bytes.length - 1] = (byte) value;
        return NodeId.fromHex(NGEUtils.bytesToHex(bytes));
    }
}
