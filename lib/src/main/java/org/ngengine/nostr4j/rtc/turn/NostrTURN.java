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
import org.ngengine.nostr4j.platform.AsyncTask;
import org.ngengine.nostr4j.platform.NostrExecutor;
import org.ngengine.nostr4j.platform.Platform;
import org.ngengine.nostr4j.rtc.NostrRTCLocalPeer;
import org.ngengine.nostr4j.rtc.NostrRTCPeer;
import org.ngengine.nostr4j.utils.NostrUtils;

// A turn server that uses nostr relays to relay encrypted and compressed data
public class NostrTURN {

    private static final Logger logger = Logger.getLogger(NostrTURN.class.getName());

    private static class Chunk {

        String data;
        boolean ack;
        boolean sent;
        Instant lastAttempt;

        Chunk(String data) {
            this.data = data;
        }
    }

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
    private final NostrExecutor executor;
    private final List<NostrTURNListener> listeners = new CopyOnWriteArrayList<>();

    private Packet inPacket;
    private Runnable outQueueNotify;
    private volatile boolean stopped = false;

    public NostrTURN(String connectionId, NostrRTCLocalPeer localPeer, NostrRTCPeer remotePeer, NostrTURNSettings config) {
        Platform platform = NostrUtils.getPlatform();
        this.connectionId = connectionId;
        this.localPeer = localPeer;
        this.remotePeer = remotePeer;
        this.config = config;
        this.executor = platform.newPoolExecutor();

        // setup local peer turn server
        this.inPool = new NostrPool(PassthroughEventTracker.class);
        this.inPool.connectRelay(new NostrRelay(localPeer.getTurnServer(), executor));
        this.inSub =
            this.inPool.subscribe(
                    new NostrFilter()
                        .author(this.remotePeer.getPubkey())
                        .kind(this.config.getKind())
                        .tag("d", "turn-" + this.connectionId)
                );
        this.inSub.listenEvent((event, stored) -> {
                onTurnEvent(event, false);
            });

        // setup remote peer turn server
        this.outPool = new NostrPool(PassthroughEventTracker.class);
        this.outPool.connectRelay(new NostrRelay(remotePeer.getTurnServer(), executor));
        this.outSub =
            this.outPool.subscribe(
                    new NostrFilter()
                        .author(this.localPeer.getPubkey())
                        .kind(this.config.getKind())
                        .tag("d", "turn-" + this.connectionId)
                );
        this.outSub.listenEvent((event, stored) -> {
                onTurnEvent(event, true);
            });
    }

    public void addListener(NostrTURNListener listener) {
        this.listeners.add(listener);
    }

    public void removeListener(NostrTURNListener listener) {
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

    // internal- called from executor thread
    private void onTurnEvent(SignedNostrEvent event, boolean fromRemote) {
        String content = event.getContent();
        this.localPeer.getSigner()
            .decrypt(content, remotePeer.getPubkey())
            .then(decrypted -> {
                String prefix = decrypted.substring(0, 3);
                if (prefix.equals("ack") && fromRemote) {
                    this.onReceivedAck(decrypted.substring(3));
                } else if (prefix.equals("pkt") && !fromRemote) {
                    this.onReceivedPacket(decrypted.substring(3));
                } else {
                    logger.warning("Unknown TURN event: " + prefix + " fromRemote " + fromRemote);
                }
                return null;
            });
    }

    // internal- called from executor thread
    private void onReceivedAck(String data) {
        StringTokenizer tokenizer = new StringTokenizer(data, ",");
        int packetId = NostrUtils.safeInt(tokenizer.nextToken());
        Packet packet = outQueue.get(packetId);
        if (packet == null) return;
        int chunkId = NostrUtils.safeInt(tokenizer.nextToken());
        Chunk chunk = packet.chunks.get(chunkId);
        if (chunk == null || chunk.ack) return;
        chunk.ack = true;
        packet.ack++;

        // move stream forward
        this.consume();
    }

    // internal- called from executor thread
    private void onReceivedPacket(String data) {
        StringTokenizer tokenizer = new StringTokenizer(data, ",");
        int packetId = NostrUtils.safeInt(tokenizer.nextToken());

        if (this.inPacket != null && this.inPacket.id != packetId) {
            logger.warning("Received packet with id " + packetId + " but current packet is " + this.inPacket.id);
            return;
        }
        if (this.inPacket == null) {
            this.inPacket = new Packet(packetId, new ArrayList<>(), null, null);
        }

        int chunkId = NostrUtils.safeInt(tokenizer.nextToken());
        int nChunks = NostrUtils.safeInt(tokenizer.nextToken());
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
                event.setKind(this.config.getKind());
                event.setCreatedAt(Instant.now());
                event.setContent(encrypted);
                event.setTag("d", "turn-" + this.connectionId);
                event.setExpirationTimestamp(Instant.now().plus(this.config.getPacketTimeout()));

                return this.localPeer.getSigner()
                    .sign(event)
                    .compose(signed -> {
                        return this.inPool.send(signed);
                    });
            });
    }

    // internal: call only from executor thread
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
                    byte[] buffer = new byte[config.getChunkLength()];
                    int decompressedSize = inflater.inflate(buffer);
                    inflater.end();

                    ByteBuffer byteBuffer = ByteBuffer.wrap(buffer, 0, decompressedSize);
                    for (NostrTURNListener listener : listeners) {
                        listener.onTurnPacket(remotePeer, byteBuffer);
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

    private void loop() {
        this.executor.runLater(
                () -> {
                    Collection<Packet> packets = outQueue.values();
                    if (packets.isEmpty()) {
                        Platform platform = NostrUtils.getPlatform();
                        platform
                            .wrapPromise((res, rej) -> {
                                assert outQueueNotify == null;
                                outQueueNotify =
                                    () -> {
                                        res.accept(null);
                                    };
                            })
                            .await();
                    }
                    if (this.stopped) return null;
                    Map.Entry<Long, Packet> entry = outQueue.size() > 0 ? outQueue.entrySet().iterator().next() : null;
                    if (entry != null) {
                        Packet nextPacket = entry.getValue();
                        for (int i = 0; i < nextPacket.chunks.size(); i++) {
                            Chunk chunk = nextPacket.chunks.get(i);
                            Instant lastAttempt = chunk.lastAttempt;

                            // Skip chunk if not acked but still likely to be in transit
                            if (Instant.now().isAfter(lastAttempt.plus(this.config.getMaxLatency()))) {
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

                            logger.fine("Sending chunk " + nextPacket.id + "," + chunkIndex);

                            // Create and send event
                            UnsignedNostrEvent event = new UnsignedNostrEvent();
                            event.setKind(this.config.getKind());
                            event.setCreatedAt(Instant.now());
                            event.setContent(encrypted);
                            event.setTag("d", "turn-" + this.connectionId);
                            event.setExpirationTimestamp(Instant.now().plus(this.config.getPacketTimeout()));

                            SignedNostrEvent signed = this.localPeer.getSigner().sign(event).await();

                            this.outPool.send(signed).await();
                        }
                    }

                    if (this.stopped) return null;
                    this.loop();
                    return null;
                },
                this.config.getLoopInterval().toMillis(),
                TimeUnit.MILLISECONDS
            );
    }

    public AsyncTask<Void> write(ByteBuffer data) {
        Platform platform = NostrUtils.getPlatform();
        return platform.promisify(
            (res, rej) -> {
                // compress
                Deflater deflater = new Deflater();
                byte dataBytes[] = new byte[data.remaining()];
                data.slice().get(dataBytes);
                deflater.setInput(dataBytes);
                deflater.finish();
                byte[] buffer = new byte[dataBytes.length];
                int compressedSize = deflater.deflate(buffer);
                deflater.end();

                // encode to b64
                String b64data = Base64.getEncoder().encodeToString(Arrays.copyOf(buffer, compressedSize));

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
                if (outQueueNotify != null) {
                    outQueueNotify.run();
                }
            },
            this.executor
        );
    }
}
