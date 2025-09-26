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

package org.ngengine.nostr4j.signer;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.ngengine.nostr4j.event.SignedNostrEvent;
import org.ngengine.nostr4j.event.UnsignedNostrEvent;
import org.ngengine.nostr4j.keypair.NostrPublicKey;
import org.ngengine.platform.AsyncExecutor;
import org.ngengine.platform.AsyncTask;
import org.ngengine.platform.NGEPlatform;

public class NostrNIP07Signer implements NostrSigner {
    private final AsyncExecutor executor;
    private final Runnable closer;

    public NostrNIP07Signer() {
        this.executor = NGEPlatform.get().newAsyncExecutor(NostrNIP07Signer.class);
        this.closer = NGEPlatform.get().registerFinalizer(this,()->{
            this.executor.close();
        });
    }

    @Override
    public AsyncTask<SignedNostrEvent> sign(UnsignedNostrEvent event) {
        NGEPlatform p = NGEPlatform.get();

        Map<String, Object> params = new HashMap<>();
        params.put("kind", event.getKind());
        params.put("content", event.getContent());
        params.put("tags", event.getTagRows());
        params.put("created_at", event.getCreatedAt().getEpochSecond());

        return p.promisify((res, rej) -> {
            p.callFunction(
                "window.nostr.signEvent",
                List.of(params),
                result -> {
                    SignedNostrEvent signed = new SignedNostrEvent((Map<String, Object>) result);
                    res.accept(signed);
                },
                err -> {
                    rej.accept(err);
                }
            );
        }, executor);
    }

    private String getEncFun(EncryptAlgo algo, String type) {
        String fun = "window.nostr.";
        switch (algo) {
            case NIP04:
                fun = fun + "nip04";
            default:
            case NIP44:
                fun = fun + "nip44";
        }
        return fun + "." + type;
    }

    @Override
    public AsyncTask<String> encrypt(String message, NostrPublicKey publicKey, EncryptAlgo algo) {
        NGEPlatform p = NGEPlatform.get();
        return p.promisify((res, rej) -> {
            p.callFunction(
                getEncFun(algo, "encrypt"),
                List.of(publicKey.asHex(), message),
                result -> {
                    res.accept(result.toString());
                },
                err -> {
                    rej.accept(err);
                }
            );
        }, executor);
    }

    @Override
    public AsyncTask<String> decrypt(String message, NostrPublicKey publicKey, EncryptAlgo algo) {
        NGEPlatform p = NGEPlatform.get();
        return p.promisify((res, rej) -> {
            p.callFunction(
                getEncFun(algo, "decrypt"),
                List.of(publicKey.asHex(), message),
                result -> {
                    res.accept(result.toString());
                },
                err -> {
                    rej.accept(err);
                }
            );
        }, executor);
    }

    @Override
    public AsyncTask<NostrPublicKey> getPublicKey() {
        NGEPlatform p = NGEPlatform.get();
        return p.promisify((res, rej) -> {
            p.callFunction(
                "window.nostr.getPublicKey",
                List.of(),
                result -> {
                    res.accept(NostrPublicKey.fromHex(result.toString()));
                },
                err -> {
                    rej.accept(err);
                }
            );
        }, executor);
    }

    @Override
    public AsyncTask<NostrSigner> close() {
        closer.run();
        return NGEPlatform
            .get()
            .wrapPromise((res, rej) -> {
                res.accept(this);
            });
    }

    @Override
    public AsyncTask<Boolean> isAvailable() {
        NGEPlatform p = NGEPlatform.get();
        return p.promisify((res, rej) -> {
            if (!p.getPlatformName().contains("(browser)")) {
                res.accept(false);
                return;
            }
            p.canCallFunction(
                "window.nostr.getPublicKey",
                result -> {
                    res.accept((boolean) result);
                }
            );
        },executor);
    }
}
