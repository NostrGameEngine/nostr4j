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

package org.ngengine.blossom4j;

import jakarta.annotation.Nullable;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import org.ngengine.platform.NGEUtils;

public class BlobDescriptor {

    private final String url;
    private final String sha256;
    private final long size;
    private final String type;
    private final Instant uploaded;
    private final Map<String, Object> extra;

    public BlobDescriptor(
        String url,
        String sha256,
        long size,
        String type,
        Instant uploaded,
        @Nullable Map<String, Object> extra
    ) {
        this.url = url;
        this.sha256 = sha256;
        this.size = size;
        this.type = type;
        this.uploaded = uploaded;
        this.extra = extra != null ? extra : Map.of();
    }

    public BlobDescriptor(Map<String, Object> map) {
        this(
            NGEUtils.safeString(Objects.requireNonNull(map.get("url"), "url is required")),
            NGEUtils.safeString(Objects.requireNonNull(map.get("sha256"), "sha256 is required")),
            NGEUtils.safeLong(Objects.requireNonNull(map.get("size"), "size is required")),
            NGEUtils.safeString(Objects.requireNonNull(map.get("type"), "type is required")),
            NGEUtils.safeInstantInSeconds(Objects.requireNonNull(map.get("uploaded"), "uploaded is required")),
            map
        );
    }

    public <T> T get(String key) {
        return (T) extra.get(key);
    }

    public String getUrl() {
        return url;
    }

    public String getSha256() {
        return sha256;
    }

    public long getSize() {
        return size;
    }

    public String getType() {
        return type;
    }

    public Instant getUploaded() {
        return uploaded;
    }

    @Override
    public String toString() {
        return (
            "BlobDescriptor{" +
            "url='" +
            url +
            '\'' +
            ", sha256='" +
            sha256 +
            '\'' +
            ", size=" +
            size +
            ", type='" +
            type +
            '\'' +
            ", uploaded=" +
            uploaded +
            ", extra=" +
            extra +
            '}'
        );
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof BlobDescriptor)) return false;
        BlobDescriptor that = (BlobDescriptor) o;
        return (
            size == that.size &&
            url.equals(that.url) &&
            sha256.equals(that.sha256) &&
            type.equals(that.type) &&
            uploaded.equals(that.uploaded) &&
            extra.equals(that.extra)
        );
    }

    @Override
    public int hashCode() {
        return Objects.hash(url, sha256, size, type, uploaded, extra);
    }

    public Map<String, Object> toMap() {
        HashMap<String, Object> map = new HashMap<>();
        map.putAll(extra);
        return extra;
    }
}
