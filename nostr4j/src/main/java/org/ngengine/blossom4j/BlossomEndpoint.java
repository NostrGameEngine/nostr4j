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
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import org.ngengine.nostr4j.event.SignedNostrEvent;
import org.ngengine.nostr4j.keypair.NostrPublicKey;
import org.ngengine.platform.AsyncTask;
import org.ngengine.platform.NGEPlatform;
import org.ngengine.platform.NGEUtils;
import org.ngengine.platform.transport.NGEHttpResponse;

public class BlossomEndpoint {

    private static final java.util.logging.Logger logger = java.util.logging.Logger.getLogger(BlossomEndpoint.class.getName());
    private final String url;

    public BlossomEndpoint(String url) {
        this.url = url;
    }

    /**
     * Get blob by SHA256
     *
     * @param sha256OrPath can be just sha256 or sha256.ext
     * @param byteRange optional, the range of bytes to fetch, can be null for full range
     * @param authEvent optional, can be null
     * @return
     */
    public AsyncTask<BlossomBlobResponse> get(String sha256OrPath, int[] byteRange, @Nullable SignedNostrEvent authEvent) {
        String endpoint = sha256OrPath;
        Map<String, String> headers = new HashMap<>();
        if (byteRange != null) {
            if (byteRange.length != 2) {
                throw new IllegalArgumentException(
                    "byteRange must be an array of two integers [start, end] or null for full range"
                );
            }
            headers.put("Range", "bytes=" + byteRange[0] + "-" + byteRange[1]);
        }
        return httpRequest(endpoint, "GET", headers, null, authEvent)
            .then(response -> {
                handleError(response);
                return new BlossomBlobResponse(response.body(), response);
            });
    }

    /**
     * Check if a blob exists
     *
     * @param sha256OrPath can be just sha256 or sha256.ext
     * @param authEvent optional, can be null
     * @return an AsyncTask that returns true if the blob exists, false if it does not
     */
    public AsyncTask<Boolean> exists(String sha256OrPath, @Nullable SignedNostrEvent authEvent) {
        String endpoint = sha256OrPath;
        return httpRequest(endpoint, "HEAD", null, null, authEvent)
            .then(res -> {
                return res.status();
            });
    }

    /**
     * Upload a blob
     *
     * @param data the byte array to upload
     * @param mimeType (optional), the MIME type of the blob, can be null for application/octet-stream
     * @param authEvent (optional), a SignedNostrEvent for authorization, can be null
     * @return an AsyncTask that returns a BlossomResponse containing the BlobDescriptor and the HTTP response
     */
    public AsyncTask<BlossomResponse> upload(byte[] data, @Nullable String mimeType, @Nullable SignedNostrEvent authEvent) {
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", mimeType != null ? mimeType : "application/octet-stream");
        headers.put("Content-Length", String.valueOf(data.length));
        return httpRequest("upload", "PUT", headers, data, authEvent)
            .then(response -> {
                handleError(response);
                // Parse the response body as a BlobDescriptor
                Map<String, Object> responseMap = NGEPlatform.get().fromJSON(NGEUtils.safeString(response.body()), Map.class);
                BlobDescriptor descriptor = new BlobDescriptor(responseMap);
                return new BlossomResponse(List.of(descriptor), response);
            });
    }

    /**
     * List blobs uploaded by a given public key
     *
     * @param pubkey the public key of the user whose blobs you want to list
     * @param since (optional) the start time for the listing, can be null
     * @param until (optional) the end time for the listing, can be null
     * @param authEvent (optional) a SignedNostrEvent for authorization, can be null
     * @return an AsyncTask that returns a BlossomResponse containing a list of BlobDescriptors and the HTTP response
     */
    @SuppressWarnings("unchecked")
    public AsyncTask<BlossomResponse> list(
        NostrPublicKey pubkey,
        @Nullable Instant since,
        @Nullable Instant until,
        @Nullable SignedNostrEvent authEvent
    ) {
        String endpoint = "list/" + pubkey.asHex();
        StringBuilder query = new StringBuilder();

        if (since != null) {
            if (query.length() != 0) {
                query.append("&");
            }
            query.append("since=").append(since.getEpochSecond());
        }
        if (until != null) {
            if (query.length() != 0) {
                query.append("&");
            }
            query.append("until=").append(until.getEpochSecond());
        }
        if (query.length() > 0) {
            endpoint += "?" + query.toString();
        }

        return httpRequest(endpoint.toString(), "GET", null, null, authEvent)
            .then(response -> {
                handleError(response);
                Collection<Map<String, Object>> rs = NGEPlatform
                    .get()
                    .fromJSON(NGEUtils.safeString(response.body()), Collection.class);
                List<BlobDescriptor> blobs = new ArrayList<>();
                for (Map<String, Object> blobMap : rs) {
                    BlobDescriptor blob = new BlobDescriptor(blobMap);
                    blobs.add(blob);
                }
                return new BlossomResponse(Collections.unmodifiableList(blobs), response);
            });
    }

    /**
     * Delete a blob by its SHA256 hash
     *
     * @param sha256
     * @param authEvent
     * @return
     */
    public AsyncTask<BlossomResponse> delete(String sha256, @Nullable SignedNostrEvent authEvent) {
        String endpoint = sha256;
        return httpRequest(endpoint, "DELETE", null, null, authEvent)
            .then(response -> {
                handleError(response);
                return new BlossomResponse(List.of(), response);
            });
    }

    @Override
    public String toString() {
        return "BlossomEndpoint{" + "url='" + url + '\'' + '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof BlossomEndpoint)) return false;
        BlossomEndpoint that = (BlossomEndpoint) o;
        return url.equals(that.url);
    }

    @Override
    public int hashCode() {
        return url.hashCode();
    }

    public AsyncTask<NGEHttpResponse> httpRequest(
        String endpoint,
        String method,
        @Nullable Map<String, String> headers,
        @Nullable byte[] body,
        @Nullable SignedNostrEvent authEvent
    ) {
        String fullUrl = (this.url.endsWith("/") ? this.url : this.url + "/") + endpoint;

        if (authEvent != null) {
            if (headers == null) {
                headers = new HashMap<>();
            }
            // Using the Authorization HTTP header, the kind 24242 event MUST be base64 encoded and use the Authorization scheme Nostr
            // Example HTTP Authorization header:
            // Authorization: Nostr eyJpZCI6IjhlY2JkY2RkNTMyOTIwMDEwNTUyNGExNDI4NzkxMzg4MWIzOWQxNDA5ZDhiOTBjY2RiNGI0M2Y4ZjBmYzlkMGMiLCJwdWJrZXkiOiI5ZjBjYzE3MDIzYjJjZjUwOWUwZjFkMzA1NzkzZDIwZTdjNzIyNzY5MjhmZDliZjg1NTM2ODg3YWM1NzBhMjgwIiwiY3JlYXRlZF9hdCI6MTcwODc3MTIyNywia2luZCI6MjQyNDIsInRhZ3MiOltbInQiLCJnZXQiXSxbImV4cGlyYXRpb24iLCIxNzA4ODU3NTQwIl1dLCJjb250ZW50IjoiR2V0IEJsb2JzIiwic2lnIjoiMDJmMGQyYWIyM2IwNDQ0NjI4NGIwNzFhOTVjOThjNjE2YjVlOGM3NWFmMDY2N2Y5NmNlMmIzMWM1M2UwN2I0MjFmOGVmYWRhYzZkOTBiYTc1NTFlMzA4NWJhN2M0ZjU2NzRmZWJkMTVlYjQ4NTFjZTM5MGI4MzI4MjJiNDcwZDIifQ==
            String authJson = NGEPlatform.get().toJSON(authEvent.toMap());
            String authBase64 = NGEPlatform.get().base64encode(authJson.getBytes(StandardCharsets.UTF_8));
            headers.put("Authorization", "Nostr " + authBase64);
        }

        URI url = NGEUtils.safeURI(fullUrl);
        return NGEPlatform.get().httpRequest(method, url.toString(), body, null, headers);
    }

    private void handleError(NGEHttpResponse response) throws IOException {
        if (!response.status()) {
            String reason = null;
            try {
                List<String> r = response.headers().get("X-Reason");
                if (r != null && r.size() > 0) {
                    reason = r.get(0);
                }
            } catch (Exception e) {
                logger.log(Level.FINE, "Error parsing upload response", e);
            }
            if (reason == null) {
                reason = "Request failed with status: " + response.statusCode();
            }
            if (response.body() != null) {
                logger.log(Level.FINE, "Request failed: " + reason + ", body: " + NGEUtils.safeString(response.body()));
            }
            throw new IOException(reason);
        }
    }
}
