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

package org.ngengine.nostr4j.turn.ref;

import com.google.gson.JsonObject;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.websocket.server.config.JettyWebSocketServletContainerInitializer;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.websocket.api.Callback;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.StatusCode;
import org.ngengine.nostr4j.event.SignedNostrEvent;
import org.ngengine.nostr4j.keypair.NostrPublicKey;
import org.ngengine.nostr4j.rtc.turn.event.NostrTURNCodec;
import org.ngengine.nostr4j.signer.NostrKeyPairSigner;
import org.ngengine.nostr4j.utils.NostrRoomProof;
import org.ngengine.platform.NGEUtils;

/**
 * Minimal NIP-DC TURN reference server.
 *
 * <p>High-level flow:
 * 1) WebSocket opens -> server sends `challenge` with difficulty+token.
 * 2) Client sends signed `connect` with PoW + roomproof + routing tags.
 * 3) Server validates and creates websocket-scoped virtual socket mapping.
 * 4) Server sends `ack` for accepted virtual socket.
 * 5) `data` frames are forwarded to reciprocal sockets; if no reciprocal target exists,
 *    frames are queued temporarily.
 * 6) Queue overflow/timeout results in sender `disconnect` with peer-unreachable error.
 * 7) `disconnect` tears down the virtual socket and is relayed to reciprocal side.
 *
 * <p>Security and isolation principles:
 * - signatures are always verified before semantic handling
 * - roomproof binds connect to authorized room participant
 * - virtual sockets are scoped to the originating websocket session
 * - routing requires full tuple match in TurnVirtualSocket reciprocity checks
 */
public final class TurnServer {

    static final Logger logger = Logger.getLogger(TurnServer.class.getName());
    private static final int DEFAULT_MAX_QUEUED_FRAMES = 2048;
    private static final int DEFAULT_QUEUE_MAX_AGE_SECONDS = 30;
    private static final String PEER_UNREACHABLE_REASON = "peer unreachable";

    // Active websocket clients keyed by session object.
    final Map<Session, TurnClientConnection> clients = new ConcurrentHashMap<Session, TurnClientConnection>();
    private final Server jettyServer;
    private final TurnEventFactory eventFactory;
    final int difficulty;
    final int challengeTtlSeconds;
    final String host;
    private final int maxQueuedFrames;
    private final long queueMaxAgeMillis;
    // Separate lock guarding queuedFrames composite operations.
    private final Object queuedFramesLock = new Object();
    // FIFO queue of frames awaiting reciprocal target connect.
    private final ArrayDeque<QueuedFrame> queuedFrames = new ArrayDeque<QueuedFrame>();

    /**
     * One queued data frame entry.
     *
     * <p>Stored as raw frame bytes so we can rewrite only VSOCKET_ID at delivery time
     * (header and encrypted payload remain opaque and untouched).
     */
    private static final class QueuedFrame {

        private final TurnVirtualSocket senderSocket;
        private final Session senderSession;
        private final byte[] frameBytes;
        private final long queuedAtMillis;

        QueuedFrame(TurnVirtualSocket senderSocket, Session senderSession, byte[] frameBytes, long queuedAtMillis) {
            this.senderSocket = senderSocket;
            this.senderSession = senderSession;
            this.frameBytes = frameBytes;
            this.queuedAtMillis = queuedAtMillis;
        }
    }

    private static final class ExpiredSender {

        private final Session session;
        private final long vsocketId;

        ExpiredSender(Session session, long vsocketId) {
            this.session = session;
            this.vsocketId = vsocketId;
        }
    }

    public final class TurnHandler implements Session.Listener.AutoDemanding {

        private final TurnServer turnServer;

        /**
         * @param turnServer
         */
        TurnHandler(TurnServer turnServer) {
            this.turnServer = turnServer;
        }

        private Session wsSession;

        @Override
        public void onWebSocketOpen(Session session) {
            // 1) Attach websocket-scoped client state.
            this.wsSession = session;
            TurnClientConnection connection = new TurnClientConnection(
                session,
                this.turnServer.difficulty,
                this.turnServer.challengeTtlSeconds
            );
            this.turnServer.clients.put(session, connection);
            // 2) Send challenge immediately as required by protocol.
            this.turnServer.sendChallenge(connection);
        }

        @Override
        public void onWebSocketBinary(ByteBuffer payload, Callback callback) {
            try {
                // Copy incoming payload to decouple from Jetty buffer lifecycle.
                ByteBuffer incomingFrame = TurnServer.deepCopy(payload);
                callback.succeed();

                TurnClientConnection connection = this.turnServer.clients.get(this.wsSession);
                if (connection == null) {
                    // Session already closed/evicted.
                    return;
                }

                // Envelope-level decode.
                long envelopeVsocketId = NostrTURNCodec.extractVsocketId(incomingFrame.asReadOnlyBuffer());

                // Header decode + signature verification.
                SignedNostrEvent header = NostrTURNCodec.decodeHeader(incomingFrame.asReadOnlyBuffer());
                this.turnServer.validateHeaderSignature(header);

                // Dispatch by control type.
                String type = TurnJson.safeString(header.getFirstTagFirstValue("t"));
                if ("connect".equals(type)) {
                    this.turnServer.handleConnect(connection, header, envelopeVsocketId);
                    return;
                }
                if ("data".equals(type)) {
                    this.turnServer.handleData(connection, header, incomingFrame, envelopeVsocketId);
                    return;
                }
                if ("disconnect".equals(type)) {
                    this.turnServer.handleDisconnect(connection, header, envelopeVsocketId);
                    return;
                }

                this.turnServer.closeConnection(this.wsSession, "invalid-event-type");
            } catch (Exception ex) {
                // Any malformed frame is treated as protocol violation.
                TurnServer.logger.log(Level.WARNING, "Reference TURN frame error", ex);
                this.turnServer.closeConnection(this.wsSession, "invalid-frame");
            }
        }

        @Override
        public void onWebSocketClose(int statusCode, String reason) {
            this.turnServer.closeConnection(this.wsSession, reason == null ? "closed" : reason);
        }

        @Override
        public void onWebSocketError(Throwable cause) {
            TurnServer.logger.log(Level.WARNING, "Reference TURN socket error", cause);
            this.turnServer.closeConnection(this.wsSession, "socket-error");
        }
    }

    public TurnServer(int port, NostrKeyPairSigner serverSigner, int difficulty, int challengeTtlSeconds) {
        this(
            "127.0.0.1",
            port,
            serverSigner,
            difficulty,
            challengeTtlSeconds,
            DEFAULT_MAX_QUEUED_FRAMES,
            DEFAULT_QUEUE_MAX_AGE_SECONDS
        );
    }

    public TurnServer(String host, int port, NostrKeyPairSigner serverSigner, int difficulty, int challengeTtlSeconds) {
        this(
            host,
            port,
            serverSigner,
            difficulty,
            challengeTtlSeconds,
            DEFAULT_MAX_QUEUED_FRAMES,
            DEFAULT_QUEUE_MAX_AGE_SECONDS
        );
    }

    public TurnServer(
        int port,
        NostrKeyPairSigner serverSigner,
        int difficulty,
        int challengeTtlSeconds,
        int maxQueuedFrames,
        int queueMaxAgeSeconds
    ) {
        this("127.0.0.1", port, serverSigner, difficulty, challengeTtlSeconds, maxQueuedFrames, queueMaxAgeSeconds);
    }

    public TurnServer(
        String host,
        int port,
        NostrKeyPairSigner serverSigner,
        int difficulty,
        int challengeTtlSeconds,
        int maxQueuedFrames,
        int queueMaxAgeSeconds
    ) {
        // Validate constructor parameters early; these values shape server behavior.
        if (host == null || host.trim().isEmpty()) {
            throw new IllegalArgumentException("host must not be blank");
        }
        if (port <= 0) {
            throw new IllegalArgumentException("port must be > 0");
        }
        if (difficulty <= 0) {
            throw new IllegalArgumentException("difficulty must be > 0");
        }
        if (challengeTtlSeconds <= 0) {
            throw new IllegalArgumentException("challengeTtlSeconds must be > 0");
        }
        if (maxQueuedFrames <= 0) {
            throw new IllegalArgumentException("maxQueuedFrames must be > 0");
        }
        if (queueMaxAgeSeconds <= 0) {
            throw new IllegalArgumentException("queueMaxAgeSeconds must be > 0");
        }

        // Initialize signer/event factory and queue policy configuration.
        this.eventFactory = new TurnEventFactory(Objects.requireNonNull(serverSigner, "serverSigner cannot be null"));
        this.host = host.trim();
        this.difficulty = difficulty;
        this.challengeTtlSeconds = challengeTtlSeconds;
        this.maxQueuedFrames = maxQueuedFrames;
        this.queueMaxAgeMillis = queueMaxAgeSeconds * 1000L;

        // Jetty bootstrap.
        this.jettyServer = new Server();
        ServerConnector connector = new ServerConnector(this.jettyServer);
        connector.setHost(this.host);
        connector.setPort(port);
        this.jettyServer.addConnector(connector);

        ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);
        context.setContextPath("/");
        this.jettyServer.setHandler(context);

        // Single endpoint for TURN websocket protocol.
        JettyWebSocketServletContainerInitializer.configure(
            context,
            (servletContext, container) -> {
                container.setMaxBinaryMessageSize(1024L * 1024L);
                container.setIdleTimeout(java.time.Duration.ofSeconds(60));
                container.addMapping(
                    "/turn",
                    (upgradeRequest, upgradeResponse) -> {
                        return new TurnHandler(this);
                    }
                );
            }
        );
    }

    public void start() throws Exception {
        this.jettyServer.start();
    }

    public void join() throws InterruptedException {
        this.jettyServer.join();
    }

    public void stop() throws Exception {
        this.jettyServer.stop();
    }

    public int getPort() {
        ServerConnector connector = (ServerConnector) this.jettyServer.getConnectors()[0];
        return connector.getLocalPort();
    }

    public NostrPublicKey getServerPubkey() {
        return this.eventFactory.getServerPubkey();
    }

    void validateHeaderSignature(SignedNostrEvent header) {
        // Kind gate first, then signature gate.
        if (header.getKind() != TurnEventFactory.TURN_KIND) {
            throw new IllegalArgumentException("Invalid TURN kind " + header.getKind());
        }
        try {
            if (!header.verify()) {
                throw new IllegalArgumentException("Invalid TURN signature");
            }
        } catch (Exception ex) {
            throw new IllegalArgumentException("TURN signature verification failed", ex);
        }
    }

    /**
     * Handshake step 2: validate connect and issue ack + vsocketId.
     */
    void handleConnect(TurnClientConnection connection, SignedNostrEvent header, long envelopeVsocketId) {
        // Periodic opportunistic cleanup so stale queues don't accumulate.
        pruneExpiredQueuedFrames();
        // Challenge must still be valid for this websocket client.
        if (connection.getChallenge() == null || Instant.now().isAfter(connection.getChallengeExpiresAt())) {
            closeConnection(connection.getWsSession(), "challenge-expired");
            return;
        }
        if (envelopeVsocketId == 0L) {
            closeConnection(connection.getWsSession(), "invalid-vsocket-id");
            return;
        }

        // Parse connect content and bind it to current websocket challenge.
        JsonObject content = TurnJson.parseObject(header.getContent());
        String challenge = TurnJson.readString(content, "challenge");
        long contentVsocketId = NGEUtils.safeLong(TurnJson.readString(content, "vsocketId"));
        if (!connection.getChallenge().equals(challenge)) {
            closeConnection(connection.getWsSession(), "challenge-mismatch");
            return;
        }
        if (contentVsocketId == 0L || contentVsocketId != envelopeVsocketId) {
            closeConnection(connection.getWsSession(), "vsocket-mismatch");
            return;
        }

        // PoW: declared difficulty must match server challenge difficulty.
        String difficultyTag = TurnJson.safeString(header.getFirstTagSecondValue("nonce"));
        if (!Integer.toString(connection.getDifficulty()).equals(difficultyTag)) {
            closeConnection(connection.getWsSession(), "difficulty-mismatch");
            return;
        }

        if (!header.checkPow(connection.getDifficulty())) {
            closeConnection(connection.getWsSession(), "pow-failed");
            return;
        }

        // Extract routing dimensions required by NIP-DC connect.
        String roomHex = TurnJson.safeString(header.getFirstTagFirstValue("P"));
        String senderSessionId = TurnJson.safeString(header.getFirstTagFirstValue("d"));
        String protocolId = TurnJson.safeString(header.getFirstTagFirstValue("i"));
        String applicationId = TurnJson.safeString(header.getFirstTagFirstValue("y"));
        String targetHex = TurnJson.safeString(header.getFirstTagFirstValue("p"));
        String channelLabel = TurnJson.safeString(header.getFirstTagSecondValue("p"));
        if (
            roomHex.isEmpty() ||
            senderSessionId.isEmpty() ||
            protocolId.isEmpty() ||
            applicationId.isEmpty() ||
            targetHex.isEmpty() ||
            channelLabel.isEmpty()
        ) {
            closeConnection(connection.getWsSession(), "missing-connect-routing-tags");
            return;
        }

        // Parse and verify structural pubkeys.
        NostrPublicKey roomPubkey = NostrPublicKey.fromHex(roomHex);
        NostrPublicKey senderPubkey = header.getPubkey();
        NostrPublicKey targetPubkey = NostrPublicKey.fromHex(targetHex);

        // roomproof challenge for connect is the challenge token string.
        if (!verifyRoomProof(header, roomPubkey, challenge)) {
            closeConnection(connection.getWsSession(), "roomproof-failed");
            return;
        }

        // VSOCKET_ID uniqueness is websocket-local (per TurnClientConnection).
        long vsocketId = envelopeVsocketId;
        if (connection.getSockets().containsKey(Long.valueOf(vsocketId))) {
            closeConnection(connection.getWsSession(), "vsocket-collision");
            return;
        }
        // Build accepted socket identity entry.
        TurnVirtualSocket senderSocket = new TurnVirtualSocket(
            vsocketId,
            roomPubkey,
            senderPubkey,
            targetPubkey,
            senderSessionId,
            protocolId,
            applicationId,
            channelLabel
        );

        // Store accepted socket then ack it.
        connection.getSockets().put(Long.valueOf(vsocketId), senderSocket);
        sendFrame(connection.getWsSession(), eventFactory.createAck(senderSocket), null, vsocketId);
        // Recipient might already be connected now; flush matching queued frames.
        drainQueuedFramesForRecipient(connection, senderSocket);
    }

    /**
     * Data step: server is blind to payload, only routes by header identity + reciprocal socket.
     */
    void handleData(TurnClientConnection connection, SignedNostrEvent header, ByteBuffer fullFrame, long envelopeVsocketId) {
        pruneExpiredQueuedFrames();
        // VSOCKET_ID=0 data is invalid and ignored by policy.
        if (envelopeVsocketId == 0L) {
            return;
        }

        // Sender socket must be previously accepted for this websocket.
        TurnVirtualSocket senderSocket = connection.getSockets().get(Long.valueOf(envelopeVsocketId));
        if (senderSocket == null) {
            return;
        }
        // Header pubkey must match accepted sender socket identity.
        if (!senderSocket.getClientPubkey().equals(header.getPubkey())) {
            return;
        }

        // Try immediate delivery to all currently connected reciprocal sockets.
        boolean delivered = false;
        for (TurnClientConnection recipientConnection : clients.values()) {
            if (recipientConnection == connection) {
                continue;
            }
            for (TurnVirtualSocket recipientSocket : recipientConnection.getSockets().values()) {
                if (!senderSocket.isReciprocal(recipientSocket)) {
                    continue;
                }
                Session session = recipientConnection.getWsSession();
                if (session != null && session.isOpen()) {
                    // Rewrite envelope VSOCKET_ID to recipient's accepted socket id.
                    ByteBuffer forwardedFrame = NostrTURNCodec.withVsocketId(fullFrame, recipientSocket.getVsocketId());
                    session.sendBinary(forwardedFrame, Callback.NOOP);
                    delivered = true;
                }
            }
        }

        if (delivered) {
            return;
        }
        // No reciprocal target online: queue frame for delayed drain.
        queueFrameOrDisconnectSender(connection, senderSocket, fullFrame);
    }

    void handleDisconnect(TurnClientConnection connection, SignedNostrEvent header, long envelopeVsocketId) {
        pruneExpiredQueuedFrames();
        // Disconnect content is optional-ish for routing but parsed for reason/error propagation.
        JsonObject content = TurnJson.parseObject(header.getContent());
        String reason = TurnJson.readString(content, "reason");
        boolean error = content != null && content.has("error") && content.get("error").getAsBoolean();

        // Unknown/zero sockets are ignored for idempotence.
        if (envelopeVsocketId == 0L) {
            return;
        }

        TurnVirtualSocket senderSocket = connection.getSockets().remove(Long.valueOf(envelopeVsocketId));
        if (senderSocket == null) {
            return;
        }

        // Remove any queued outbound frames still associated with this sender socket.
        removeQueuedFramesForSenderSocket(connection.getWsSession(), senderSocket.getVsocketId());
        // Relay disconnect to reciprocal side(s).
        relayDisconnectToReciprocal(senderSocket, reason, error);
    }

    private void relayDisconnectToReciprocal(TurnVirtualSocket senderSocket, String reason, boolean error) {
        // Fan-out to all reciprocal sockets currently active.
        for (TurnClientConnection recipientConnection : clients.values()) {
            for (TurnVirtualSocket recipientSocket : recipientConnection.getSockets().values()) {
                if (!senderSocket.isReciprocal(recipientSocket)) {
                    continue;
                }
                sendFrame(
                    recipientConnection.getWsSession(),
                    eventFactory.createDisconnect(recipientSocket, reason, error),
                    null,
                    recipientSocket.getVsocketId()
                );
            }
        }
    }

    private boolean verifyRoomProof(SignedNostrEvent header, NostrPublicKey roomPubkey, String challenge) {
        // roomproof tag shape: ["roomproof", id, sig]
        org.ngengine.nostr4j.event.NostrEvent.TagValue proofTag = header.getFirstTag("roomproof");
        if (proofTag == null || proofTag.size() < 2) {
            return false;
        }

        String proofId = TurnJson.safeString(proofTag.get(0));
        String proofSig = TurnJson.safeString(proofTag.get(1));

        return NostrRoomProof.verify(
            roomPubkey,
            header.getCreatedAt(),
            header.getKind(),
            header.getPubkey(),
            challenge,
            proofId,
            proofSig
        );
    }

    void sendChallenge(TurnClientConnection connection) {
        // Envelope VSOCKET_ID must be 0 for challenge.
        sendFrame(connection.getWsSession(), eventFactory.createChallenge(connection), null, 0L);
    }

    private void sendFrame(Session session, SignedNostrEvent header, java.util.List<byte[]> payloads, long vsocketId) {
        // Silently drop sends to closed session.
        if (session == null || !session.isOpen() || header == null) {
            return;
        }

        // Encode full TURN frame and write.
        java.util.List<byte[]> safePayloads = payloads == null ? Collections.<byte[]>emptyList() : payloads;
        ByteBuffer frame = NostrTURNCodec.encodeFrame(NostrTURNCodec.encodeHeader(header), vsocketId, safePayloads);
        session.sendBinary(frame.asReadOnlyBuffer(), Callback.NOOP);
    }

    void closeConnection(Session session, String reason) {
        if (session == null) {
            return;
        }
        logger.fine("TURN connection closing: reason=" + reason);

        // Evict client and relay synthetic peer-unreachable disconnect for its sockets.
        TurnClientConnection removed = clients.remove(session);
        if (removed != null) {
            for (TurnVirtualSocket virtualSocket : removed.getSockets().values()) {
                relayDisconnectToReciprocal(virtualSocket, "peer unreachable", true);
            }
            removeQueuedFramesForSession(session);
            removed.getSockets().clear();
        }

        // Close underlying transport.
        if (session.isOpen()) {
            session.close(StatusCode.NORMAL, reason, Callback.NOOP);
        }
    }

    static ByteBuffer deepCopy(ByteBuffer payload) {
        // Defensive copy because incoming buffers can be reused by transport layer.
        ByteBuffer copy = ByteBuffer.allocate(payload.remaining());
        copy.put(payload.slice());
        copy.flip();
        return copy;
    }

    private void queueFrameOrDisconnectSender(
        TurnClientConnection senderConnection,
        TurnVirtualSocket senderSocket,
        ByteBuffer fullFrame
    ) {
        // Snapshot frame bytes for deferred delivery.
        byte[] frameBytes = new byte[fullFrame.remaining()];
        fullFrame.asReadOnlyBuffer().get(frameBytes);
        boolean overflow = false;
        synchronized (queuedFramesLock) {
            if (queuedFrames.size() >= maxQueuedFrames) {
                // Queue hard limit reached: sender gets unreachable disconnect.
                overflow = true;
            } else {
                // Keep sender websocket + socket id so timeouts can target correct sender.
                queuedFrames.addLast(
                    new QueuedFrame(senderSocket, senderConnection.getWsSession(), frameBytes, System.currentTimeMillis())
                );
            }
        }
        if (overflow) {
            disconnectSenderSocket(senderConnection.getWsSession(), senderSocket.getVsocketId(), PEER_UNREACHABLE_REASON, true);
        }
    }

    private void drainQueuedFramesForRecipient(TurnClientConnection recipientConnection, TurnVirtualSocket recipientSocket) {
        // Pull all queued frames whose sender is reciprocal with this recipient socket.
        ArrayList<byte[]> toForward = new ArrayList<byte[]>();
        synchronized (queuedFramesLock) {
            Iterator<QueuedFrame> iterator = queuedFrames.iterator();
            while (iterator.hasNext()) {
                QueuedFrame queued = iterator.next();
                if (!queued.senderSocket.isReciprocal(recipientSocket)) {
                    continue;
                }
                toForward.add(queued.frameBytes);
                iterator.remove();
            }
        }

        Session recipientSession = recipientConnection.getWsSession();
        if (recipientSession == null || !recipientSession.isOpen()) {
            return;
        }
        // Forward drained frames with recipient socket id rewrite.
        for (byte[] frameBytes : toForward) {
            ByteBuffer queuedFrame = ByteBuffer.wrap(frameBytes).asReadOnlyBuffer();
            ByteBuffer rewritten = NostrTURNCodec.withVsocketId(queuedFrame, recipientSocket.getVsocketId());
            recipientSession.sendBinary(rewritten, Callback.NOOP);
        }
    }

    private void removeQueuedFramesForSession(Session session) {
        // Drop all pending queued frames originating from a closed websocket.
        if (session == null) {
            return;
        }
        synchronized (queuedFramesLock) {
            Iterator<QueuedFrame> iterator = queuedFrames.iterator();
            while (iterator.hasNext()) {
                QueuedFrame queued = iterator.next();
                if (queued.senderSession == session) {
                    iterator.remove();
                }
            }
        }
    }

    private void removeQueuedFramesForSenderSocket(Session senderSession, long senderVsocketId) {
        // Remove only frames belonging to one logical sender socket.
        synchronized (queuedFramesLock) {
            Iterator<QueuedFrame> iterator = queuedFrames.iterator();
            while (iterator.hasNext()) {
                QueuedFrame queued = iterator.next();
                if (queued.senderSession == senderSession && queued.senderSocket.getVsocketId() == senderVsocketId) {
                    iterator.remove();
                }
            }
        }
    }

    private void pruneExpiredQueuedFrames() {
        // TTL-based queue cleanup; stale frames trigger peer-unreachable to sender.
        long now = System.currentTimeMillis();
        ArrayList<ExpiredSender> expiredSenders = new ArrayList<ExpiredSender>();
        synchronized (queuedFramesLock) {
            Iterator<QueuedFrame> iterator = queuedFrames.iterator();
            while (iterator.hasNext()) {
                QueuedFrame queued = iterator.next();
                if (now - queued.queuedAtMillis <= queueMaxAgeMillis) {
                    continue;
                }
                // Collect sender socket to notify after lock release.
                expiredSenders.add(new ExpiredSender(queued.senderSession, queued.senderSocket.getVsocketId()));
                iterator.remove();
            }
        }
        // Notify each expired sender socket exactly once per expired queued entry set.
        for (ExpiredSender expired : expiredSenders) {
            disconnectSenderSocket(expired.session, expired.vsocketId, PEER_UNREACHABLE_REASON, true);
        }
    }

    private void disconnectSenderSocket(Session senderSession, long senderVsocketId, String reason, boolean error) {
        // Best-effort targeted disconnect for one sender socket.
        if (senderSession == null || senderVsocketId == 0L) {
            return;
        }
        TurnClientConnection senderClient = clients.get(senderSession);
        if (senderClient == null) {
            return;
        }
        TurnVirtualSocket senderSocket = senderClient.getSockets().remove(Long.valueOf(senderVsocketId));
        if (senderSocket == null) {
            return;
        }
        // Clear residual queued payloads from this sender socket and emit disconnect.
        removeQueuedFramesForSenderSocket(senderSession, senderVsocketId);
        sendFrame(senderSession, eventFactory.createDisconnect(senderSocket, reason, error), null, senderVsocketId);
    }
}
