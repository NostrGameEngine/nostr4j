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
import java.nio.channels.ClosedChannelException;
import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.websocket.server.config.JettyWebSocketServletContainerInitializer;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.websocket.api.Callback;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.StatusCode;
import org.eclipse.jetty.websocket.api.exceptions.WebSocketTimeoutException;
import org.ngengine.nostr4j.event.SignedNostrEvent;
import org.ngengine.nostr4j.keypair.NostrPublicKey;
import org.ngengine.nostr4j.rtc.turn.NostrTURNCodec;
import org.ngengine.nostr4j.signer.NostrKeyPairSigner;
import org.ngengine.nostr4j.utils.NostrRoomProof;
import org.ngengine.platform.AsyncExecutor;
import org.ngengine.platform.AsyncTask;
import org.ngengine.platform.NGEUtils;

/**
 * Minimal NIP-DC TURN reference server.
 *
 * <p>High-level flow:
 * 1) WebSocket opens -> server sends `challenge` with difficulty+token.
 * 2) Client sends signed `connect` with PoW + roomproof + routing tags.
 * 3) Server validates and creates websocket-scoped virtual socket mapping.
 * 4) Server sends `ack` for accepted virtual socket.
 * 5) `data` frames are always enqueued per sender socket and drained in order.
 * 6) Drain attempts deliver to reciprocal sockets; failure stops the queue.
 * 7) Queue watchdog restarts delivery attempts periodically.
 * 8) Queue overflow results in sender `disconnect` with peer-unreachable error.
 * 9) `disconnect` tears down the virtual socket and is relayed to reciprocal side.
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
    private static final String PEER_UNREACHABLE_REASON = "peer unreachable";
    private static final long SOCKET_LOOP_MS = 250L;

    // Active websocket clients keyed by session object.
    final Map<Session, TurnClientConnection> clients = new ConcurrentHashMap<Session, TurnClientConnection>();
    private final Server jettyServer;
    private final TurnEventFactory eventFactory;
    final int difficulty;
    final int challengeTtlSeconds;
    final String host;
    private final int maxQueuedFrames;
    private final AsyncExecutor loopExecutor;
    private volatile boolean running = false;

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
            try {
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
            } catch (Exception ex) {
                TurnServer.logger.log(Level.WARNING, "Error during TURN handshake", ex);
                this.turnServer.closeConnection(session, "handshake-error");
            }
        }

        @Override
        public void onWebSocketBinary(ByteBuffer payload, Callback callback) {
            try {
                // Copy incoming payload to decouple from Jetty buffer lifecycle.
                ByteBuffer incomingFrame = ByteBuffer.allocate(payload.remaining());
                incomingFrame.put(payload.slice());
                incomingFrame.flip();
                callback.succeed();

                TurnClientConnection connection = this.turnServer.clients.get(this.wsSession);
                if (connection == null) {
                    // Session already closed/evicted.
                    return;
                }

                // Envelope-level decode.
                long envelopeVsocketId = NostrTURNCodec.extractVsocketId(incomingFrame.asReadOnlyBuffer());
                int envelopeMessageId = NostrTURNCodec.extractMessageId(incomingFrame.asReadOnlyBuffer());

                // Header decode + signature verification.
                SignedNostrEvent header = NostrTURNCodec.decodeHeader(incomingFrame.asReadOnlyBuffer());
                this.turnServer.validateHeaderSignature(header);

                // Dispatch by control type.
                String type = TurnJson.safeString(header.getFirstTagFirstValue("t"));
                if ("connect".equals(type)) {
                    if (envelopeMessageId != 0) {
                        this.turnServer.closeConnection(this.wsSession, "invalid-message-id");
                        return;
                    }
                    this.turnServer.handleConnect(connection, header, envelopeVsocketId);
                    return;
                }
                if ("data".equals(type)) {
                    if (envelopeMessageId == 0) {
                        return;
                    }
                    this.turnServer.handleData(connection, header, incomingFrame, envelopeVsocketId);
                    return;
                }
                if ("delivery_ack".equals(type)) {
                    if (envelopeMessageId == 0) {
                        return;
                    }
                    this.turnServer.handleData(connection, header, incomingFrame, envelopeVsocketId);
                    return;
                }
                if ("disconnect".equals(type)) {
                    if (envelopeMessageId != 0) {
                        this.turnServer.closeConnection(this.wsSession, "invalid-message-id");
                        return;
                    }
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
            Level level = isExpectedSocketClosure(cause) ? Level.FINE : Level.WARNING;
            String message = level == Level.FINE ? "Reference TURN socket closed" : "Reference TURN socket error";
            TurnServer.logger.log(level, message, cause);
            this.turnServer.closeConnection(this.wsSession, "socket-error");
        }
    }

    private static boolean isExpectedSocketClosure(Throwable cause) {
        Throwable current = cause;
        while (current != null) {
            if (current instanceof ClosedChannelException) {
                return true;
            }
            if (current instanceof WebSocketTimeoutException) {
                return true;
            }
            String message = current.getMessage();
            if (message != null && message.contains("Connection Idle Timeout")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    public TurnServer(int port, NostrKeyPairSigner serverSigner, int difficulty, int challengeTtlSeconds) {
        this("127.0.0.1", port, serverSigner, difficulty, challengeTtlSeconds, DEFAULT_MAX_QUEUED_FRAMES);
    }

    public TurnServer(String host, int port, NostrKeyPairSigner serverSigner, int difficulty, int challengeTtlSeconds) {
        this(host, port, serverSigner, difficulty, challengeTtlSeconds, DEFAULT_MAX_QUEUED_FRAMES);
    }

    public TurnServer(int port, NostrKeyPairSigner serverSigner, int difficulty, int challengeTtlSeconds, int maxQueuedFrames) {
        this("127.0.0.1", port, serverSigner, difficulty, challengeTtlSeconds, maxQueuedFrames);
    }

    public TurnServer(
        String host,
        int port,
        NostrKeyPairSigner serverSigner,
        int difficulty,
        int challengeTtlSeconds,
        int maxQueuedFrames
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
        // Initialize signer/event factory and queue policy configuration.
        this.eventFactory = new TurnEventFactory(Objects.requireNonNull(serverSigner, "serverSigner cannot be null"));
        this.host = host.trim();
        this.difficulty = difficulty;
        this.challengeTtlSeconds = challengeTtlSeconds;
        this.maxQueuedFrames = maxQueuedFrames;
        this.loopExecutor = NGEUtils.getPlatform().newAsyncExecutor(TurnServer.class.getSimpleName() + "-loop");

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
        this.running = true;
        loop();
    }

    public void join() throws InterruptedException {
        this.jettyServer.join();
    }

    public void stop() throws Exception {
        this.running = false;
        for (TurnClientConnection connection : clients.values()) {
            for (TurnVirtualSocket socket : connection.getSockets().values()) {
                socket.close();
            }
        }
        this.jettyServer.stop();
        this.loopExecutor.close();
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
        org.ngengine.nostr4j.event.NostrEvent.TagValue pTag = header.getFirstTag("p");
        String targetSessionId = "";
        if (pTag != null && pTag.size() > 2) {
            targetSessionId = TurnJson.safeString(pTag.get(2));
        }
        if (
            roomHex.isEmpty() ||
            senderSessionId.isEmpty() ||
            protocolId.isEmpty() ||
            applicationId.isEmpty() ||
            targetHex.isEmpty() ||
            channelLabel.isEmpty() ||
            targetSessionId.isEmpty()
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
            targetSessionId,
            protocolId,
            applicationId,
            channelLabel,
            this::processQueuedFrame,
            this::hasReachableRecipient
        );

        // Store accepted socket then ack it.
        connection.getSockets().put(Long.valueOf(vsocketId), senderSocket);
        sendFrame(
            connection.getWsSession(),
            eventFactory.createAck(senderSocket),
            null,
            vsocketId,
            0,
            new Callback() {
                @Override
                public void succeed() {
                    senderSocket.markAckSent();
                }

                @Override
                public void fail(Throwable x) {
                    logger.log(Level.FINE, "Failed to send TURN ack", x);
                    closeConnection(connection.getWsSession(), "ack-send-failed");
                }
            }
        );
    }

    /**
     * Data step: server is blind to payload, only routes by header identity + reciprocal socket.
     *
     * Frames are always enqueued first to preserve strict per-socket ordering.
     */
    void handleData(TurnClientConnection connection, SignedNostrEvent header, ByteBuffer fullFrame, long envelopeVsocketId) {
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
        // Always enqueue to preserve ordering even when recipient is currently online.
        if (!senderSocket.out(fullFrame, maxQueuedFrames)) {
            disconnectSenderSocket(connection.getWsSession(), senderSocket.getVsocketId(), PEER_UNREACHABLE_REASON, true);
        }
    }

    void handleDisconnect(TurnClientConnection connection, SignedNostrEvent header, long envelopeVsocketId) {
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
        senderSocket.close();
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
        sendFrame(connection.getWsSession(), eventFactory.createChallenge(connection), null, 0L, 0);
    }

    private void sendFrame(Session session, SignedNostrEvent header, java.util.List<byte[]> payloads, long vsocketId) {
        sendFrame(session, header, payloads, vsocketId, 0, Callback.NOOP);
    }

    private void sendFrame(
        Session session,
        SignedNostrEvent header,
        java.util.List<byte[]> payloads,
        long vsocketId,
        int messageId
    ) {
        sendFrame(session, header, payloads, vsocketId, messageId, Callback.NOOP);
    }

    private void sendFrame(
        Session session,
        SignedNostrEvent header,
        java.util.List<byte[]> payloads,
        long vsocketId,
        int messageId,
        Callback callback
    ) {
        // Silently drop sends to closed session.
        if (session == null || !session.isOpen() || header == null) {
            return;
        }

        // Encode full TURN frame and write.
        java.util.List<byte[]> safePayloads = payloads == null ? Collections.<byte[]>emptyList() : payloads;
        ByteBuffer frame = NostrTURNCodec.encodeFrame(NostrTURNCodec.encodeHeader(header), vsocketId, messageId, safePayloads);
        session.sendBinary(frame.asReadOnlyBuffer(), callback == null ? Callback.NOOP : callback);
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
                virtualSocket.close();
            }
            removed.getSockets().clear();
        }

        // Close underlying transport.
        if (session.isOpen()) {
            session.close(StatusCode.NORMAL, reason, Callback.NOOP);
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
        senderSocket.close();
        sendFrame(senderSession, eventFactory.createDisconnect(senderSocket, reason, error), null, senderVsocketId);
    }

    private AsyncTask<Boolean> processQueuedFrame(
        TurnVirtualSocket senderSocket,
        TurnVirtualSocket.QueuedOutgoingFrame queued
    ) {
        if (senderSocket == null || queued == null) {
            return AsyncTask.completed(Boolean.TRUE);
        }
        boolean delivered = false;
        for (TurnClientConnection recipientConnection : clients.values()) {
            if (recipientConnection == null) {
                continue;
            }
            for (TurnVirtualSocket recipientSocket : recipientConnection.getSockets().values()) {
                if (!senderSocket.isReciprocal(recipientSocket)) {
                    continue;
                }
                if (!recipientSocket.isAckSent()) {
                    continue;
                }
                Session recipientSession = recipientConnection.getWsSession();
                if (recipientSession == null || !recipientSession.isOpen()) {
                    continue;
                }
                ByteBuffer rewritten = senderSocket.in(queued, recipientSocket.getVsocketId());
                if (rewritten == null) {
                    continue;
                }
                recipientSession.sendBinary(rewritten, Callback.NOOP);
                delivered = true;
            }
        }
        return AsyncTask.completed(Boolean.valueOf(delivered));
    }

    private boolean hasReachableRecipient(TurnVirtualSocket senderSocket) {
        if (senderSocket == null) {
            return false;
        }
        for (TurnClientConnection recipientConnection : clients.values()) {
            if (recipientConnection == null) {
                continue;
            }
            Session recipientSession = recipientConnection.getWsSession();
            if (recipientSession == null || !recipientSession.isOpen()) {
                continue;
            }
            for (TurnVirtualSocket recipientSocket : recipientConnection.getSockets().values()) {
                if (senderSocket.isReciprocal(recipientSocket) && recipientSocket.isAckSent()) {
                    return true;
                }
            }
        }
        return false;
    }

    private void loop() {
        if (!running) {
            return;
        }
        for (TurnClientConnection connection : clients.values()) {
            if (connection == null) {
                continue;
            }
            for (TurnVirtualSocket socket : connection.getSockets().values()) {
                if (socket == null) {
                    continue;
                }
                socket.loop();
            }
        }
        loopExecutor.runLater(
            () -> {
                loop();
                return null;
            },
            SOCKET_LOOP_MS,
            TimeUnit.MILLISECONDS
        );
    }
}
