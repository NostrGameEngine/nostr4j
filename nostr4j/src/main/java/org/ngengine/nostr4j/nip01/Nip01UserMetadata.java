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
package org.ngengine.nostr4j.nip01;

import jakarta.annotation.Nullable;
import java.io.Serializable;
import java.net.URISyntaxException;
import java.time.Instant;
import java.util.Calendar;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import org.ngengine.lnurl.LnAddress;
import org.ngengine.lnurl.LnUrl;
import org.ngengine.nostr4j.event.NostrEvent;
import org.ngengine.nostr4j.event.NostrEvent.TagValue;
import org.ngengine.nostr4j.event.UnsignedNostrEvent;
import org.ngengine.platform.NGEPlatform;
import org.ngengine.platform.NGEUtils;

public class Nip01UserMetadata implements Serializable {

    private static final long serialVersionUID = 1L;

    public final Map<String, Object> metadata;
    protected final NostrEvent sourceEvent;

    public Nip01UserMetadata() {
        this.sourceEvent = null;
        this.metadata = new HashMap<>();
    }

    public Nip01UserMetadata(NostrEvent source) {
        if (source.getKind() != 0) {
            throw new IllegalArgumentException("Invalid event kind");
        }
        this.sourceEvent = source;
        NGEPlatform platform = NGEUtils.getPlatform();
        String content = sourceEvent.getContent();
        Map<String, Object> meta = platform.fromJSON(content, Map.class);
        if (meta == null) throw new IllegalArgumentException("Invalid metadata");
        this.metadata = meta;
    }

    public UnsignedNostrEvent toUpdateEvent() {
        UnsignedNostrEvent event = new UnsignedNostrEvent();
        event.withKind(0);
        event.createdAt(Instant.now());
        event.withContent(NGEUtils.getPlatform().toJSON(metadata));
        if (sourceEvent != null) {
            for (String key : sourceEvent.listTagKeys()) {
                Collection<TagValue> tags = sourceEvent.getTag(key);
                for (TagValue tag : tags) {
                    event.withTag(key, tag);
                }
            }
        }
        return event;
    }

    public NostrEvent getSourceEvent() {
        return sourceEvent;
    }

    public void setName(@Nullable String name) {
        metadata.put("name", name);
    }

    @Nullable
    public String getAbout() {
        Object v = metadata.get("about");
        if (v == null) return null;
        return NGEUtils.safeString(v);
    }

    public void setAbout(@Nullable String about) {
        metadata.put("about", about);
    }

    @Nullable
    public String getPicture() {
        Object v = metadata.get("picture");
        if (v == null) return null;
        return NGEUtils.safeString(v);
    }

    public void setPicture(@Nullable String picture) {
        metadata.put("picture", picture);
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == null || !(obj instanceof Nip01UserMetadata)) {
            return false;
        }
        if (obj == this) {
            return true;
        }
        Nip01UserMetadata other = (Nip01UserMetadata) obj;
        if (sourceEvent == null) return metadata.equals(other.metadata);
        return sourceEvent.equals(other.sourceEvent);
    }

    @Override
    public int hashCode() {
        if (sourceEvent == null) return metadata.hashCode();
        return sourceEvent.hashCode();
    }

    @Override
    public String toString() {
        if (sourceEvent == null) return metadata.toString();

        return sourceEvent.toString();
    }

    @Nullable
    public String getName() {
        String v = NGEUtils.safeString(metadata.get("name"));
        if (v == null || v.isEmpty()) v = NGEUtils.safeString(metadata.get("username"));
        if (v == null || v.isEmpty()) return null;
        return v;
    }

    // nip24
    @Nullable
    public String getDisplayName() {
        String v = NGEUtils.safeString(metadata.get("display_name"));
        if (v.isEmpty()) {
            v = NGEUtils.safeString(metadata.get("displayName"));
        }
        if (v.isEmpty()) return null;
        return NGEUtils.safeString(v);
    }

    public void setDisplayName(@Nullable String name) {
        metadata.put("display_name", name);
    }

    @Nullable
    public String getWebsite() {
        Object v = metadata.get("website");
        if (v == null) return null;
        return NGEUtils.safeString(v);
    }

    public void setWebsite(@Nullable String website) {
        metadata.put("website", website);
    }

    @Nullable
    public String getBanner() {
        Object v = metadata.get("banner");
        if (v == null) return null;
        return NGEUtils.safeString(v);
    }

    public void setBanner(@Nullable String banner) {
        metadata.put("banner", banner);
    }

    public boolean isBot() {
        Object v = metadata.get("bot");
        if (v == null) return false;
        return NGEUtils.safeBool(v);
    }

    public void setBot(boolean bot) {
        metadata.put("bot", bot);
    }

    /**
     * use getName() instead
     */
    @Deprecated
    @Nullable
    public String getUsername() {
        Object v = metadata.get("username");
        if (v == null) return null;
        return NGEUtils.safeString(v);
    }

    @Nullable
    public Date getBirthday() {
        Map<String, Object> birthday = (Map<String, Object>) metadata.get("birthday");
        if (birthday == null) return null;
        int year = NGEUtils.safeInt(birthday.get("year"));
        int month = NGEUtils.safeInt(birthday.get("month"));
        int day = NGEUtils.safeInt(birthday.get("day"));
        if (year == 0 || month == 0 || day == 0) return null;
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.YEAR, year);
        cal.set(Calendar.MONTH, month - 1);
        cal.set(Calendar.DAY_OF_MONTH, day);
        return cal.getTime();
    }

    public void setBirthday(@Nullable Date birthday) {
        if (birthday == null) {
            metadata.remove("birthday");
            return;
        }
        Calendar cal = Calendar.getInstance();
        cal.setTime(birthday);
        Map<String, Object> b = new HashMap<>();
        b.put("year", cal.get(Calendar.YEAR));
        b.put("month", cal.get(Calendar.MONTH) + 1);
        b.put("day", cal.get(Calendar.DAY_OF_MONTH));
        metadata.put("birthday", b);
    }

    public void setBirthday(int year, int month, int day) {
        Map<String, Object> b = new HashMap<>();
        b.put("year", year);
        b.put("month", month);
        b.put("day", day);
        metadata.put("birthday", b);
    }

    // nip 57

    /**
     * Gets the lud06 field from the user metadata.
     * @return the LNURL or null if not present
     * @throws URISyntaxException
     */
    @Nullable
    public LnUrl getLnUrl() throws URISyntaxException {
        Object lud06 = metadata.get("lud06");
        if (lud06 == null) return null;
        String lnurl = NGEUtils.safeString(lud06);
        if (lnurl.isEmpty()) return null;
        return new LnUrl(lnurl);
    }

    /**
     * Sets the LNURL in the user metadata.
     *
     * @param lnUrl the LNURL to set, or null to remove it.
     */
    public void setLnUrl(@Nullable LnUrl lnUrl) {
        if (lnUrl == null) {
            metadata.remove("lud06");
        } else {
            metadata.put("lud06", lnUrl.toString());
        }
    }

    /**
     * Gets the lud16 field from the user metadata.
     * @return the LN Address or null if not present
     * @throws URISyntaxException
     */
    @Nullable
    public LnAddress getLnAddress() throws URISyntaxException {
        Object lud16 = metadata.get("lud16");
        if (lud16 == null) return null;
        String lnAddress = NGEUtils.safeString(lud16);
        if (lnAddress.isEmpty()) return null;
        return new LnAddress(lnAddress);
    }

    /**
     * Sets the LN Address in the user metadata.
     *
     * @param lnAddress the LN Address to set, or null to remove it.
     */
    public void setLnAddress(@Nullable LnAddress lnAddress) {
        if (lnAddress == null) {
            metadata.remove("lud16");
        } else {
            metadata.put("lud16", lnAddress.toString());
        }
    }

    /**
     * Get either the LNURL or LN Address from the user metadata, whichever is available.
     * @return the LNURL or LN Address returned as a {@link LnUrl} object, so that they can be used interchangeably.
     *
     * @throws URISyntaxException
     */
    public LnUrl getPaymentAddress() throws URISyntaxException {
        LnUrl lnUrl = getLnUrl();
        if (lnUrl != null) {
            return lnUrl;
        }
        LnAddress lnAddress = getLnAddress();
        if (lnAddress != null) {
            return lnAddress;
        }
        throw new IllegalStateException("No LNURL or LN Address found in metadata");
    }

    /**
     * Sets the payment address in the user metadata.
     * This can be either a {@link LnUrl} or a {@link LnAddress}, the method will handle both cases with the
     * appropriate metadata fields.
     *
     * If the payment address is null, it will remove both "lud06" and "lud16" fields from the metadata.
     *
     * @param paymentAddress the payment address to set, can be either a {@link LnUrl} or a {@link LnAddress}, or null to remove it.
     */
    public void setPaymentAddress(@Nullable LnUrl paymentAddress) {
        if (paymentAddress == null) {
            metadata.remove("lud06");
            metadata.remove("lud16");
        } else if (paymentAddress instanceof LnUrl) {
            setLnUrl((LnUrl) paymentAddress);
        } else if (paymentAddress instanceof LnAddress) {
            setLnAddress((LnAddress) paymentAddress);
        } else {
            throw new IllegalArgumentException("Unsupported payment address type: " + paymentAddress.getClass().getName());
        }
    }
}
