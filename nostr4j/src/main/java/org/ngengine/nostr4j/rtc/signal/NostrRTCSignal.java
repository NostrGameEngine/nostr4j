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
package org.ngengine.nostr4j.rtc.signal;

import java.io.Serializable;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

import org.ngengine.nostr4j.event.SignedNostrEvent;
import org.ngengine.nostr4j.event.UnsignedNostrEvent;
import org.ngengine.nostr4j.keypair.NostrKeyPair;
import org.ngengine.nostr4j.keypair.NostrPublicKey;
import org.ngengine.nostr4j.signer.NostrSigner;
import org.ngengine.nostr4j.utils.NostrRoomProof;
import org.ngengine.platform.AsyncTask;
import org.ngengine.platform.NGEPlatform;
import org.ngengine.platform.NGEUtils;

import jakarta.annotation.Nullable;

/**
 * A signal for the RTC handshake.
 */

public abstract class NostrRTCSignal implements Serializable {
    private final NostrKeyPair roomKeyPair;
    private final NostrRTCPeer peer;
    private final String type;
    private final NostrSigner localSigner;

    protected NostrRTCSignal(NostrSigner localSigner, String type, NostrKeyPair roomKeyPair, NostrRTCPeer peer){
        Objects.requireNonNull(localSigner, "Local signer cannot be null");
        Objects.requireNonNull(type, "Type cannot be null");
        Objects.requireNonNull(roomKeyPair, "Room key pair cannot be null");
        Objects.requireNonNull(peer, "Peer cannot be null");
        this.localSigner = localSigner;
        this.type = type;
        this.roomKeyPair = roomKeyPair;
        this.peer = peer;
    }


    protected NostrRTCSignal(NostrSigner localSigner, String type, NostrKeyPair roomKeyPair, SignedNostrEvent event){
        Objects.requireNonNull(localSigner, "Local signer cannot be null");
        Objects.requireNonNull(type, "Type cannot be null");
        Objects.requireNonNull(roomKeyPair, "Room key pair cannot be null");
        Objects.requireNonNull(event, "Event cannot be null");
        try{
            if(!event.verify())throw new IllegalArgumentException("Event signature is invalid");
        }catch(Exception e){
            throw new IllegalArgumentException("Event signature verification failed", e);
        }
        if(event.getKind() != 25050) throw new IllegalArgumentException("Event kind must be 25050");
        String eventType = NGEUtils.safeString(event.getFirstTagFirstValue("t"));
        if (!type.equals(eventType)) throw new IllegalArgumentException("Event type must be " + type);

        String sessionId = NGEUtils.safeString(event.getFirstTagFirstValue("d"));
        String protocolId = NGEUtils.safeString(event.getFirstTagFirstValue("i"));
        String applicationId = NGEUtils.safeString(event.getFirstTagFirstValue("y"));
        String roomHex = NGEUtils.safeString(event.getFirstTagFirstValue("P"));
        if (roomHex.isEmpty()) {
            throw new IllegalArgumentException("Missing room pubkey tag P");
        }
        NostrPublicKey roomPubkey = NostrPublicKey.fromHex(roomHex);
        if(!roomPubkey.equals(roomKeyPair.getPublicKey())) throw new IllegalArgumentException("Event room pubkey does not match the provided room");
        if(event.isExpired()) throw new IllegalArgumentException("Event is expired");
        if (requiresRoomProof(type) && !verifyRoomProof(roomPubkey, event)) {
            throw new IllegalArgumentException("Invalid roomproof");
        }
        this.peer = new NostrRTCPeer(
            event.getPubkey(),
            applicationId,
            protocolId,
            sessionId,
            roomPubkey,
            null
        );
        this.roomKeyPair = roomKeyPair;
        this.type = type;
        this.localSigner = localSigner;

    } 

    public NostrPublicKey getRoomPubkey(){
        return roomKeyPair.getPublicKey();
    }

    public NostrRTCPeer getPeer(){
        return peer;
    }

    protected final AsyncTask<String> encrypt(
        String content, 
        NostrPublicKey recipient
    ) {
        return localSigner.encrypt(content, recipient);
    }

    protected final AsyncTask<String> decrypt(String content, NostrPublicKey sender) {
        return localSigner.decrypt(content, sender);
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "{peer=" + getPeer() + ", roomPubkey=" + getRoomPubkey() + "}";
    }

    protected abstract AsyncTask<UnsignedNostrEvent> computeEvent(UnsignedNostrEvent event)  ;


    public final AsyncTask<SignedNostrEvent> toEvent(@Nullable NostrPublicKey toUser)  {
        
        NostrRTCPeer peer = getPeer();     
        UnsignedNostrEvent connectEvent = new UnsignedNostrEvent();
        connectEvent.withKind(25050);
        connectEvent.createdAt(Instant.now());
        connectEvent.withTag("t", type);
        connectEvent.withTag("P", getRoomPubkey().asHex());
        connectEvent.withTag("d", peer.getSessionId());
        connectEvent.withTag("i", peer.getProtocolId());
        connectEvent.withTag("y", peer.getApplicationId());        
        if (toUser != null) {
             connectEvent.withTag("p", toUser.asHex());
         }
         

        return computeEvent(connectEvent).compose(ev -> {
            // encrypt if event  to user
            if (toUser != null && !NGEUtils.safeString(ev.getContent()).isEmpty()) {
                return encrypt(ev.getContent(), toUser).then(enc -> {
                    ev.withContent(enc);
                    return ev;
                }).compose(this::signForRoom);
            }
            return signForRoom(ev);
        });
    }

    protected AsyncTask<SignedNostrEvent> signForRoom(UnsignedNostrEvent event) {
        // we don't sign presence events for plausble deniability
        if (!requiresRoomProof(type)) {
            return localSigner.sign(event);
        }
        NostrPublicKey receiver = null;
        if (event.getFirstTag("p") != null && event.getFirstTag("p").get(0) != null) {
            receiver = NostrPublicKey.fromHex(event.getFirstTag("p").get(0));
        }
        if (receiver == null) {
            return NGEPlatform.get().wrapPromise((res, rej) -> rej.accept(new IllegalStateException("Missing receiver pubkey for roomproof")));
        }
        String content = NGEUtils.safeString(event.getContent());
        String challenge = NGEUtils.getPlatform().toJSON(List.of(receiver.asHex(), content));
        return localSigner.getPublicKey().compose(senderPubkey -> {
            return NostrRoomProof.sign(roomKeyPair, event.getCreatedAt(), event.getKind(), senderPubkey, challenge).then(sig -> {
                String id = NostrRoomProof.computeId(roomKeyPair.getPublicKey(), event.getCreatedAt(), event.getKind(), senderPubkey, challenge);
                event.withTag("roomproof", id, sig);
                return event;
            });
        }).compose(localSigner::sign);
    }

   
    protected boolean verifyRoomProof(  NostrPublicKey roomPubkey, SignedNostrEvent event) {
        if (!requiresRoomProof(type)) {
            return true;
        }
        if (event.getFirstTag("p") == null || event.getFirstTag("p").get(0) == null) {
            return false;
        }
        if (event.getFirstTag("roomproof") == null || event.getFirstTag("roomproof").size() < 2) {
            return false;
        }
        String receiver = event.getFirstTag("p").get(0);
        String content = NGEUtils.safeString(event.getContent());
        String challenge = NGEUtils.getPlatform().toJSON(List.of(receiver, content));
        String proofId = event.getFirstTag("roomproof").get(0);
        String proofSig = event.getFirstTag("roomproof").get(1);
        return NostrRoomProof.verify(
            roomPubkey,
            event.getCreatedAt(),
            event.getKind(),
            event.getPubkey(),
            challenge,
            proofId,
            proofSig
        );
    }

    protected boolean requiresRoomProof(String type) {
        return "offer".equals(type) || "answer".equals(type) || "route".equals(type);
    }


    protected abstract boolean requireRoomSignature();
    

    public void await(){

    }

}
