/* BSD 3-Clause License - Copyright (c) 2026, Riccardo Balbo */
package org.ngengine.nostr4j.io;

import static org.junit.Assert.*;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import org.ngengine.nostr4j.RTCSettings;
import org.ngengine.nostr4j.keypair.NostrKeyPair;
import org.ngengine.nostr4j.keypair.NostrPrivateKey;
import org.ngengine.nostr4j.keypair.NostrPublicKey;
import org.ngengine.nostr4j.nip44.Nip44;

public class TestNostrPeerConnection {

    @Test
    public void roomKeyMatchesAtBothEndpointsAndSeparatesConnectionIds() throws Exception {
        try (
            NostrKeyPair a = new NostrKeyPair();
            NostrKeyPair b = new NostrKeyPair();
            NostrKeyPair firstAB = deriveRoomKey("first", a, b.getPublicKey());
            NostrKeyPair firstBA = deriveRoomKey("first", b, a.getPublicKey());
            NostrKeyPair secondAB = deriveRoomKey("second", a, b.getPublicKey())
        ) {
            assertEquals(firstAB.getPublicKey(), firstBA.getPublicKey());
            assertEquals(firstAB.getPrivateKey().asHex(), firstBA.getPrivateKey().asHex());
            assertNotEquals(firstAB.getPublicKey(), secondAB.getPublicKey());
            assertFalse(Arrays.equals(
                firstAB.getPrivateKey()._array(),
                Nip44.getConversationKeySync(a.getPrivateKey(), b.getPublicKey())
            ));
            assertTrue(firstAB.getPublicKey().verify());
        }
    }

    @Test
    public void constructionKeepsExplicitRtcScopeAndCreatesDistinctIdentities() throws Exception {
        RTCSettings settings = RTCSettings.getDefault("test.app", "test.protocol")
            .withStunServers(List.of()).withSignalingRelays(List.of());
        try (
            NostrPeerConnection a = new NostrPeerConnection(settings, "shared-id", 1024, 2);
            NostrPeerConnection b = new NostrPeerConnection(settings, "shared-id", 1024, 2)
        ) {
            assertNotEquals(a.getPeerId(), b.getPeerId());
            Field field = NostrPeerConnection.class.getDeclaredField("rtcSettings");
            field.setAccessible(true);
            assertSame(settings, field.get(a));
            assertSame(settings, field.get(b));
            assertThrows(IllegalArgumentException.class, () -> a.connect(a.getPeerId()));
        }
    }

    @Test
    public void connectionIdMustNotBeNullOrBlank() {
        assertThrows(NullPointerException.class, () -> new NostrPeerConnection((String) null));
        assertThrows(IllegalArgumentException.class, () -> new NostrPeerConnection(" "));
    }

    private static NostrKeyPair deriveRoomKey(String connectionId, NostrKeyPair local, NostrPublicKey remote)
        throws Exception {
        Method method = NostrPeerConnection.class.getDeclaredMethod(
            "generateRoomKey", String.class, NostrPrivateKey.class, NostrPublicKey.class
        );
        method.setAccessible(true);
        return new NostrKeyPair((NostrPrivateKey) method.invoke(null, connectionId, local.getPrivateKey(), remote));
    }
}
