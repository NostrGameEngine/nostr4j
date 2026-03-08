/**
 * BSD 3-Clause License
 * Copyright (c) 2025, Riccardo Balbo
 */
package org.ngengine.nostr4j.rtc.turn.event;

import jakarta.annotation.Nullable;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.Collection;
import java.util.Objects;
import org.ngengine.nostr4j.event.SignedNostrEvent;
import org.ngengine.nostr4j.event.UnsignedNostrEvent;
import org.ngengine.nostr4j.keypair.NostrKeyPair;
import org.ngengine.nostr4j.keypair.NostrPublicKey;
import org.ngengine.nostr4j.rtc.signal.NostrRTCLocalPeer;
import org.ngengine.nostr4j.rtc.signal.NostrRTCPeer;
import org.ngengine.nostr4j.signer.NostrSigner;
import org.ngengine.platform.AsyncTask;
import org.ngengine.platform.NGEUtils;

/**
 * Base abstraction for TURN events.
 * The constructor that accept an event arg parses and validates incoming events
 * the other constructor is to create outgoing events.
 */
public abstract class NostrTURNEvent {

    public static final int KIND = 25051;

    private final String type;

    private final NostrRTCLocalPeer localPeer;
    private final NostrRTCPeer remotePeer;
    private final NostrKeyPair roomKeyPair;
    private final String channelLabel;
    private AsyncTask<byte[]> encodedHeader;

    /**
     * Create outgoing event
     * @param type
     * @param localPeer
     * @param remotePeer
     * @param roomKeyPair
     * @param channelLabel
     */
    protected NostrTURNEvent(
        String type,
        NostrRTCLocalPeer localPeer,
        @Nullable NostrRTCPeer remotePeer, // can be null depending on the event type
        @Nullable NostrKeyPair roomKeyPair,
        @Nullable String channelLabel
    ) {
        this.type = Objects.requireNonNull(type, "Type cannot be null");
        this.localPeer = Objects.requireNonNull(localPeer, "Local peer cannot be null");
        this.roomKeyPair = roomKeyPair; // can be null depending on the event type
        this.remotePeer = remotePeer; // can be null depending on the event type
        this.channelLabel = channelLabel == null ? null : NGEUtils.safeString(channelLabel);
    }

    /**
     * Parse incoming event
     * @param type
     * @param event
     * @param localPeer
     * @param roomKeyPair
     * @param remotePeer
     * @param channelLabel
     */
    protected NostrTURNEvent(
        String type,
        SignedNostrEvent event,
        NostrRTCLocalPeer localPeer,
        @Nullable NostrKeyPair roomKeyPair,
        @Nullable NostrRTCPeer remotePeer,
        @Nullable String channelLabel
    ) {
        this.localPeer = localPeer;
        this.type = Objects.requireNonNull(type, "Type cannot be null");

        this.roomKeyPair = roomKeyPair; // can be null depending on the event type
        this.remotePeer = remotePeer; // can be null depending on the event type
        this.channelLabel = channelLabel == null ? null : NGEUtils.safeString(channelLabel);

        String actualType = NGEUtils.safeString(event.getFirstTagFirstValue("t"));
        if (!this.type.equals(actualType)) {
            throw new IllegalArgumentException("Event type mismatch: expected " + this.type + ", got " + actualType);
        }

        // validation
        String eventRoomHex = NGEUtils.safeString(event.getFirstTagFirstValue("P"));
        if (
            roomKeyPair != null &&
            (eventRoomHex.isEmpty() || !roomKeyPair.getPublicKey().equals(NostrPublicKey.fromHex(eventRoomHex)))
        ) {
            throw new IllegalArgumentException("Event room pubkey does not match the provided room");
        }

        // local peer is the target of the remote event
        String targetPubHex = NGEUtils.safeString(event.getFirstTagFirstValue("p"));
        if (
            localPeer != null && !targetPubHex.isEmpty() && !localPeer.getPubkey().equals(NostrPublicKey.fromHex(targetPubHex))
        ) {
            throw new IllegalArgumentException("Event local pubkey does not match the provided local peer");
        }
        if (localPeer != null) {
            String targetSessionId = NGEUtils.safeString(event.getFirstTagThirdValue("p"));
            if (!targetSessionId.isEmpty() && !localPeer.getSessionId().equals(targetSessionId)) {
                throw new IllegalArgumentException("Event local session ID does not match the provided local peer session");
            }
        }

        // remote peer is the source of the remote event
        if (remotePeer != null && !remotePeer.getPubkey().equals(event.getPubkey())) {
            throw new IllegalArgumentException("Event remote pubkey does not match the provided remote peer");
        }

        if (remotePeer != null) {
            // the sessionId matches the advertised remote peer session ID
            String sessionId = remotePeer.getSessionId();
            if (sessionId != null && !sessionId.equals(event.getFirstTagFirstValue("d"))) {
                throw new IllegalArgumentException("Event session ID does not match the provided session ID");
            }

            // both peers are on the same protocol
            String protocolId = remotePeer.getProtocolId();
            if (
                protocolId != null &&
                (!protocolId.equals(event.getFirstTagFirstValue("i")) || !protocolId.equals(localPeer.getProtocolId()))
            ) {
                throw new IllegalArgumentException("Event protocol ID does not match the provided protocol ID");
            }

            // both peers are on the same application
            String applicationId = remotePeer.getApplicationId();
            if (
                applicationId != null &&
                (!applicationId.equals(event.getFirstTagFirstValue("y")) || !applicationId.equals(localPeer.getApplicationId()))
            ) {
                throw new IllegalArgumentException("Event application ID does not match the provided application ID");
            }
        }

        // the expected channel label matches the advertised channel label
        if (channelLabel != null && !channelLabel.equals(event.getFirstTagSecondValue("p"))) {
            throw new IllegalArgumentException("Event channel label does not match the provided channel label");
        }

        if (event.isExpired()) {
            throw new IllegalArgumentException("Event is expired");
        }

        if (event.getKind() != KIND) {
            throw new IllegalArgumentException("Event kind must be " + KIND);
        }

        try {
            if (!event.verify()) {
                throw new IllegalArgumentException("Event signature is invalid");
            }
        } catch (Exception e) {
            throw new IllegalArgumentException("Event signature verification failed", e);
        }
    }

    protected final NostrKeyPair getRoomKeyPair() {
        return roomKeyPair;
    }

    protected final NostrRTCLocalPeer getLocalPeer() {
        return localPeer;
    }

    protected final NostrRTCPeer getRemotePeer() {
        return remotePeer;
    }

    protected AsyncTask<UnsignedNostrEvent> toUnsignedEvent() {
        if (localPeer == null) {
            throw new IllegalStateException("Local peer not configured for outbound TURN event");
        }

        UnsignedNostrEvent event = new UnsignedNostrEvent().withKind(KIND).createdAt(Instant.now()).withTag("t", type);

        String sessionId = localPeer.getSessionId();
        String protocolId = localPeer.getProtocolId();
        String applicationId = localPeer.getApplicationId();

        if (shouldIncludeRoutingTags()) {
            if (roomKeyPair != null) event.withTag("P", roomKeyPair.getPublicKey().asHex());
            if (remotePeer != null) event.withTag("d", sessionId);
            if (remotePeer != null) event.withTag("i", protocolId);
            if (remotePeer != null) event.withTag("y", applicationId);
            if (remotePeer != null) {
                event.withTag("p", remotePeer.getPubkey().asHex(), channelLabel, remotePeer.getSessionId());
            }
        }
        return computeEvent(event);
    }

    protected boolean shouldIncludeRoutingTags() {
        return remotePeer != null;
    }

    protected long getEnvelopeVsocketId() {
        return 0L;
    }

    protected int getEnvelopeMessageId() {
        return 0;
    }

    protected AsyncTask<SignedNostrEvent> toEvent() {
        if (localPeer == null) {
            throw new IllegalStateException("Local peer not configured for outbound TURN event");
        }
        NostrSigner localSigner = localPeer.getSigner();
        if (localSigner == null) {
            throw new IllegalStateException("Local signer not configured for outbound TURN event");
        }
        return toUnsignedEvent().compose(ev -> localSigner.sign(ev));
    }

    protected final AsyncTask<SignedNostrEvent> toPowEvent(int difficulty) {
        if (localPeer == null) {
            throw new IllegalStateException("Local peer not configured for outbound TURN event");
        }
        NostrSigner localSigner = localPeer.getSigner();
        if (localSigner == null) {
            throw new IllegalStateException("Local signer not configured for outbound TURN event");
        }
        return toUnsignedEvent().compose(ev -> localSigner.powSign(ev, difficulty));
    }

    protected abstract AsyncTask<UnsignedNostrEvent> computeEvent(UnsignedNostrEvent event);

    protected AsyncTask<byte[]> toEncodedHeader() {
        if (encodedHeader == null) {
            encodedHeader =
                toEvent()
                    .then(ev -> {
                        return NostrTURNCodec.encodeHeader(ev);
                    });
        }
        return encodedHeader;
    }

    public AsyncTask<ByteBuffer> encodeToFrame(Collection<ByteBuffer> payloads) {
        return toEncodedHeader()
            .then(header -> {
                return NostrTURNCodec.encodeFrame(header, getEnvelopeVsocketId(), getEnvelopeMessageId(), null);
            });
    }
}
