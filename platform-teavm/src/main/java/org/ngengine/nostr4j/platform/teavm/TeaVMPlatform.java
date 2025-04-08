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
package org.ngengine.nostr4j.platform.teavm;

import java.nio.charset.StandardCharsets;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import org.ngengine.nostr4j.keypair.NostrPrivateKey;
import org.ngengine.nostr4j.keypair.NostrPublicKey;
import org.ngengine.nostr4j.platform.AsyncTask;
import org.ngengine.nostr4j.platform.NostrExecutor;
import org.ngengine.nostr4j.platform.Platform;
import org.ngengine.nostr4j.transport.NostrTransport;
import org.ngengine.nostr4j.utils.NostrUtils;
import org.teavm.jso.JSBody;

public class TeaVMPlatform implements Platform {

    @JSBody(script = "return window.nostr4j_jsBinds;")
    private static native TeaVMBinds getBinds();

    @Override
    public byte[] generatePrivateKey() throws Exception {
        return getBinds().generatePrivateKey();
    }

    @Override
    public byte[] genPubKey(byte[] secKey) throws Exception {
        return getBinds().genPubKey(secKey);
    }

    @Override
    public String toJSON(Object obj) throws Exception {
        return getBinds().toJSON(obj);
    }

    @Override
    public <T> T fromJSON(String json, Class<T> claz) throws Exception {
        return (T) getBinds().fromJSON(json);
    }

    @Override
    public byte[] secp256k1SharedSecret(byte[] privKey, byte[] pubKey)
            throws Exception {
        return getBinds().secp256k1SharedSecret(privKey, pubKey);
    }

    @Override
    public byte[] hmac(byte[] key, byte[] data1, byte[] data2)
            throws Exception {
        return getBinds().hmac(key, data1, data2);
    }

    @Override
    public byte[] hkdf_extract(byte[] salt, byte[] ikm) throws Exception {
        return getBinds().hkdf_extract(salt, ikm);
    }

    @Override
    public byte[] hkdf_expand(byte[] prk, byte[] info, int length)
            throws Exception {
        return getBinds().hkdf_expand(prk, info, length);
    }

    @Override
    public String base64encode(byte[] data) throws Exception {
        return getBinds().base64encode(data);
    }

    @Override
    public byte[] base64decode(String data) throws Exception {
        return getBinds().base64decode(data);
    }

    @Override
    public byte[] chacha20(
            byte[] key,
            byte[] nonce,
            byte[] data,
            boolean forEncryption) throws Exception {
        return getBinds().chacha20(key, nonce, data);
    }

    @Override
    public String sha256(String data) throws NoSuchAlgorithmException {
        byte[] bytes = data.getBytes(StandardCharsets.UTF_8);
        byte[] hash = getBinds().sha256(bytes);
        return NostrUtils.bytesToHex(hash);
    }

    @Override
    public byte[] sha256(byte[] data) throws NoSuchAlgorithmException {
        return getBinds().sha256(data);
    }

    @Override
    public String sign(String data, NostrPrivateKey privKey) throws Exception {
        byte dataB[] = NostrUtils.hexToByteArray(data);
        byte priv[] = privKey._array();
        byte sig[] = getBinds().sign(dataB, priv);
        return NostrUtils.bytesToHex(sig);
    }

    @Override
    public boolean verify(String data, String sign, NostrPublicKey pubKey)
            throws Exception {
        byte dataB[] = NostrUtils.hexToByteArray(data);
        byte sig[] = NostrUtils.hexToByteArray(sign);
        byte pub[] = pubKey._array();
        return getBinds().verify(dataB, pub, sig);
    }

    @Override
    public byte[] randomBytes(int n) {
        return getBinds().randomBytes(n);
    }

    @Override
    public long getTimestampSeconds() {
        return System.currentTimeMillis() / 1000;
    }

    @Override
    public <T> AsyncTask<T> promisify(
            BiConsumer<Consumer<T>, Consumer<Throwable>> func,
            NostrExecutor executor) {
        TeaVMBinds.TeaVMPromise<T> promise = new TeaVMBinds.TeaVMPromise<>();

        func.accept(promise::resolve, promise::reject);

        return new AsyncTask<T>() {
            @Override
            public T await() throws Exception {
                if (!promise.completed) {
                    throw new UnsupportedOperationException(
                            "Blocking await() is not supported in TeaVM");
                }

                if (promise.failed) {
                    if (promise.error instanceof Exception) {
                        throw (Exception) promise.error;
                    } else {
                        throw new Exception(promise.error);
                    }
                }

                return promise.result;
            }

            @Override
            public boolean isDone() {
                return promise.completed;
            }

            @Override
            public boolean isFailed() {
                return promise.failed;
            }

            @Override
            public boolean isSuccess() {
                return promise.completed && !promise.failed;
            }

            @Override
            public <R> AsyncTask<R> then(Function<T, R> func2) {
                return promisify((res, rej) -> {
                    promise
                            .then(result -> {
                                try {
                                    res.accept(func2.apply(result));
                                } catch (Throwable e) {
                                    rej.accept(e);
                                }
                            })
                            .catchError(rej::accept);
                }, executor);
            }

            @Override
            public AsyncTask<T> catchException(Consumer<Throwable> func2) {
                promise.catchError(func2);
                return this;
            }

            @Override
            public <R> AsyncTask<R> compose(Function<T, AsyncTask<R>> func2) {
                return promisify((res, rej) -> {
                    promise
                            .then(result -> {
                                try {

                                    try {
                                        AsyncTask<R> task2 = func2.apply(result);
                                        task2.catchException(exc -> {
                                            rej.accept(exc);
                                        });
                                        task2.then(r -> {
                                            res.accept(r);
                                            return null;
                                        });
                                    } catch (Throwable e) {
                                        rej.accept(e);
                                    }
                                } catch (Throwable e) {
                                    rej.accept(e);
                                }
                            })
                            .catchError(rej::accept);
                }, executor);
            }
        };
    }

    @Override
    public <T> AsyncTask<T> wrapPromise(BiConsumer<Consumer<T>, Consumer<Throwable>> func) {
        return (AsyncTask<T>) promisify(func, null);
    }

    public <T> AsyncTask<List<T>> awaitAll(List<AsyncTask<T>> promises) {
        return wrapPromise((res, rej) -> {
            if (promises.isEmpty()) {
                res.accept(new ArrayList<>());
                return;
            }

            AtomicInteger count = new AtomicInteger(promises.size());
            List<T> results = new ArrayList<>(count.get());

            for (int i = 0; i < count.get(); i++) {
                results.add(null);
            }

            for (int i = 0; i < promises.size(); i++) {
                final int j = i;
                AsyncTask<T> p = promises.get(i);
                p
                        .catchException(e -> {
                            rej.accept(e);
                        })
                        .then(r -> {
                            results.set(j, r);
                            if (count.decrementAndGet() == 0) {
                                res.accept(results);
                            }
                            return null;
                        });
            }
        });
    }

    private NostrExecutor newJsExecutor() {
        return new NostrExecutor() {
            @Override
            public <T> AsyncTask<T> run(Callable<T> r) {
                return wrapPromise((res, rej) -> {
                    // Execute on the next event loop cycle
                    getBinds()
                            .setTimeout(
                                    () -> {
                                        try {
                                            res.accept(r.call());
                                        } catch (Exception e) {
                                            rej.accept(e);
                                        }
                                    },
                                    0);
                });
            }

            @Override
            public <T> AsyncTask<T> runLater(
                    Callable<T> r,
                    long delay,
                    TimeUnit unit) {
                long delayMs = unit.toMillis(delay);

                if (delayMs == 0) {
                    return run(r);
                }

                return wrapPromise((res, rej) -> {
                    getBinds()
                            .setTimeout(
                                    () -> {
                                        try {
                                            res.accept(r.call());
                                        } catch (Exception e) {
                                            rej.accept(e);
                                        }
                                    },
                                    (int) delayMs);
                });
            }
        };
    }

    @Override
    public NostrExecutor newRelayExecutor() {
        return newJsExecutor();
    }

    @Override
    public NostrExecutor newPoolExecutor() {
        return newJsExecutor();
    }

    @Override
    public NostrExecutor newSubscriptionExecutor() {
        return newJsExecutor();
    }

    @Override
    public NostrTransport newTransport() {
        return new TeaVMWebsocketTransport(this);
    }

    @Override
    public <T> Queue<T> newConcurrentQueue(Class<T> claz) {
        return new LinkedList<T>();
    }

    @Override
    public AsyncTask<String> signAsync(String data, NostrPrivateKey privKey) {
        return promisify((res, rej) -> {
            try {
                res.accept(sign(data, privKey));
            } catch (Exception e) {
                rej.accept(e);
            }
        }, null);
    }

    @Override
    public AsyncTask<Boolean> verifyAsync(String data, String sign, NostrPublicKey pubKey) {
        return promisify((res, rej) -> {
            try {
                res.accept(verify(data, sign, pubKey));
            } catch (Exception e) {
                rej.accept(e);
            }
        }, null);
    }

}
