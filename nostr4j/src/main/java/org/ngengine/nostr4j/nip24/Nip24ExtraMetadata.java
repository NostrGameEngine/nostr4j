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
package org.ngengine.nostr4j.nip24;

import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import org.ngengine.nostr4j.event.NostrEvent;
import org.ngengine.nostr4j.nip01.Nip01UserMetadata;
import org.ngengine.platform.NGEUtils;

public class Nip24ExtraMetadata extends Nip01UserMetadata {

    public Nip24ExtraMetadata(Nip01UserMetadata nip01) {
        super(nip01.getSourceEvent());
    }

    public Nip24ExtraMetadata(NostrEvent source) {
        super(source);
    }

    public String getDisplayName() {
        String v = NGEUtils.safeString(metadata.get("display_name"));
        if (v.isEmpty()) {
            v = NGEUtils.safeString(metadata.get("displayName"));
        }
        if (v.isEmpty()) return null;
        return NGEUtils.safeString(v);
    }

    public void setDisplayName(String name) {
        metadata.put("display_name", name);
    }

    @Override
    public String getName() {
        String v = super.getName();
        if (v != null) return v;
        v = NGEUtils.safeString(metadata.get("username"));
        if (v.isEmpty()) return null;
        return NGEUtils.safeString(v);
    }

    public String getWebsite() {
        Object v = metadata.get("website");
        if (v == null) return null;
        return NGEUtils.safeString(v);
    }

    public void setWebsite(String website) {
        metadata.put("website", website);
    }

    public String getBanner() {
        Object v = metadata.get("banner");
        if (v == null) return null;
        return NGEUtils.safeString(v);
    }

    public void setBanner(String banner) {
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
    public String getUsername() {
        Object v = metadata.get("username");
        if (v == null) return null;
        return NGEUtils.safeString(v);
    }

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

    public void setBirthday(Date birthday) {
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
}
