/**
 * BSD 3-Clause License
 *
 * Copyright (c) 2025, Riccardo Balbo
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * 3. Neither the name of the copyright holder nor the names of its
 *    contributors may be used to endorse or promote products derived from
 *    this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
 * FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
 * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
 * OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package org.ngengine.nostr4j.rtc.turn;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.logging.Logger;
import java.util.zip.Deflater;
import java.util.zip.Inflater;
import org.ngengine.nostr4j.NostrFilter;
import org.ngengine.nostr4j.NostrPool;
import org.ngengine.nostr4j.NostrRelay;
import org.ngengine.nostr4j.NostrSubscription;
import org.ngengine.nostr4j.event.SignedNostrEvent;
import org.ngengine.nostr4j.event.UnsignedNostrEvent;
import org.ngengine.nostr4j.event.tracker.PassthroughEventTracker;
import org.ngengine.nostr4j.listeners.sub.NostrSubEventListener;
import org.ngengine.nostr4j.rtc.signal.NostrRTCLocalPeer;
import org.ngengine.nostr4j.rtc.signal.NostrRTCPeer;
import org.ngengine.platform.AsyncExecutor;
import org.ngengine.platform.AsyncTask;
import org.ngengine.platform.NGEPlatform;
import org.ngengine.platform.NGEUtils;

/**
 * TURN implemented on top of nostr relays.
 * When a p2p connection is not possible, the packets can be relayed through a nostr relay
 * using this class.
 * The packets are automatically compressed, encrypted and split into chunks to ensure a reliable
 * and private connection through public relays.
 *
 * Note: this might cause unexpected heavy load on the relays, so use it only with relays that
 * explicitly support this kind of workload.
 */
public class NostrTURN {

    private static final Logger logger = Logger.getLogger(NostrTURN.class.getName());

    public static interface Listener {
        void onTurnPacket(NostrRTCPeer peer, ByteBuffer data);
    }

    /**
     * A chunk of encrypted and compressed data to be sent.
     */
    private static class Chunk {

        String data;
        boolean ack;
        boolean sent;
        Instant lastAttempt;

        Chunk(String data) {
            this.data = data;
        }
    }

    /**
     * A packet is made of multiple chunks.
     */
    private static class Packet {

        final long id;
        final List<Chunk> chunks;
        int sent; // chunks sent
        int ack;
        final Instant timestamp;
        final Runnable callback;
        final Consumer<Exception> callbackError;

        Packet(long id, List<Chunk> chunks, Runnable callback, Consumer<Exception> callbackError) {
            this.id = id;
            this.chunks = chunks;
            this.sent = 0;
            this.ack = 0;
            this.timestamp = Instant.now();
            this.callback = callback;
            this.callbackError = callbackError;
        }
    }

    private final NostrTURNSettings config;
    private final String connectionId;
    private final NostrRTCLocalPeer localPeer;
    private final NostrRTCPeer remotePeer;
    private final NostrSubscription inSub;
    private final NostrPool inPool;
    private final NostrPool outPool;
    private final NostrSubscription outSub;
    private final Map<Long, Packet> outQueue = new HashMap<>();
    private final AtomicLong packetCounter = new AtomicLong(0);
    private final AsyncExecutor executor;
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();

    private volatile Packet inPacket;
    private volatile Runnable lockNotify;
    private volatile boolean stopped = false;

    private class SubscriptionListenerWrapper implements NostrSubEventListener {

        private final boolean remote;

        public SubscriptionListenerWrapper(boolean remote) {
            this.remote = remote;
        }

        @Override
        public void onSubEvent(SignedNostrEvent event, boolean stored) {
            onTurnEvent(event, remote);
        }
    }

    public NostrTURN(String connectionId, NostrRTCLocalPeer localPeer, NostrRTCPeer remotePeer, NostrTURNSettings config) {
        NGEPlatform platform = NGEUtils.getPlatform();
        this.connectionId = Objects.requireNonNull(connectionId, "connectionId cannot be null");
        this.localPeer = Objects.requireNonNull(localPeer, "localPeer cannot be null");
        this.remotePeer = Objects.requireNonNull(remotePeer, "remotePeer cannot be null");
        this.config = Objects.requireNonNull(config, "config cannot be null");
        this.executor = platform.newPoolExecutor();

        // setup local peer turn server
        logger.fine("Connecting to local TURN server: " + localPeer.getTurnServer());
        this.inPool = new NostrPool(PassthroughEventTracker.class);
        this.inPool.connectRelay(new NostrRelay(Objects.requireNonNull(localPeer.getTurnServer()), executor));
        this.inSub = // receive packets from remote peer
            this.inPool.subscribe(
                    new NostrFilter()
                        .withAuthor(this.remotePeer.getPubkey())
                        .withKind(this.config.getKind())
                        .withTag("d", "turn-" + this.connectionId) // tag to identify the connection
                );

        this.inSub.addEventListener(new SubscriptionListenerWrapper(false));

        // setup remote peer turn server
        logger.fine("Connecting to remote TURN server: " + remotePeer.getTurnServer());
        this.outPool = new NostrPool(PassthroughEventTracker.class);
        this.outPool.connectRelay(new NostrRelay(Objects.requireNonNull(remotePeer.getTurnServer()), executor));
        this.outSub =
            this.outPool.subscribe(
                    new NostrFilter()
                        .withAuthor(this.remotePeer.getPubkey())
                        .withKind(this.config.getKind())
                        .withTag("d", "turn-" + this.connectionId)
                );
        this.outSub.addEventListener(new SubscriptionListenerWrapper(false));
    }

    public void addListener(Listener listener) {
        this.listeners.add(listener);
    }

    public void removeListener(Listener listener) {
        this.listeners.remove(listener);
    }

    public void start() {
        this.outSub.open();
        this.inSub.open();
        this.loop();
    }

    public void close() {
        this.stopped = true;
        this.inSub.close();
        this.outSub.close();
        this.inPool.close().forEach(r -> r.disconnect("closed"));
        this.outPool.close().forEach(r -> r.disconnect("closed"));
        this.executor.close();
    }

    // handle incoming TURN events
    private void onTurnEvent(SignedNostrEvent event, boolean remote) {
        String content = event.getContent();
        this.localPeer.getSigner() // decrypt for peer
            .decrypt(content, remotePeer.getPubkey())
            .then(decrypted -> {
                String prefix = decrypted.substring(0, 3);
                if (prefix.equals("ack") && remote) {
                    this.onReceivedAck(decrypted.substring(3));
                } else if (prefix.equals("pkt") && !remote) {
                    this.onReceivedPacketChunk(decrypted.substring(3));
                }
                return null;
            });
    }

    // handle incoming ACK events by marking the chunk as acked
    // and moving the stream forward
    private void onReceivedAck(String data) {
        StringTokenizer tokenizer = new StringTokenizer(data, ",");
        long packetId = NGEUtils.safeLong(tokenizer.nextToken());
        Packet packet = outQueue.get(packetId);
        if (packet == null) return;
        int chunkId = NGEUtils.safeInt(tokenizer.nextToken());
        Chunk chunk = packet.chunks.get(chunkId);
        if (chunk == null || chunk.ack) return;
        chunk.ack = true;
        packet.ack++;

        // move stream forward
        this.consume();
    }

    // handle incoming packet chunks by appending the chunk to the aggregated packet
    private void onReceivedPacketChunk(String data) {
        // string tokenizer to parse the data as we move forward
        StringTokenizer tokenizer = new StringTokenizer(data, ",");

        long packetId = NGEUtils.safeLong(tokenizer.nextToken());
        if (this.inPacket != null && this.inPacket.id != packetId) {
            logger.warning("Received packet with id " + packetId + " but current packet is " + this.inPacket.id);
            return;
        }

        // create new aggregated packet if not already created
        if (this.inPacket == null) {
            this.inPacket = new Packet(packetId, new ArrayList<>(), null, null);
        }

        int chunkId = NGEUtils.safeInt(tokenizer.nextToken());
        int nChunks = NGEUtils.safeInt(tokenizer.nextToken());
        while (this.inPacket.chunks.size() < nChunks) {
            this.inPacket.chunks.add(new Chunk(null));
        }

        // append chunk
        Chunk chunk = this.inPacket.chunks.get(chunkId);
        if (chunk.ack || chunk.sent) return;

        chunk.data = tokenizer.nextToken();
        chunk.sent = true;
        chunk.ack = true;
        chunk.lastAttempt = Instant.now();

        // record ack
        this.inPacket.ack++;
        this.inPacket.sent = nChunks;

        // move stream forward
        this.consume();

        // send ack
        String ack = "ack" + packetId + "," + chunkId;
        this.localPeer.getSigner()
            .encrypt(ack, remotePeer.getPubkey())
            .compose(encrypted -> {
                UnsignedNostrEvent event = new UnsignedNostrEvent();
                event.withKind(this.config.getKind());
                event.createdAt(Instant.now());
                event.withContent(encrypted);
                event.withTag("d", "turn-" + this.connectionId);
                event.withExpiration(Instant.now().plus(this.config.getPacketTimeout()));

                return this.localPeer.getSigner()
                    .sign(event)
                    .compose(signed -> {
                        return this.inPool.publish(signed);
                    });
            });
    }

    // consume the aggregated packet when it is ready
    private void consume() {
        // if we have a full packet, emit data and delete it
        if (this.inPacket != null) {
            if (this.inPacket.sent == this.inPacket.ack) {
                try {
                    // merge chunks
                    StringBuilder data = new StringBuilder();
                    for (Chunk chunk : this.inPacket.chunks) {
                        if (chunk.data != null) {
                            data.append(chunk.data);
                        }
                    }

                    // decode from b64
                    byte[] decoded = Base64.getDecoder().decode(data.toString());
                    // decompress
                    Inflater inflater = new Inflater();
                    inflater.setInput(decoded);

                    ByteArrayOutputStream bos = new ByteArrayOutputStream(decoded.length);
                    int decompressedSize = 0;
                    byte[] chunk = new byte[1024];
                    while (!inflater.finished()) {
                        int s = inflater.inflate(chunk);
                        if (s == 0) {
                            break;
                        }
                        bos.write(chunk, 0, s);
                        decompressedSize += s;
                    }
                    inflater.end();

                    ByteBuffer byteBuffer = ByteBuffer.wrap(bos.toByteArray(), 0, decompressedSize);
                    for (Listener listener : listeners) {
                        try {
                            listener.onTurnPacket(remotePeer, byteBuffer);
                        } catch (Exception e) {
                            logger.warning("Error running listener: " + e.getMessage());
                        }
                    }
                } catch (Exception e) {
                    logger.warning("Error decompressing data: " + e.getMessage());
                }
                this.inPacket = null;
            }
        }

        // if output packet is fully acked, move forward
        Iterator<Packet> it = outQueue.values().iterator();
        while (it.hasNext()) {
            Packet packet = it.next();
            if (packet.sent != 0 && packet.ack == packet.sent) {
                logger.fine("Packet " + packet.id + " fully acked " + packet.ack + "/" + packet.sent);
                it.remove();
                if (packet.callback != null) {
                    try {
                        packet.callback.run();
                    } catch (Exception e) {
                        logger.warning("Error running callback: " + e.getMessage());
                    }
                }
            } else if (Instant.now().isAfter(packet.timestamp.plus(this.config.getPacketTimeout()))) {
                logger.warning("Packet " + packet.id + " timeout " + packet.ack + "/" + packet.sent);
                if (packet.callbackError != null) {
                    try {
                        packet.callbackError.accept(new Exception("Packet timeout"));
                    } catch (Exception e) {
                        logger.warning("Error running callback: " + e.getMessage());
                    }
                }
                it.remove();
            }
        }
    }

    // internal loop to send packets
    private void loop() {
        this.executor.runLater(
                () -> {
                    try {
                        Collection<Packet> packets = outQueue.values();
                        if (packets.isEmpty()) {
                            NGEPlatform platform = NGEUtils.getPlatform();
                            platform
                                .wrapPromise((res, rej) -> {
                                    assert lockNotify == null;
                                    lockNotify =
                                        () -> {
                                            res.accept(null);
                                        };
                                })
                                .await();
                        }
                        if (this.stopped) {
                            return null;
                        }
                        Map.Entry<Long, Packet> entry = outQueue.size() > 0 ? outQueue.entrySet().iterator().next() : null;
                        if (entry != null) {
                            Packet nextPacket = entry.getValue();
                            for (int i = 0; i < nextPacket.chunks.size(); i++) {
                                Chunk chunk = nextPacket.chunks.get(i);
                                Instant lastAttempt = chunk.lastAttempt;

                                // Skip chunk if not acked but still likely to be in transit
                                if (
                                    lastAttempt != null && Instant.now().isAfter(lastAttempt.plus(this.config.getMaxLatency()))
                                ) {
                                    continue;
                                }

                                // Skip chunk if acked
                                if (chunk.ack) {
                                    continue;
                                }

                                // Update last attempt timestamp
                                chunk.lastAttempt = Instant.now();

                                // Prepare data for sending
                                final int chunkIndex = i;
                                String encrypted =
                                    this.localPeer.getSigner()
                                        .encrypt(
                                            "pkt" +
                                            nextPacket.id +
                                            "," +
                                            chunkIndex +
                                            "," +
                                            nextPacket.chunks.size() +
                                            "," +
                                            chunk.data,
                                            remotePeer.getPubkey()
                                        )
                                        .await();
                                // First attempt, mark it as sent
                                if (!chunk.sent) {
                                    chunk.sent = true;
                                    nextPacket.sent++;
                                }

                                // Create and send event
                                UnsignedNostrEvent event = new UnsignedNostrEvent();
                                event.withKind(this.config.getKind());
                                event.createdAt(Instant.now());
                                event.withContent(encrypted);
                                event.withTag("d", "turn-" + this.connectionId);
                                // event.withExpiration(Instant.now().plus(this.config.getPacketTimeout()));

                                SignedNostrEvent signed = this.localPeer.getSigner().sign(event).await();

                                this.outPool.publish(signed).await();
                            }
                        }

                        if (this.stopped) {
                            return null;
                        }

                        this.loop();
                        return null;
                    } catch (Exception e) {
                        logger.log(java.util.logging.Level.WARNING, "Error in TURN loop: " + e.getMessage(), e);
                        return null;
                    }
                },
                this.config.getLoopInterval().toMillis(),
                TimeUnit.MILLISECONDS
            );
    }

    /**
     * Send some data to the remote peer.
     * @param data the data to send
     * @return a promise that resolves when the data is sent
     */
    public AsyncTask<Void> write(ByteBuffer data) {
        Objects.requireNonNull(data);

        NGEPlatform platform = NGEUtils.getPlatform();
        return platform.promisify(
            (res, rej) -> {
                // compress
                Deflater deflater = new Deflater();
                byte[] inputBytes = new byte[data.remaining()];

                data.slice().get(inputBytes);
                deflater.setInput(inputBytes);
                deflater.finish();

                ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
                byte[] buffer = new byte[1024];
                while (!deflater.finished()) {
                    int count = deflater.deflate(buffer);
                    if (count > 0) {
                        outputStream.write(buffer, 0, count);
                    } else {
                        break;
                    }
                }
                deflater.end();
                byte[] compressedBytes = outputStream.toByteArray();

                // encode to b64
                String b64data = Base64.getEncoder().encodeToString(compressedBytes);

                // split into chunks
                long packetId = packetCounter.incrementAndGet();
                int chunkLen = config.getChunkLength();
                int chunkCount = (int) Math.ceil((double) b64data.length() / chunkLen);
                List<Chunk> chunks = new ArrayList<>(chunkCount);
                for (int i = 0; i < chunkCount; i++) {
                    int start = i * chunkLen;
                    int end = Math.min(b64data.length(), (i + 1) * chunkLen);
                    chunks.add(new Chunk(b64data.substring(start, end)));
                }

                Packet packet = new Packet(
                    packetId,
                    chunks,
                    () -> {
                        res.accept(null);
                    },
                    e -> {
                        rej.accept(e);
                    }
                );
                outQueue.put(packetId, packet);

                // move stream forward
                consume();

                // notify the sending loop so that it unlocks and sends the data
                if (lockNotify != null) {
                    lockNotify.run();
                }
            },
            this.executor
        );
    }
}
