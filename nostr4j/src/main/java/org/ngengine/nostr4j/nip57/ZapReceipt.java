package org.ngengine.nostr4j.nip57;

import java.util.Map;
import java.util.Objects;

import org.ngengine.nostr4j.event.NostrEvent;
import org.ngengine.nostr4j.event.SignedNostrEvent;
import org.ngengine.nostr4j.keypair.NostrPublicKey;
import org.ngengine.platform.NGEPlatform;

import jakarta.annotation.Nullable;

public class ZapReceipt extends SignedNostrEvent {
    private transient ZapRequest zapRequestEventCache = null;

    public ZapReceipt(Map<String, Object> map) {
        super(Objects.requireNonNull(map, "map"));
    }

    @Nullable
    public String getPreimage() {
        return getFirstTagFirstValue("preimage");
    }

    public String getBolt11() {
        String bolt11 = getFirstTagFirstValue("bolt11");
        if (bolt11 == null || bolt11.isEmpty()) {
            throw new IllegalStateException("Missing required bolt11 tag in zap receipt");
        }
        return bolt11;
    }

    public NostrPublicKey getProviderPubkey() {
        return getPubkey();
    }

    public ZapRequest getZapRequestEvent() {
        if(zapRequestEventCache!=null) return zapRequestEventCache;
        String zapRequestRaw = getFirstTagFirstValue("description");
        if(zapRequestRaw==null || zapRequestRaw.isEmpty()) {
            throw new IllegalStateException("Missing required description tag in zap receipt");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> zapRequestMap = (Map<String, Object>) NGEPlatform.get().fromJSON(zapRequestRaw, Map.class);
        zapRequestEventCache = new ZapRequest(zapRequestMap);
        return zapRequestEventCache;        
    }

    @Nullable
    public NostrEvent.Coordinates getZappedEventCoordinates() {
        String eTag = getFirstTagFirstValue("e");
        String kTag = getFirstTagFirstValue("k");
        String aTag = getFirstTagFirstValue("a");
        if(eTag!=null){
            return new NostrEvent.Coordinates("e", kTag!=null?kTag:"", eTag);
        } else if(aTag!=null){
            return new NostrEvent.Coordinates("a",  kTag!=null?kTag:"", aTag);
        }
        return null;
    }

    public NostrPublicKey getRecipient() {
        String recipient = getFirstTagFirstValue("p");
        if (recipient == null || recipient.isEmpty()) {
            throw new IllegalStateException("Missing required p tag in zap receipt");
        }
        return NostrPublicKey.fromHex(recipient);
    }

    @Nullable
    public NostrPublicKey getSender() {
        if(hasTag("P")){
            return NostrPublicKey.fromHex(getFirstTagFirstValue("P"));
        }
        return null;
    }
}