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

package org.ngengine.nostr4j.rtc.turn.event;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.ngengine.nostr4j.event.SignedNostrEvent;
import org.ngengine.nostr4j.event.UnsignedNostrEvent;
import org.ngengine.nostr4j.keypair.NostrKeyPair;
import org.ngengine.nostr4j.keypair.NostrPublicKey;
import org.ngengine.nostr4j.nip44.Nip44;
import org.ngengine.nostr4j.rtc.signal.NostrRTCLocalPeer;
import org.ngengine.nostr4j.rtc.signal.NostrRTCPeer;
import org.ngengine.platform.AsyncTask;
import org.ngengine.platform.NGEUtils;

public final class NostrTURNDataEvent extends NostrTURNEvent {

    private final long vsocketId;
    private final NostrRTCPeer remotePeer;
    private AsyncTask<SignedNostrEvent> event;
    private final AsyncTask<byte[]> encryptionKey;
    private final AtomicInteger messageCounter = new AtomicInteger(1);

    // created locally to send
    public static NostrTURNDataEvent createOutgoing(
        NostrRTCLocalPeer localPeer,
        NostrRTCPeer remotePeer,
        NostrKeyPair roomKeyPair,
        String channelLabel,
        long vsocketId,
        byte[] encryptionKey
    ) {
        return new NostrTURNDataEvent(localPeer, remotePeer, roomKeyPair, channelLabel, vsocketId, encryptionKey);
    }

    private NostrTURNDataEvent(
        NostrRTCLocalPeer localPeer,
        NostrRTCPeer remotePeer,
        NostrKeyPair roomKeyPair,
        String channelLabel,
        long vsocketId,
        byte[] encryptionKey
    ) {
        super("data", localPeer, remotePeer, roomKeyPair, channelLabel);
        this.remotePeer = remotePeer;
        this.vsocketId = vsocketId;
        if (this.vsocketId == 0L) {
            throw new IllegalArgumentException("Invalid TURN data event: vsocketId must be != 0");
        }
        if (encryptionKey.length != 32) {
            throw new IllegalArgumentException("Invalid encryption key length: " + encryptionKey.length);
        }
        this.encryptionKey = AsyncTask.completed(encryptionKey);
    }

    public static NostrTURNDataEvent parseIncoming(
        SignedNostrEvent event,
        NostrRTCLocalPeer localPeer,
        NostrRTCPeer remotePeer,
        NostrKeyPair roomKeyPair,
        String channelLabel,
        long envelopeVsocketId
    ) {
        return new NostrTURNDataEvent(event, localPeer, remotePeer, roomKeyPair, channelLabel, envelopeVsocketId);
    }

    // Received from peer
    private NostrTURNDataEvent(
        SignedNostrEvent event,
        NostrRTCLocalPeer localPeer,
        NostrRTCPeer remotePeer,
        NostrKeyPair roomKeyPair,
        String channelLabel,
        long envelopeVsocketId
    ) {
        super("data", event, localPeer, null, null, null);
        this.remotePeer = remotePeer;
        this.vsocketId = envelopeVsocketId;
        if (remotePeer == null) {
            throw new IllegalArgumentException("Remote peer is required for TURN data event");
        }
        NostrPublicKey senderPubkey = event.getPubkey();
        if (!remotePeer.getPubkey().equals(senderPubkey)) {
            throw new IllegalArgumentException("TURN data event sender mismatch");
        }

        this.event = AsyncTask.completed(event);
        String encryptionProtocol = event.getFirstTagFirstValue("enc");
        if (!"nip44-v2".equals(encryptionProtocol)) {
            throw new IllegalArgumentException("Unsupported encryption protocol: " + encryptionProtocol);
        }
        String encryptedKey = event.getFirstTagSecondValue("enc");
        if (encryptedKey == null || encryptedKey.isEmpty()) {
            throw new IllegalArgumentException("Missing encrypted key in enc tag");
        }

        this.encryptionKey =
            localPeer
                .getSigner()
                .decrypt(encryptedKey, remotePeer.getPubkey())
                .then(decryptedHex -> {
                    byte[] decrypted = NGEUtils.hexToByteArray(decryptedHex);
                    if (decrypted.length != 32) {
                        throw new IllegalArgumentException("Invalid encryption key length: " + decrypted.length);
                    }
                    return decrypted;
                });
    }

    public long getVsocketId() {
        return vsocketId;
    }

    @Override
    protected AsyncTask<UnsignedNostrEvent> computeEvent(UnsignedNostrEvent event) {
        return encryptionKey
            .compose(k -> {
                return this.getLocalPeer().getSigner().encrypt(NGEUtils.bytesToHex(k), remotePeer.getPubkey());
            })
            .then(encKey -> {
                event.withContent("");
                event.withTag("enc", "nip44-v2", encKey);
                return event;
            });
    }

    @Override
    public AsyncTask<SignedNostrEvent> toEvent() {
        // This is a special event, we can reuse the same signed event for multiple payloads, so we cache it after the first generation
        if (event == null) {
            event = super.toEvent();
        }

        return event;
    }

    @Override
    public AsyncTask<ByteBuffer> encodeToFrame(Collection<ByteBuffer> payloads) {
        return encodeToFrame(payloads, nextMessageId());
    }

    public AsyncTask<ByteBuffer> encodeToFrame(Collection<ByteBuffer> payloads, int messageId) {
        if (messageId == 0) {
            throw new IllegalArgumentException("TURN data messageId must be != 0");
        }
        List<AsyncTask<byte[]>> encryptedTasks = new ArrayList<>();
        for (ByteBuffer payload : payloads) {
            byte[] payloadBytes = new byte[payload.remaining()];
            payload.duplicate().get(payloadBytes);
            encryptedTasks.add(
                encryptionKey.compose(encKey -> {
                    return Nip44.encryptBinary(payloadBytes, encKey);
                })
            );
        }
        return AsyncTask
            .all(encryptedTasks)
            .compose(encryptedPayloads -> {
                return toEncodedHeader()
                    .then(header -> {
                        return NostrTURNCodec.encodeFrame(header, getEnvelopeVsocketId(), messageId, encryptedPayloads);
                    });
            });
    }

    private int nextMessageId() {
        int id = messageCounter.getAndIncrement();
        if (id == 0) {
            return messageCounter.getAndIncrement();
        }
        return id;
    }

    @Override
    protected boolean shouldIncludeRoutingTags() {
        return false;
    }

    @Override
    protected long getEnvelopeVsocketId() {
        return vsocketId;
    }

    public AsyncTask<Collection<ByteBuffer>> decodeFramePayloads(ByteBuffer frame) {
        return encryptionKey.compose(encKey -> {
            return toEncodedHeader()
                .then(encodedHeader -> {
                    if (!NostrTURNCodec.compareHeaders(frame, encodedHeader)) {
                        throw new IllegalArgumentException("Header mismatch when decoding TURN data frame");
                    }
                    return frame;
                })
                .then(frame0 -> {
                    List<byte[]> encryptedPayloads = new ArrayList<>();
                    NostrTURNCodec.decodePayloads(frame0, encryptedPayloads);
                    return encryptedPayloads;
                })
                .compose(encryptedPayloads -> {
                    List<AsyncTask<byte[]>> decryptedTasks = new ArrayList<>();
                    for (int i = 0; i < encryptedPayloads.size(); i++) {
                        byte[] payload = encryptedPayloads.get(i);

                        decryptedTasks.add(Nip44.decryptBinary(payload, encKey));
                    }
                    return AsyncTask.all(decryptedTasks);
                })
                .then(decryptedPayloads -> {
                    List<ByteBuffer> decryptedBuffers = new ArrayList<>();
                    for (byte[] decrypted : decryptedPayloads) {
                        decryptedBuffers.add(ByteBuffer.wrap(decrypted).asReadOnlyBuffer());
                    }
                    return decryptedBuffers;
                });
        });
    }
}
