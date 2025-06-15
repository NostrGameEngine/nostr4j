package org.ngengine.nostr4j.sdan;

import java.io.Serializable;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;


import org.ngengine.nostr4j.event.NostrEvent;
import org.ngengine.nostr4j.event.SignedNostrEvent;
import org.ngengine.nostr4j.event.UnsignedNostrEvent;
import org.ngengine.nostr4j.nip09.Nip09EventDeletion;
import org.ngengine.nostr4j.signer.NostrSigner;
import org.ngengine.platform.AsyncTask;
import org.ngengine.platform.NGEPlatform;
import org.ngengine.platform.NGEUtils;

import jakarta.annotation.Nullable;
import jakarta.annotation.Nonnull;

public class SdanBid implements Serializable,Cloneable {     
    public static final int KIND = 30100; // SDAN Bid kind
    protected String description;
    protected String context;
    protected String payload;
    protected int[] size; // [width, height]
    protected String link;
    protected String callToAction;
    protected long bid; // in satoshis
    protected Duration holdTime; // how long the ad should be held in the slot
    protected SdanActionType actionType; // k
    protected SdanMimeType mimeType; // m
    protected String tier1Category; // h
    protected String tier2Category; // H
    protected String tier3Category; // T
    protected String tier4Category; // t
    protected String language; // l
    protected List<String> targets; // p
    protected String id; // d
    protected SdanSlotBid slotBid; // f
    protected SdanSlotSize slotSize; // s
    protected Instant expirationTimestamp; // expiration 

    public SdanBid(
        @Nonnull String description,
        @Nullable String context,
        @Nonnull String payload,
        @Nonnull int[] size,
        @Nonnull String link,
        @Nullable String callToAction,
        @Nonnull long bid,
        @Nonnull Duration holdTime,

        // Tag fields
        @Nonnull SdanActionType actionType, // k
        @Nonnull SdanMimeType mimeType, // m
        @Nullable String tier1Category, // h
        @Nullable String tier2Category, // H
        @Nullable String tier3Category, // T
        @Nullable String tier4Category, // t
        @Nullable String language, // l
        @Nullable List<String> targets, // p
        @Nonnull String id, // d
        @Nonnull SdanSlotBid slotBid, // f
        @Nonnull SdanSlotSize slotSize, // s
        @Nullable Instant expirationTimestamp
    ) {
        this.description = Objects.requireNonNull(description, "Description cannot be null");
        this.context = context;
        this.payload = Objects.requireNonNull(payload, "Payload cannot be null");
        this.size = Objects.requireNonNull(size, "Size cannot be null");
        this.link = Objects.requireNonNull(link, "Link cannot be null");
        this.callToAction = callToAction;
        this.bid = bid;
        this.holdTime = Objects.requireNonNull(holdTime, "Hold time cannot be null");
        this.actionType = Objects.requireNonNull(actionType, "Action type cannot be null");
        this.mimeType = Objects.requireNonNull(mimeType, "MIME type cannot be null");
        this.tier1Category = tier1Category;
        this.tier2Category = tier2Category;
        this.tier3Category = tier3Category;
        this.tier4Category = tier4Category;
        this.language = language;
        this.targets = targets;
        this.id = Objects.requireNonNull(id, "ID cannot be null");
        this.slotBid = Objects.requireNonNull(slotBid, "Slot bid cannot be null");
        this.slotSize = Objects.requireNonNull(slotSize, "Slot size cannot be null");
        this.expirationTimestamp = expirationTimestamp;
    }


    public SdanBid(){

    }
 
    public SdanBid(NostrEvent event) {
        Objects.requireNonNull(event, "Event cannot be null");

        // Parse content JSON
        NGEPlatform platform = NGEPlatform.get();
        @SuppressWarnings("unchecked")
        Map<String, Object> content = platform.fromJSON(
                event.getContent(),
                Map.class);

        // Extract content fields with proper null handling
        this.description = NGEUtils.safeString(content.get("description"));
        this.context = NGEUtils.safeString(content.get("context")); // Fixed bug: wasn't assigning the result
        this.payload = NGEUtils.safeString(content.get("payload"));
        this.link = NGEUtils.safeString(content.get("link"));
        this.callToAction = NGEUtils.safeString(content.get("call_to_action"));
        this.bid = NGEUtils.safeLong(content.get("bid"));
        this.holdTime = Duration.ofSeconds(NGEUtils.safeLong(content.get("hold_time")));

        // Parse size array (with null check)
        List<Integer> sizeList = NGEUtils.safeIntList(content.get("size"));
        this.size = sizeList != null ? sizeList.stream()
                .mapToInt(Integer::intValue)
                .toArray() : new int[0];

        // Extract required tags with validation
        try {
            String actionType = getRequiredTag(event, "k", 0);
            this.actionType = SdanActionType.fromValue(actionType);

            String mimeType = getRequiredTag(event, "m", 0);
            this.mimeType = SdanMimeType.fromValue(mimeType);

            this.id = getRequiredTag(event, "d", 0);
            this.slotBid = SdanSlotBid.valueOf(getRequiredTag(event, "f", 0));
            this.slotSize = SdanSlotSize.valueOf(getRequiredTag(event, "s", 0));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid SDAN bid event: " + e.getMessage(), e);
        }

        // Extract optional tags
        this.tier1Category = getOptionalTag(event, "h", 0);
        this.tier2Category = getOptionalTag(event, "H", 0);
        this.tier3Category = getOptionalTag(event, "T", 0);
        this.tier4Category = getOptionalTag(event, "t", 0);
        this.language = getOptionalTag(event, "l", 0);

        // Extract target pubkeys (p tag)
        this.targets = event.hasTag("p") ? event.getFirstTag("p").getAll() : null;

        // Extract expiration
        this.expirationTimestamp = event.getExpiration();
    }

  
    private String getRequiredTag(NostrEvent event, String tagName, int index) {
        if (!event.hasTag(tagName)) {
            throw new IllegalArgumentException("Missing required tag: " + tagName);
        }
        return event.getFirstTag(tagName).get(index);
    }

   
    private String getOptionalTag(NostrEvent event, String tagName, int index) {
        return event.hasTag(tagName) ? event.getFirstTag(tagName).get(index) : null;
    }



    @Override
    public SdanBid clone() {
        try {
            return (SdanBid) super.clone();
        } catch (CloneNotSupportedException e) {
            throw new AssertionError("Cloning not supported", e);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SdanBid)) return false;
        SdanBid sdanBid = (SdanBid) o;
        return bid == sdanBid.bid &&
                Objects.equals(description, sdanBid.description) &&
                Objects.equals(context, sdanBid.context) &&
                Objects.equals(payload, sdanBid.payload) &&
                Objects.deepEquals(size, sdanBid.size) &&
                Objects.equals(link, sdanBid.link) &&
                Objects.equals(callToAction, sdanBid.callToAction) &&
                holdTime.equals(sdanBid.holdTime) &&
                actionType == sdanBid.actionType &&
                mimeType == sdanBid.mimeType &&
                Objects.equals(tier1Category, sdanBid.tier1Category) &&
                Objects.equals(tier2Category, sdanBid.tier2Category) &&
                Objects.equals(tier3Category, sdanBid.tier3Category) &&
                Objects.equals(tier4Category, sdanBid.tier4Category) &&
                Objects.equals(language, sdanBid.language) &&
                Objects.equals(targets, sdanBid.targets) &&
                Objects.equals(id, sdanBid.id) &&
                slotBid == sdanBid.slotBid &&
                slotSize == sdanBid.slotSize &&
                Objects.equals(expirationTimestamp, sdanBid.expirationTimestamp);
    }

    @Override
    public int hashCode() {
        return Objects.hash(description, context, payload, size, link, callToAction, bid, holdTime,
                actionType, mimeType, tier1Category, tier2Category, tier3Category, tier4Category,
                language, targets, id, slotBid, slotSize, expirationTimestamp);
    }

    @Override
    public String toString() {
        return "SdanBid{" +
                "description='" + description + '\'' +
                ", context='" + context + '\'' +
                ", payload='" + payload + '\'' +
                ", size=" + size[0]+"x"+size[1] +
                ", link='" + link + '\'' +
                ", callToAction='" + callToAction + '\'' +
                ", bid=" + bid +
                ", holdTime=" + holdTime +
                ", actionType=" + actionType +
                ", mimeType=" + mimeType +
                ", tier1Category='" + tier1Category + '\'' +
                ", tier2Category='" + tier2Category + '\'' +
                ", tier3Category='" + tier3Category + '\'' +
                ", tier4Category='" + tier4Category + '\'' +
                ", language='" + language + '\'' +
                ", targets=" + targets +
                ", id='" + id + '\'' +
                ", slotBid=" + slotBid +
                ", slotSize=" + slotSize +
                ", expirationTimestamp=" + expirationTimestamp +
                '}';
    }


    public String getDescription() {
        return description;
    }
    
    public String getContext() {
        return context;
    }
  
    public String getPayload() {
        return payload;
    }

    public int[] getSize() {
        return size;
    }
    
    public String getLink() {
        return link;
    }
  
    public String getCallToAction() {
        return callToAction;
    }
    
    public long getBid() {
        return bid;
    }
   
    public Duration getHoldTime() {
        return holdTime;
    }
   
    public SdanActionType getActionType() {
        return actionType;
    }

    public SdanMimeType getMimeType() {
        return mimeType;
    }

    public String getTier1Category() {
        return tier1Category;
    }
    public String getTier2Category() {
        return tier2Category;
    }
   
    public String getTier3Category() {
        return tier3Category;
    }
   
    public String getTier4Category() {
        return tier4Category;
    }
    
    public String getLanguage() {
        return language;
    }

   
    public List<String> getTargets() {
        return targets;
    }
    
    public String getId() {
        return id;
    }
    
    public SdanSlotBid getSlotBid() {
        return slotBid;
    }
    
    public SdanSlotSize getSlotSize() {
        return slotSize;
    }
    
    public Instant getExpirationTimestamp() {
        return expirationTimestamp;
    }
    
    public boolean isExpired() {
        return expirationTimestamp != null && Instant.now().isAfter(expirationTimestamp);   
    }

    public static class SdanOwnedBid extends SdanBid {
        protected NostrSigner signer;
        protected SignedNostrEvent event;
        public void setPayload(String payload) {
            this.payload = payload;
        }

        public void setDescription(String description) {
            this.description = description;
        }

        public void setContext(String context) {
            this.context = context;
        }

        public void setTier4Category(String tier4Category) {
            this.tier4Category = tier4Category;
        }

        public void setTier3Category(String tier3Category) {
            this.tier3Category = tier3Category;
        }

        public void setTier2Category(String tier2Category) {
            this.tier2Category = tier2Category;
        }

        public void setTier1Category(String tier1Category) {
            this.tier1Category = tier1Category;
        }

        public void setActionType(SdanActionType actionType) {
            this.actionType = actionType;
        }

        public void setMimeType(SdanMimeType mimeType) {
            this.mimeType = mimeType;
        }

        public void setBid(long bid) {
            this.bid = bid;
        }

        public void setLink(String link) {
            this.link = link;
        }

        public void setExpirationTimestamp(Instant expirationTimestamp) {
            this.expirationTimestamp = expirationTimestamp;
        }

        public void setSlotSize(SdanSlotSize slotSize) {
            this.slotSize = slotSize;
        }

        public void setSlotBid(SdanSlotBid slotBid) {
            this.slotBid = slotBid;
        }

        public void setId(String id) {
            this.id = id;
        }

        public void setTargets(List<String> targets) {
            this.targets = targets;
        }

        public void setLanguage(String language) {
            this.language = language;
        }

        public void setHoldTime(Duration holdTime) {
            this.holdTime = holdTime;
        }

        public void setCallToAction(String callToAction) {
            this.callToAction = callToAction;
        }

        public void setSize(int[] size) {
            this.size = size;
        }

        public SdanOwnedBid(
                @Nonnull NostrSigner signer,
                @Nonnull String description,
                @Nullable String context,
                @Nonnull String payload,
                @Nonnull int[] size,
                @Nonnull String link,
                @Nullable String callToAction,
                @Nonnull long bid,
                @Nonnull Duration holdTime,

                // Tag fields
                @Nonnull SdanActionType actionType, // k
                @Nonnull SdanMimeType mimeType, // m
                @Nullable String tier1Category, // h
                @Nullable String tier2Category, // H
                @Nullable String tier3Category, // T
                @Nullable String tier4Category, // t
                @Nullable String language, // l
                @Nullable List<String> targets, // p
                @Nonnull String id, // d
                @Nonnull SdanSlotBid slotBid, // f
                @Nonnull SdanSlotSize slotSize, // s
                @Nullable Instant expirationTimestamp) {
            super(description, context, payload, size, link, callToAction, bid, holdTime,
                    actionType, mimeType, tier1Category, tier2Category, tier3Category,
                    tier4Category, language, targets, id, slotBid, slotSize, expirationTimestamp);
            this.signer = Objects.requireNonNull(signer, "Signer cannot be null");
        }

        public SdanOwnedBid(@Nonnull NostrSigner signer) {
            super();
            this.signer = Objects.requireNonNull(signer, "Signer cannot be null");
        }



        public SdanOwnedBid(@Nonnull NostrSigner signer, @Nonnull SignedNostrEvent event)  {
            super(event);
            this.signer = signer;
            this.event = event;

        }

        public AsyncTask<Boolean> verifyOwnership(){
            if(this.event == null){
                return NGEPlatform.get().wrapPromise((res,rej)->res.accept(true));
            }
            return signer.getPublicKey().then(pubkey->{
                return pubkey.equals(event.getPubkey());
            });
        }

        public AsyncTask<SignedNostrEvent> cancellationEvent(){
            return cancellationEvent(null);
        }

        public AsyncTask<SignedNostrEvent> cancellationEvent(String reason){
            return signer.getPublicKey().compose(pubkey->{
                NostrEvent.Coordinates coordinates = new NostrEvent.Coordinates(
                    "a",
                    ""+KIND,
                    String.join(":", KIND+"", pubkey.asHex(), id)
                );
                UnsignedNostrEvent deletionEvent = Nip09EventDeletion.createDeletionEvent(reason, coordinates);
                return signer.sign(deletionEvent);
            });
        }

        public AsyncTask<SignedNostrEvent> toEvent() {
            UnsignedNostrEvent event = new UnsignedNostrEvent();
            Map<String, Object> content = new HashMap<>();
            content.put("description", description);
            content.put("context", context);
            content.put("payload", payload);
            content.put("size", size);
            content.put("link", link);
            content.put("call_to_action", callToAction);
            content.put("bid", bid);
            content.put("hold_time", holdTime.getSeconds());
            event.withContent(NGEPlatform.get().toJSON(content));

            event.withTag("k", actionType.getValue());
            event.withTag("m", mimeType.getValue());
            if (tier1Category != null) {
                event.withTag("h", tier1Category);
            }
            if (tier2Category != null) {
                event.withTag("H", tier2Category);
            }
            if (tier3Category != null) {
                event.withTag("T", tier3Category);
            }
            if (tier4Category != null) {
                event.withTag("t", tier4Category);
            }
            if (language != null) {
                event.withTag("l", language);
            }
            if (targets != null && !targets.isEmpty()) {
                event.withTag("p", targets);
            }
            event.withTag("d", id);
            event.withTag("f", slotBid.getValue());
            event.withTag("s", slotSize.getValue());
            if (expirationTimestamp != null) {
                event.withExpiration(expirationTimestamp);
            }

            event.withKind(KIND);
            return signer.sign(event);
        }
    }

 
}