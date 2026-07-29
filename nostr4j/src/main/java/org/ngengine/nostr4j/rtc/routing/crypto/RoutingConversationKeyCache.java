/**
 * BSD 3-Clause License
 *
 * Copyright (c) 2025, Riccardo Balbo
 */
package org.ngengine.nostr4j.rtc.routing.crypto;

import java.io.Closeable;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.ngengine.nostr4j.keypair.NostrPrivateKey;
import org.ngengine.nostr4j.keypair.NostrPublicKey;
import org.ngengine.nostr4j.nip44.Nip44;
import org.ngengine.nostr4j.rtc.routing.NodeId;

final class RoutingConversationKeyCache implements Closeable {

    private static final int MAX_ENTRIES = 1024;
    private final LinkedHashMap<Key, byte[]> keys = new LinkedHashMap<Key, byte[]>(16, 0.75f, true);

    synchronized byte[] get(NostrPrivateKey localPrivateKey, NodeId remoteNode, NostrPublicKey remoteRoutingPublicKey) {
        Key key = new Key(remoteNode, remoteRoutingPublicKey);
        byte[] cached = keys.get(key);
        if (cached != null) return cached.clone();
        Iterator<Map.Entry<Key, byte[]>> iterator = keys.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Key, byte[]> entry = iterator.next();
            if (entry.getKey().node.equals(remoteNode) && !entry.getKey().equals(key)) {
                Arrays.fill(entry.getValue(), (byte) 0);
                iterator.remove();
            }
        }
        byte[] derived = Nip44.getConversationKeySync(localPrivateKey, remoteRoutingPublicKey);
        keys.put(key, derived.clone());
        while (keys.size() > MAX_ENTRIES) {
            Map.Entry<Key, byte[]> eldest = keys.entrySet().iterator().next();
            Arrays.fill(eldest.getValue(), (byte) 0);
            keys.remove(eldest.getKey());
        }
        return derived;
    }

    synchronized int size() {
        return keys.size();
    }

    @Override
    public synchronized void close() {
        for (byte[] key : keys.values()) Arrays.fill(key, (byte) 0);
        keys.clear();
    }

    private static final class Key {

        private final NodeId node;
        private final NostrPublicKey publicKey;

        private Key(NodeId node, NostrPublicKey publicKey) {
            this.node = Objects.requireNonNull(node);
            this.publicKey = Objects.requireNonNull(publicKey);
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof Key)) return false;
            Key that = (Key) other;
            return node.equals(that.node) && publicKey.equals(that.publicKey);
        }

        @Override
        public int hashCode() {
            return Objects.hash(node, publicKey);
        }
    }
}
