package org.ngengine.nostr4j.nip57;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.ngengine.nostr4j.event.NostrEvent;
import org.ngengine.nostr4j.event.SignedNostrEvent;
import org.ngengine.nostr4j.keypair.NostrPublicKey;
import org.ngengine.platform.NGEUtils;

import jakarta.annotation.Nullable;

public class ZapRequest extends SignedNostrEvent {

    public ZapRequest(Map<String, Object> map) {
        super(Objects.requireNonNull(map, "map"));
    }

    public long getAmountMsats() {
        return NGEUtils.safeMSats(getFirstTagFirstValue("amount"));
    }

    @Nullable
    public String getLnurl() {
        return getFirstTagFirstValue("lnurl");
    }

    public NostrPublicKey getRecipient() {
        return NostrPublicKey.fromHex(getFirstTagFirstValue("p"));
    }

    public NostrPublicKey getSender() {
        return getPubkey();
    }

    public List<String> getRelays() {
        List<TagValue> relays = getTag("relays");
        if (relays.isEmpty()) return List.of();
        return List.copyOf(new ArrayList<>(relays.get(0).getAll()));
    }

    @Nullable
    public NostrEvent.Coordinates getZappedEventCoordinates() {
        String eTag = getFirstTagFirstValue("e");
        String kTag = getFirstTagFirstValue("k");
        String aTag = getFirstTagFirstValue("a");
        if (eTag != null) {
            return new NostrEvent.Coordinates("e", kTag != null ? kTag : "", eTag);
        } else if (aTag != null) {
            return new NostrEvent.Coordinates("a", kTag != null ? kTag : "", aTag);
        }
        return null;
    }
}