/** BSD 3-Clause License. Copyright (c) 2025, Riccardo Balbo. */
package org.ngengine.nostr4j.rtc;

import static org.junit.Assert.*;

import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.util.Map;
import org.junit.Test;
import org.ngengine.nostr4j.RTCSettings;
import org.ngengine.nostr4j.keypair.NostrKeyPair;
import org.ngengine.nostr4j.rtc.signal.NostrRTCLocalPeer;
import org.ngengine.nostr4j.rtc.signal.NostrRTCPeer;
import org.ngengine.nostr4j.signer.NostrKeyPairSigner;
import org.ngengine.platform.AsyncExecutor;
import org.ngengine.platform.NGEPlatform;

public class TestFragmentReassemblyBounds {

    @Test
    public void hostileFragmentsStayBoundedAndCloseReleasesState() throws Exception {
        NostrKeyPair room = new NostrKeyPair();
        RTCSettings settings = RTCSettings.getDefault("bounds-app", "bounds-proto");
        NostrRTCLocalPeer local = new NostrRTCLocalPeer(settings, NostrKeyPairSigner.generate(), "local", room, null);
        NostrRTCPeer remote = new NostrRTCPeer(
            new NostrKeyPair().getPublicKey(),
            "bounds-app",
            "bounds-proto",
            "remote",
            room.getPublicKey(),
            null
        );
        AsyncExecutor executor = NGEPlatform.get().newAsyncExecutor("fragment-bounds");
        NostrRTCSocket socket = new NostrRTCSocket(executor, remote, room, local, settings, null);
        NostrRTCChannel channel = new NostrRTCChannel("bounds", socket, true, true, 0, null);
        try {
            assertFalse(channel.onRoutedSocketMessage(frame(9999, 0, Short.MAX_VALUE, 1)));
            for (long id = 1; id <= 1000; id++) channel.onRTCSocketMessage(frame(id, 0, Short.MAX_VALUE, 1));
            assertEquals(0, pending(channel).size());
            for (long id = 1; id <= 1000; id++) channel.onRTCSocketMessage(frame(id, 0, 2, 1));
            assertFalse(channel.onRoutedSocketMessage(frame(9999, 0, 2, 1)));
            assertEquals(NostrRTCChannel.MAX_PENDING_FRAGMENT_PACKETS, pending(channel).size());
            assertEquals(NostrRTCChannel.MAX_PENDING_FRAGMENT_PACKETS, field(channel, "pendingFragmentBytes"));
            assertNotNull(field(channel, "fragmentCleanupTask"));
            channel.onRTCSocketMessage(frame(1, 1, 3, 1));
            assertEquals(NostrRTCChannel.MAX_PENDING_FRAGMENT_PACKETS, field(channel, "pendingFragmentBytes"));
            channel.onRTCSocketMessage(frame(1, 1, 2, 1));
            assertEquals(NostrRTCChannel.MAX_PENDING_FRAGMENT_PACKETS - 1, pending(channel).size());
            NostrRTCChannel byteBounded = new NostrRTCChannel("byte-bounds", socket, true, true, 0, null);
            try {
                for (int i = 0; i < 300; i++) byteBounded.onRTCSocketMessage(
                    frame(10001, i, 1024, NostrRTCChannel.MAX_APPLICATION_FRAGMENT_SIZE)
                );
                int bytes = (Integer) field(byteBounded, "pendingFragmentBytes");
                assertTrue(bytes <= NostrRTCChannel.MAX_REASSEMBLY_BYTES);
                assertTrue(bytes > NostrRTCChannel.MAX_REASSEMBLY_BYTES - NostrRTCChannel.MAX_APPLICATION_FRAGMENT_SIZE);
            } finally {
                byteBounded.close();
            }
            channel.close();
            assertEquals(0, pending(channel).size());
            assertEquals(0, field(channel, "pendingFragmentBytes"));
            channel.onRTCSocketMessage(frame(9999, 0, 2, 1));
            assertEquals(0, pending(channel).size());
        } finally {
            channel.close();
            socket.close();
            executor.close();
        }
    }

    private static ByteBuffer frame(long id, int part, int count, int bytes) {
        ByteBuffer b = ByteBuffer.allocate(12 + bytes);
        b.putLong(id).putShort((short) part).putShort((short) count);
        b.position(b.capacity());
        b.flip();
        return b;
    }

    private static Object field(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static Map<?, ?> pending(NostrRTCChannel channel) throws Exception {
        return (Map<?, ?>) field(channel, "pendingFragments");
    }
}
