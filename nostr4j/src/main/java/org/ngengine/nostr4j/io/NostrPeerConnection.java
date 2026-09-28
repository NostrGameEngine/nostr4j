/* BSD 3-Clause License - Copyright (c) 2026, Riccardo Balbo */
package org.ngengine.nostr4j.io;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
import java.util.logging.Logger;

import org.ngengine.nostr4j.NostrPool;
import org.ngengine.nostr4j.NostrRelay;
import org.ngengine.nostr4j.RTCSettings;
import org.ngengine.nostr4j.keypair.NostrKeyPair;
import org.ngengine.nostr4j.keypair.NostrPrivateKey;
import org.ngengine.nostr4j.keypair.NostrPublicKey;
import org.ngengine.nostr4j.nip44.Nip44;
import org.ngengine.nostr4j.rtc.NostrRTCChannel;
import org.ngengine.nostr4j.rtc.NostrRTCRoom;
import org.ngengine.nostr4j.rtc.NostrRTCSocket;
import org.ngengine.nostr4j.rtc.NostrTURNPool;
import org.ngengine.nostr4j.rtc.listeners.NostrRTCPeerSocketAvailableListener;
import org.ngengine.nostr4j.rtc.listeners.NostrRTCRoomPeerDisconnectListener;
import org.ngengine.nostr4j.rtc.listeners.NostrRTCRoomPeerMessageListener;
import org.ngengine.nostr4j.rtc.signal.NostrRTCLocalPeer;
import org.ngengine.nostr4j.rtc.signal.NostrRTCPeer;
import org.ngengine.nostr4j.signer.NostrKeyPairSigner;
import org.ngengine.platform.AsyncTask;
import org.ngengine.platform.NGEPlatform;
import org.ngengine.platform.NGEUtils;

import jakarta.annotation.Nullable;

/**
 * A blocking, bidirectional byte stream between two ephemeral Nostr identities.
 *
 * <p>
 * Exchange
 * {@link #getPeerId()} out of band, then call {@link #connect(NostrPublicKey)}
 * at both endpoints. Both endpoints must use compatible settings and a common
 * signaling
 * relay.
 * </p>
 *
 * <p>The first remote session is used for the lifetime of the stream. An EOF
 * ends the connection after buffered input is consumed. If that session
 * disconnects without EOF, reads fail with an {@link IOException} after
 * buffered input is consumed.</p>
 *
 * <p>The stream uses a second reliable, ordered channel for PAUSE/RESUME
 * messages. Writes pause when the remote unread queue reaches its high
 * watermark. A receiver closes the connection with an {@link IOException}
 * if the peer keeps sending past the hard limit. With the default chunk size,
 * the low, high, and hard watermarks are 24, 32, and 512 MiB respectively.</p>
 */
public class NostrPeerConnection implements Closeable {
    private final static Logger LOGGER = Logger.getLogger(NostrPeerConnection.class.getName());


    private static final String CHANNEL = "peer-stream-v3";
    private static final String CONTROL_CHANNEL = "peer-stream-v3-control";
    private static final byte PAUSE = 1;
    private static final byte RESUME = 2;
    private static final int SOFT_LOW_BYTES = 24 * 1024 * 1024;
    private static final int SOFT_HIGH_BYTES = 32 * 1024 * 1024;
    private static final int HARD_BYTES = 512 * 1024 * 1024;

    private final Object monitor = new Object();
    private final Object writeLock = new Object();

    private final RTCSettings rtcSettings;
    private final String sessionId;
    private final NostrKeyPair localKeypair;
    private final String turnServerUrl;
    private final NostrPool signalingPool;
    private final NostrTURNPool turnPool;
    private final List<ByteBuffer> incomingChunks = new LinkedList<>();
    private final List<ByteBuffer> freedChunks = new LinkedList<>();
    private final Deque<Byte> pendingControls = new ArrayDeque<>();
    private final int chunkSize;
    private final int maxFreedChunks;
    private final int softLowChunks;
    private final int softHighChunks;
    private final int hardMaxChunks;
    private final ByteBuffer writeChunk;
    private final String connectionId;

    private volatile OutputStream writeStream;
    private volatile InputStream readStream;
    private volatile NostrPublicKey remotePeerId;
    private volatile NostrRTCRoom rtcRoom;
    private NostrKeyPair roomKeypair;
    private volatile boolean stopped = false;
    private String remoteSessionId;
    private boolean remoteEof;
    private boolean remoteEofReceived;
    private boolean disconnectDuringEofSend;
    private volatile boolean outputClosed;
    private boolean localEofSent;
    private volatile IOException outputCloseFailure;
    private IOException receiveFailure;
    private boolean remotePaused;
    private boolean pauseRequested;
    private boolean controlSending;



    public NostrPeerConnection(String connectionId) {
        this(RTCSettings.getDefault("org.ngengine.nostr4j.peer-stream", "byte-stream-v3"), connectionId, null, 1024, 16);
    }


    public NostrPeerConnection(
            RTCSettings rtcSettings,
            String connectionId,
            int chunkSize,
            int maxFreedChunks
    ) {
        this(rtcSettings, connectionId, null, chunkSize, maxFreedChunks);
    }


    public NostrPeerConnection(
            RTCSettings rtcSettings,
            String connectionId,
            @Nullable String turnServerUrl,
            int chunkSize,
            int maxFreedChunks
    ) {
        this(rtcSettings, connectionId, turnServerUrl, chunkSize, maxFreedChunks,
            chunkSize > 0 ? Math.max(1, SOFT_LOW_BYTES / chunkSize) : 0,
            chunkSize > 0 ? Math.max(2, SOFT_HIGH_BYTES / chunkSize) : 0,
            chunkSize > 0 ? HARD_BYTES / chunkSize : 0);
    }

    NostrPeerConnection(
            RTCSettings rtcSettings,
            String connectionId,
            @Nullable String turnServerUrl,
            int chunkSize,
            int maxFreedChunks,
            int softLowChunks,
            int softHighChunks,
            int hardMaxChunks
    ) {
        if (chunkSize <= 0)
            throw new IllegalArgumentException("chunkSize must be positive");
        if (maxFreedChunks < 0)
            throw new IllegalArgumentException("maxFreedChunks must not be negative");
        if (turnServerUrl != null && turnServerUrl.isBlank())
            throw new IllegalArgumentException("TURN server URL must not be blank");
        if (softLowChunks < 1 || softHighChunks <= softLowChunks || hardMaxChunks <= softHighChunks)
            throw new IllegalArgumentException("Receive chunk limits must satisfy 0 < low < high < hard");

        this.rtcSettings = Objects.requireNonNull(rtcSettings, "rtcSettings");
        this.connectionId = requireNonBlank(connectionId, "connectionId");
        this.turnServerUrl = turnServerUrl;
        this.turnPool = new NostrTURNPool();

        this.signalingPool = new NostrPool();

        this.localKeypair = new NostrKeyPair();
        this.sessionId = NostrRTCLocalPeer.newSessionId();

        this.chunkSize = chunkSize;
        this.maxFreedChunks = maxFreedChunks;
        this.softLowChunks = softLowChunks;
        this.softHighChunks = softHighChunks;
        this.hardMaxChunks = hardMaxChunks;
        this.writeChunk = NGEUtils.getPlatform().getNativeAllocator().malloc(chunkSize);
    }

    private static String requireNonBlank(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }

    private boolean selectRemotePeerLocked(NostrRTCPeer peer) {
        if (peer == null || !peer.getPubkey().equals(remotePeerId)) return false;
        if (remoteSessionId == null) remoteSessionId = peer.getSessionId();
        return remoteSessionId.equals(peer.getSessionId());
    }

    void onRemotePeerAvailable(NostrRTCPeer peer) {
        synchronized (monitor) {
            if (stopped || !selectRemotePeerLocked(peer)) return;
            monitor.notifyAll();
        }
    }

    private void openStreamChannels(NostrRTCPeer peer) {
        NostrRTCRoom room;
        synchronized (monitor) {
            if (stopped || !selectRemotePeerLocked(peer)) return;
            room = rtcRoom;
        }
        if (room != null) {
            try {
                room.createChannel(peer, CONTROL_CHANNEL, true, true);
                room.createChannel(peer, CHANNEL, true, true);
            } catch (RuntimeException error) {
                failConnection(new IOException("Unable to open peer stream channels", error));
            }
        }
    }

    void onRemotePeerDisconnected(NostrRTCPeer peer) {
        synchronized (monitor) {
            if (stopped || remoteSessionId == null ||
                !peer.getPubkey().equals(remotePeerId) ||
                !remoteSessionId.equals(peer.getSessionId())) return;
            if (remoteEofReceived || localEofSent) {
                remoteEof = true;
            } else if (outputClosed && outputCloseFailure == null) {
                disconnectDuringEofSend = true;
                monitor.notifyAll();
                return;
            } else {
                receiveFailure = new IOException("Remote peer disconnected without EOF");
            }
            monitor.notifyAll();
        }
        closeAfterRemoteEnd();
    }

    private void closeAfterRemoteEnd() {
        try {
            close();
        } catch (IOException error) {
            LOGGER.warning("Failed to close peer connection: " + error.getMessage());
        }
    }

    private NostrRTCChannel awaitChannel(String name) throws IOException {
        synchronized (monitor) {
            while (!stopped) {
                if (receiveFailure != null) throw receiveFailure;
                if (remoteEof) throw new IOException("Remote peer sent EOF");
                if (disconnectDuringEofSend)
                    throw new IOException("Remote peer disconnected before EOF was sent");
                NostrRTCRoom room = rtcRoom;
                if (room == null)
                    throw new IOException("Peer connection has not been started");
                for (NostrRTCPeer peer : room.getPeers()) {
                    if (selectRemotePeerLocked(peer)) {
                        room.createChannel(peer, CONTROL_CHANNEL, true, true);
                        return room.createChannel(peer, name, true, true);
                    }
                }
                try {
                    monitor.wait();
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new InterruptedIOException("Interrupted while waiting for peer");
                }
            }
            throw new IOException("Peer connection is closed");
        }
    }

    void sendFrame(ByteBuffer frame) throws IOException {
        NostrRTCChannel channel = awaitChannel(CHANNEL);
        NostrRTCRoom room = rtcRoom;
        if (stopped || room == null)
            throw new IOException("Peer connection is closed");
        try {
            room.send(channel, frame).await();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("Interrupted while writing to peer");
        } catch (Exception error) {
            throw new IOException("Error while writing to peer", error);
        }
    }

    AsyncTask<Void> sendControlFrame(byte command) throws IOException {
        NostrRTCRoom room = rtcRoom;
        if (room == null || stopped) throw new IOException("Peer connection is closed");
        NostrRTCPeer selected = null;
        for (NostrRTCPeer peer : room.getPeers()) {
            synchronized (monitor) {
                if (selectRemotePeerLocked(peer)) {
                    selected = peer;
                    break;
                }
            }
        }
        if (selected == null) throw new IOException("Remote peer is unavailable for stream control");
        NostrRTCChannel channel = room.createChannel(selected, CONTROL_CHANNEL, true, true);
        return room.send(channel, ByteBuffer.wrap(new byte[] { command }));
    }

    private void queueControlLocked(byte command) {
        pendingControls.addLast(command);
    }

    private void dispatchControl() {
        byte command;
        synchronized (monitor) {
            if (controlSending || pendingControls.isEmpty() || stopped) return;
            controlSending = true;
            command = pendingControls.removeFirst();
        }
        try {
            sendControlFrame(command)
                .then(ignored -> {
                    controlSent();
                    return null;
                })
                .catchException(error -> failConnection(new IOException("Unable to send stream control", error)));
        } catch (IOException | RuntimeException error) {
            failConnection(new IOException("Unable to send stream control", error));
        }
    }

    private void controlSent() {
        synchronized (monitor) {
            controlSending = false;
        }
        dispatchControl();
    }

    private void failConnection(IOException error) {
        synchronized (monitor) {
            if (stopped || remoteEofReceived) return;
            receiveFailure = error;
            incomingChunks.clear();
            freedChunks.clear();
            pendingControls.clear();
            monitor.notifyAll();
        }
        closeAfterRemoteEnd();
    }

    void receiveControlFrame(NostrRTCPeer peer, ByteBuffer source) {
        synchronized (monitor) {
            if (stopped || !selectRemotePeerLocked(peer)) return;
        }
        receiveControlFrame(source);
    }

    void receiveControlFrame(ByteBuffer source) {
        boolean invalid = false;
        synchronized (monitor) {
            if (stopped || remoteEofReceived) return;
            if (source.remaining() != 1) {
                invalid = true;
            } else {
                byte command = source.get(source.position());
                if (command == PAUSE) remotePaused = true;
                else if (command == RESUME) remotePaused = false;
                else invalid = true;
                monitor.notifyAll();
            }
        }
        if (invalid) failConnection(new IOException("Invalid stream control frame"));
    }

    private void awaitResume() throws IOException {
        synchronized (monitor) {
            while (remotePaused && !stopped && receiveFailure == null && !remoteEof) {
                try {
                    monitor.wait();
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new InterruptedIOException("Interrupted while waiting for stream resume");
                }
            }
            if (receiveFailure != null) throw receiveFailure;
            if (stopped || remoteEof) throw new IOException("Peer connection is closed");
        }
    }

    void receiveFrame(ByteBuffer source) {
        boolean endOfStream = false;
        boolean hardLimitExceeded = false;
        synchronized (monitor) {
            if (stopped || receiveFailure != null)
                return;
            if (remoteEof) return;
            ByteBuffer frame = source.slice();
            if (!frame.hasRemaining()) {
                remoteEof = true;
                remoteEofReceived = true;
                endOfStream = true;
            } else {
                while (frame.hasRemaining()) {
                    if (incomingChunks.size() >= hardMaxChunks) {
                        receiveFailure = new IOException("Maximum received chunk limit exceeded");
                        incomingChunks.clear();
                        freedChunks.clear();
                        hardLimitExceeded = true;
                        break;
                    }
                    ByteBuffer chunk;
                    if (freedChunks.isEmpty()) {
                        chunk = NGEUtils.getPlatform().getNativeAllocator().malloc(chunkSize);
                    } else {
                        chunk = freedChunks.remove(0);
                    }
                    chunk.clear();
                    int remaining = Math.min(frame.remaining(), chunk.remaining());
                    for (int i = 0; i < remaining; i++)
                        chunk.put(frame.get());
                    chunk.flip();
                    incomingChunks.add(chunk);
                }
                if (!hardLimitExceeded && !pauseRequested && incomingChunks.size() >= softHighChunks) {
                    pauseRequested = true;
                    queueControlLocked(PAUSE);
                }
            }
            monitor.notifyAll();
        }
        if (!endOfStream && !hardLimitExceeded) dispatchControl();
        if (endOfStream || hardLimitExceeded) closeAfterRemoteEnd();
    }

    void receiveFrame(NostrRTCPeer peer, ByteBuffer source) {
        synchronized (monitor) {
            if (stopped || !selectRemotePeerLocked(peer)) return;
        }
        receiveFrame(source);
    }

    public NostrPublicKey getPeerId() {
        return localKeypair.getPublicKey();
    }

    public void connect(NostrPublicKey peerId) throws IOException {
        synchronized (monitor) {
            if (stopped)
                throw new IOException("Peer connection is closed");
            Objects.requireNonNull(peerId, "peerId");
            if (peerId.equals(localKeypair.getPublicKey())) {
                throw new IllegalArgumentException("Cannot connect to the local peer identity");
            }
            if (this.rtcRoom != null) {
                if (!Objects.equals(this.remotePeerId, peerId)) {
                    throw new IOException("Connection already established with a different peer");
                } else {
                    LOGGER.warning("Connection already established with the same peer");
                }
                return;
            }

            this.remotePeerId = peerId;
            NostrPrivateKey roomPrivateKey = generateRoomKey(connectionId, localKeypair.getPrivateKey(), peerId);
            roomKeypair = new NostrKeyPair(roomPrivateKey);

            NostrKeyPairSigner localSigner = new NostrKeyPairSigner(this.localKeypair);

            this.rtcRoom = new NostrRTCRoom(
                    rtcSettings,
                    new NostrRTCLocalPeer(rtcSettings, localSigner, sessionId, roomKeypair, turnServerUrl),
                    roomKeypair,
                    signalingPool,
                    turnPool);

            this.rtcRoom.addPeerSocketAvailableListener(new NostrRTCPeerSocketAvailableListener() {
                @Override
                public void onRoomPeerSocketAvailable(NostrRTCPeer peer, NostrRTCSocket conn) {
                    onRemotePeerAvailable(peer);
                    openStreamChannels(peer);
                }
            });

            this.rtcRoom.addDisconnectionListener(new NostrRTCRoomPeerDisconnectListener() {
                @Override
                public void onRoomPeerDisconnected(NostrRTCPeer peer, NostrRTCSocket socket) {
                    onRemotePeerDisconnected(peer);
                }
            });

            this.rtcRoom.addMessageListener(new NostrRTCRoomPeerMessageListener() {
                @Override
                public void onRoomPeerMessage(NostrRTCPeer peer, NostrRTCSocket socket, NostrRTCChannel channel,
                        ByteBuffer bbf, boolean turn) {
                    if (CHANNEL.equals(channel.getName())) receiveFrame(peer, bbf);
                    else if (CONTROL_CHANNEL.equals(channel.getName())) receiveControlFrame(peer, bbf);
                }
            });
        }

        try {
            this.rtcRoom.start().await();
        } catch (Exception e) {
            try {
                close();
            } catch (Exception closeException) {
                e.addSuppressed(closeException);
            }
            throw new IOException("Failed to start RTC room", e);
        }

    }

    public InputStream getInputStream() {
        synchronized (monitor) {
            if (readStream == null) {
                readStream = new InputStream() {
                    byte[] buffer = new byte[1];

                    @Override
                    public int read() throws IOException {
                        int l = read(buffer, 0, 1);
                        return l == -1 ? -1 : buffer[0] & 0xFF;
                    }

                    @Override
                    public int read(byte[] b, int off, int len) throws IOException {
                        Objects.checkFromIndexSize(off, len, b.length);
                        if (len == 0)
                            return 0;
                        boolean resumeNeeded = false;
                        int result;
                        try {
                            synchronized (monitor) {
                                while (incomingChunks.isEmpty() && !remoteEof && receiveFailure == null && !stopped)
                                    monitor.wait();
                                if (stopped && !remoteEof && receiveFailure == null)
                                    throw new IOException("Peer connection is closed");
                                if (incomingChunks.isEmpty()) {
                                    if (receiveFailure != null)
                                        throw receiveFailure;
                                    return -1;
                                }

                                ByteBuffer chunk = incomingChunks.get(0);
                                int toRead = Math.min(len, chunk.remaining());
                                chunk.get(b, off, toRead);
                                if (!chunk.hasRemaining()) {
                                    if (incomingChunks.remove(0) != chunk) {
                                        throw new IllegalStateException(
                                                "Chunk mismatch while removing from incomingChunks");
                                    }
                                    chunk.clear();
                                    if (freedChunks.size() < maxFreedChunks) {
                                        // cycle back into freed chunks
                                        freedChunks.add(chunk);
                                    }
                                    if (pauseRequested && incomingChunks.size() <= softLowChunks) {
                                        pauseRequested = false;
                                        queueControlLocked(RESUME);
                                        resumeNeeded = true;
                                    }
                                }
                                result = toRead;
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new InterruptedIOException("Interrupted while waiting for incoming chunks");
                        }
                        if (resumeNeeded) dispatchControl();
                        return result;
                    }
                };
            }
            return readStream;
        }
    }

    public OutputStream getOutputStream() {
        synchronized (monitor) {
            if (writeStream == null) {
                writeStream = new OutputStream() {
                    @Override
                    public void write(int b) throws IOException {
                        write(new byte[] { (byte) b }, 0, 1);
                    }

                    @Override
                    public void write(byte[] b, int off, int len) throws IOException {
                        Objects.checkFromIndexSize(off, len, b.length);
                        synchronized (writeLock) {
                            if (outputClosed || stopped)
                                throw new IOException("Peer output is closed");
                            int sent = 0;
                            while (sent < len) {
                                awaitResume();
                                int toSend = Math.min(chunkSize, len - sent);
                                writeChunk.clear();
                                writeChunk.put(b, off + sent, toSend);
                                writeChunk.flip();
                                sendFrame(writeChunk);
                                sent += toSend;
                            }
                        }
                    }

                    @Override
                    public void close() throws IOException {
                        synchronized (writeLock) {
                            if (outputClosed) {
                                if (outputCloseFailure != null)
                                    throw outputCloseFailure;
                                return;
                            }
                            if (stopped)
                                throw new IOException("Peer connection is closed");
                            outputClosed = true;
                            try {
                                sendFrame(ByteBuffer.allocate(0));
                                boolean endAfterSend;
                                synchronized (monitor) {
                                    localEofSent = true;
                                    endAfterSend = disconnectDuringEofSend;
                                    if (endAfterSend) {
                                        remoteEof = true;
                                        monitor.notifyAll();
                                    }
                                }
                                if (endAfterSend) closeAfterRemoteEnd();
                            } catch (IOException error) {
                                boolean remoteEnded;
                                boolean endAfterFailure;
                                synchronized (monitor) {
                                    remoteEnded = remoteEofReceived;
                                    endAfterFailure = disconnectDuringEofSend;
                                    if (!remoteEnded) {
                                        outputCloseFailure = error;
                                        if (endAfterFailure) {
                                            receiveFailure = error;
                                            monitor.notifyAll();
                                        }
                                    }
                                }
                                if (endAfterFailure) closeAfterRemoteEnd();
                                if (!remoteEnded) throw error;
                            }
                        }
                    }
                };
            }
            return writeStream;
        }
    }

    /**
     * Aborts the connection; use the output stream's close() for an ordered EOF.
     */
    @Override
    public void close() throws IOException {
        NostrRTCRoom room;
        synchronized (monitor) {
            if (stopped)
                return;
            stopped = true;
            outputClosed = true;
            pendingControls.clear();
            freedChunks.clear();
            if (!remoteEof && receiveFailure == null) incomingChunks.clear();
            monitor.notifyAll();
            room = rtcRoom;
            rtcRoom = null;
        }
        try {
            if (room != null)
                room.close();
        } finally {
            try {
                for (NostrRelay relay : signalingPool.clean())
                    relay.disconnect("peer-stream-closed");
            } finally {
                try {
                    if (roomKeypair != null)
                        roomKeypair.close();
                } finally {
                    try {
                        localKeypair.close();
                    } finally {
                        try {
                            turnPool.close();
                        } catch (Exception error) {
                            LOGGER.warning("Failed to close TURN pool: " + error.getMessage());
                        }
                    }
                }
            }
        }
    }

    private static NostrPrivateKey generateRoomKey(String id, NostrPrivateKey localPrivate,
            NostrPublicKey remotePublic) {
        BigInteger order = new BigInteger(
                "FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364141", 16);
        byte[] conversationKey = Nip44.getConversationKeySync(localPrivate, remotePublic);
        byte[] candidate = new byte[32];

        try {
            for (int counter = 0; counter < 256; counter++) {
                byte[] info = ("nostr4j/rtc-room-key/v1/" + id + "/" + counter)
                        .getBytes(StandardCharsets.UTF_8);

                ByteBuffer derived = NGEPlatform.get().hkdf_expand(
                        ByteBuffer.wrap(conversationKey),
                        ByteBuffer.wrap(info),
                        32);
                derived.get(candidate);

                BigInteger scalar = new BigInteger(1, candidate);
                if (scalar.signum() > 0 && scalar.compareTo(order) < 0) {
                    return NostrPrivateKey.fromBytes(candidate); // crea una copia
                }
            }
            throw new IllegalStateException("Unable to derive a valid secp256k1 key");
        } finally {
            Arrays.fill(candidate, (byte) 0);
            Arrays.fill(conversationKey, (byte) 0);
        }
    }
}
