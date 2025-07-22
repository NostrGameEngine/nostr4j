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
import java.io.Closeable;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Level;
import org.ngengine.nostr4j.event.SignedNostrEvent;
import org.ngengine.nostr4j.keypair.NostrPublicKey;
import org.ngengine.nostr4j.signer.NostrSigner;
import org.ngengine.platform.AsyncExecutor;
import org.ngengine.platform.AsyncTask;
import org.ngengine.platform.NGEPlatform;
import org.ngengine.platform.NGEUtils;

public class BlossomPool implements Closeable {

    private static final java.util.logging.Logger logger = java.util.logging.Logger.getLogger(BlossomPool.class.getName());

    private final BlossomAuth auth;
    private final CopyOnWriteArrayList<BlossomEndpoint> endpoints = new CopyOnWriteArrayList<>();
    private final AsyncExecutor executor;
    private final List<Runnable> closers = new CopyOnWriteArrayList<>();

    public BlossomPool(BlossomAuth auth) {
        this.auth = auth;
        this.executor = NGEPlatform.get().newAsyncExecutor();
        this.closers.add(
                NGEPlatform
                    .get()
                    .registerFinalizer(
                        this,
                        () -> {
                            this.executor.close();
                        }
                    )
            );
    }

    public BlossomPool(NostrSigner auth) {
        this(auth == null ? null : new BlossomAuth(auth));
    }

    public BlossomPool() {
        this((BlossomAuth) null);
    }

    @Override
    public void close() {
        for (Runnable closer : closers) {
            try {
                closer.run();
            } catch (Exception e) {
                logger.log(Level.WARNING, "Error while closing BlossomPool", e);
            }
        }
        closers.clear();
    }

    public void connectEndpoint(BlossomEndpoint endpoint) {
        endpoints.add(Objects.requireNonNull(endpoint, "endpoint cannot be null"));
    }

    public void ensureEndpoint(BlossomEndpoint endpoint) {
        endpoints.addIfAbsent(Objects.requireNonNull(endpoint, "endpoint cannot be null"));
    }

    public AsyncTask<ByteBuffer> get(String sha256) {
        return get(sha256, (String) null);
    }

    public AsyncTask<ByteBuffer> get(String sha256, @Nullable String filename) {
        return get(sha256, filename);
    }

    public AsyncTask<ByteBuffer> get(String sha256, @Nullable int[] byteRange) {
        return get(sha256, null, byteRange);
    }

    public AsyncTask<ByteBuffer> get(String sha256, @Nullable String filename, @Nullable int[] byteRange) {
        return this.executor.run(() -> {
                boolean found = false;
                SignedNostrEvent authEvent = getAuthEvent(
                    BlossomVerb.GET,
                    "Get " + (filename != null ? filename : "blob"),
                    sha256
                )
                    .await();
                for (BlossomEndpoint endpoint : endpoints) {
                    try {
                        if (!endpoint.exists(sha256, authEvent).await()) {
                            continue;
                        }
                        found = true;
                        BlossomBlobResponse resp = endpoint.get(sha256, byteRange, authEvent).await();
                        if (resp.data() != null) {
                            byte[] data = resp.data();
                            return ByteBuffer.wrap(data);
                        }
                    } catch (Exception e) {
                        logger.log(Level.WARNING, "Error while fetching blob " + sha256 + " from endpoint " + endpoint, e);
                    }
                }
                if (!found) throw new FileNotFoundException(
                    "Blob with sha256 " + sha256 + " does not exist at any endpoint."
                ); else throw new IOException("Failed to fetch blob with sha256 " + sha256 + " from all endpoints.");
            });
    }

    public AsyncTask<Boolean> exists(String sha256) {
        return exists(sha256, null);
    }

    public AsyncTask<Boolean> exists(String sha256, @Nullable String filename) {
        return getAuthEvent(BlossomVerb.GET, "Get " + (filename != null ? filename : "blob"), sha256)
            .compose(authEvent -> {
                List<AsyncTask<Boolean>> tasks = new ArrayList<>();
                for (BlossomEndpoint endpoint : endpoints) {
                    tasks.add(endpoint.exists(sha256, authEvent));
                }
                return NGEPlatform
                    .get()
                    .awaitAny(
                        tasks,
                        r -> {
                            return r;
                        }
                    );
            });
    }

    public AsyncTask<BlobDescriptor> upload(ByteBuffer data) {
        return upload(data, null, null);
    }

    public AsyncTask<BlobDescriptor> upload(ByteBuffer data, @Nullable String fileName) {
        return upload(data, fileName);
    }

    public AsyncTask<BlobDescriptor> upload(ByteBuffer data, @Nullable String fileName, @Nullable String mimeType) {
        byte bytes[] = new byte[data.remaining()];
        data.slice().get(bytes);
        return this.executor.run(() -> {
                List<AsyncTask<BlossomResponse>> tasks = new ArrayList<>();
                String hex = NGEUtils.bytesToHex(NGEPlatform.get().sha256(bytes));
                SignedNostrEvent authEvent = getAuthEvent(
                    BlossomVerb.UPLOAD,
                    "Uploading " + (fileName != null ? fileName : "blob"),
                    hex
                )
                    .await();
                for (BlossomEndpoint endpoint : endpoints) {
                    tasks.add(endpoint.upload(bytes, mimeType, authEvent));
                }
                return NGEPlatform
                    .get()
                    .awaitAny(tasks)
                    .then(res -> {
                        return res.blobs().get(0);
                    })
                    .await();
            });
    }

    public AsyncTask<List<BlobDescriptor>> list(NostrPublicKey pubkey) {
        return list(pubkey, null, null);
    }

    public AsyncTask<List<BlobDescriptor>> list(NostrPublicKey pubkey, @Nullable Instant since, @Nullable Instant until) {
        logger.finest("list: " + pubkey + " since: " + since + " until: " + until);
        return getAuthEvent(BlossomVerb.LIST, "List Blobs", null)
            .compose(authEvent -> {
                CopyOnWriteArrayList<BlobDescriptor> out = new CopyOnWriteArrayList<>();
                List<AsyncTask<BlossomResponse>> tasks = new ArrayList<>();
                for (BlossomEndpoint endpoint : endpoints) {
                    tasks.add(
                        endpoint
                            .list(pubkey, since, until, authEvent)
                            .then(r -> {
                                for (BlobDescriptor blob : r.blobs()) {
                                    out.addIfAbsent(blob);
                                }
                                return null;
                            })
                    );
                }
                return NGEPlatform
                    .get()
                    .awaitAllSettled(tasks)
                    .then(r -> {
                        return Collections.unmodifiableList(out);
                    });
            });
    }

    public AsyncTask<Void> delete(String sha256) {
        return delete(sha256, null);
    }

    public AsyncTask<Void> delete(String sha256, @Nullable String filename) {
        logger.finest("delete: " + sha256 + " filename: " + filename);
        List<AsyncTask<BlossomResponse>> tasks = new ArrayList<>();
        return getAuthEvent(BlossomVerb.DELETE, "Delete " + (filename != null ? filename : "blob"), sha256)
            .compose(authEvent -> {
                for (BlossomEndpoint endpoint : endpoints) {
                    tasks.add(endpoint.delete(sha256, authEvent));
                }
                return NGEPlatform
                    .get()
                    .awaitAllSettled(tasks)
                    .then(r -> {
                        for (AsyncTask<BlossomResponse> task : r) {
                            try {
                                task.await();
                            } catch (Exception e) {
                                logger.log(Level.WARNING, "Error while deleting blob " + sha256 + " from endpoint", e);
                            }
                        }
                        return null;
                    });
            });
    }

    protected AsyncTask<SignedNostrEvent> getAuthEvent(BlossomVerb verb, String message, @Nullable String sha256) {
        return this.auth.getAuthEvent(verb, message, sha256 != null ? List.of(sha256) : List.of());
    }
}
