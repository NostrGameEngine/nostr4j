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
package org.ngengine.nostr4j.platform.jvm;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.logging.Logger;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.spec.ChaCha20ParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.bouncycastle.jce.ECNamedCurveTable;
import org.bouncycastle.jce.spec.ECParameterSpec;
import org.bouncycastle.math.ec.ECPoint;
import org.ngengine.nostr4j.keypair.NostrPrivateKey;
import org.ngengine.nostr4j.keypair.NostrPublicKey;
import org.ngengine.nostr4j.platform.AsyncTask;
import org.ngengine.nostr4j.platform.NostrExecutor;
import org.ngengine.nostr4j.platform.Platform;
import org.ngengine.nostr4j.transport.NostrTransport;
import org.ngengine.nostr4j.utils.NostrUtils;

// thread-safe
public class JVMAsyncPlatform implements Platform {
    static {
        if (Security.getProvider("BC") == null) {
            Security.addProvider(new org.bouncycastle.jce.provider.BouncyCastleProvider());
        }
    }

    private static final Logger logger = Logger.getLogger(JVMAsyncPlatform.class.getName());
    private static SecureRandom secureRandom;
    private static final byte EMPTY32[] = new byte[32];
    private static final byte EMPTY0[] = new byte[0];

    static {
        secureRandom = new SecureRandom();
    }

    // used for unit tests
    public static boolean _NO_AUX_RANDOM = false;
    public static boolean _EMPTY_NONCE = false;

    ///
    ///

    private static final class Context {

        MessageDigest sha256;
        Gson json;
        ECParameterSpec secp256k1;

        Context() throws NoSuchAlgorithmException, NoSuchPaddingException {
            sha256 = MessageDigest.getInstance("SHA-256");
            json = new GsonBuilder().disableHtmlEscaping().serializeNulls().create();
            secp256k1 = ECNamedCurveTable.getParameterSpec("secp256k1");
        }
    }

    private static final ThreadLocal<Context> context = ThreadLocal.withInitial(() -> {
        try {
            return new Context();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    });

    @Override
    public byte[] randomBytes(int n) {
        synchronized (secureRandom) {
            byte[] bytes = new byte[n];
            secureRandom.nextBytes(bytes);
            return bytes;
        }
    }

    @Override
    public byte[] generatePrivateKey() throws Exception {
        return Schnorr.generatePrivateKey();
    }

    @Override
    public byte[] genPubKey(byte[] secKey) throws Exception {
        return Schnorr.genPubKey(secKey);
    }

    @Override
    public String sha256(String data) throws NoSuchAlgorithmException {
        byte[] bytes = data.getBytes(StandardCharsets.UTF_8);
        return NostrUtils.bytesToHex(sha256(bytes));
    }

    @Override
    public byte[] sha256(byte data[]) throws NoSuchAlgorithmException {
        Context ctx = context.get();
        MessageDigest digest = ctx.sha256;
        byte[] hash = digest.digest(data);
        return hash;
    }

    @Override
    public String toJSON(Object obj) throws Exception {
        Context ctx = context.get();
        return ctx.json.toJson(obj);
    }

    @Override
    public <T> T fromJSON(String json, Class<T> claz) throws Exception {
        Context ctx = context.get();
        return ctx.json.fromJson(json, claz);
    }

    @Override
    public String sign(String data, NostrPrivateKey privKey) throws Exception {
        byte dataB[] = NostrUtils.hexToByteArray(data);
        byte priv[] = privKey._array();
        byte sigB[] = Schnorr.sign(dataB, priv, _NO_AUX_RANDOM ? null : randomBytes(32));
        String sig = NostrUtils.bytesToHex(sigB);
        return sig;
    }

    @Override
    public boolean verify(String data, String sign, NostrPublicKey pubKey) throws Exception {
        byte dataB[] = NostrUtils.hexToByteArray(data);
        byte sig[] = NostrUtils.hexToByteArray(sign);
        byte pub[] = pubKey._array();
        return Schnorr.verify(dataB, pub, sig);
    }

    @Override
    public byte[] secp256k1SharedSecret(byte[] privKey, byte[] pubKey) throws Exception {
        Context ctx = context.get();
        ECParameterSpec ecSpec = ctx.secp256k1;
        ECPoint point = ecSpec.getCurve().decodePoint(pubKey).normalize();
        BigInteger d = new BigInteger(1, privKey);
        ECPoint sharedPoint = point.multiply(d).normalize();
        return sharedPoint.getEncoded(false);
    }

    @Override
    public byte[] hmac(byte[] key, byte[] data1, byte[] data2) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        mac.update(data1, 0, data1.length);
        if (data2 != null) {
            mac.update(data2, 0, data2.length);
        }
        return mac.doFinal();
    }

    @Override
    public byte[] hkdf_extract(byte[] salt, byte[] ikm) throws Exception {
        assert NostrUtils.allZeroes(EMPTY32);
        if (salt == null || salt.length == 0) salt = EMPTY32;
        return hmac(salt, ikm, null);
    }

    @Override
    public byte[] hkdf_expand(byte[] prk, byte[] info, int length) throws Exception {
        int hashLen = 32; // SHA-256 output length

        if (length > 255 * hashLen) {
            throw new IllegalArgumentException("Length should be <= 255*HashLen");
        }

        int blocks = (int) Math.ceil((double) length / hashLen);

        if (info == null) {
            info = EMPTY0; // empty buffer
        }

        byte[] okm = new byte[blocks * hashLen];
        byte[] t = EMPTY0; // T(0) = empty string (zero length)
        byte[] counter = new byte[1]; // single byte counter

        // Use existing hmac functionality
        for (int i = 0; i < blocks; i++) {
            assert EMPTY0.length == 0;

            counter[0] = (byte) (i + 1); // N = counter + 1

            // T(N) = HMAC-Hash(PRK, T(N-1) | info | N)
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(prk, "HmacSHA256"));

            // Concatenate T(N-1) + info + counter
            byte[] combined = new byte[t.length + info.length + counter.length];
            System.arraycopy(t, 0, combined, 0, t.length);
            System.arraycopy(info, 0, combined, t.length, info.length);
            System.arraycopy(counter, 0, combined, t.length + info.length, counter.length);

            t = mac.doFinal(combined);
            System.arraycopy(t, 0, okm, hashLen * i, hashLen);
        }

        return Arrays.copyOf(okm, length);
    }

    @Override
    public String base64encode(byte[] data) throws Exception {
        return Base64.getEncoder().encodeToString(data);
    }

    @Override
    public byte[] base64decode(String data) throws Exception {
        try {
            return Base64.getDecoder().decode(data);
        } catch (Exception e) {
            throw new IllegalArgumentException("invalid base64 " + e.getMessage());
        }
    }

    @Override
    public byte[] chacha20(byte[] key, byte[] nonce, byte[] padded, boolean forEncryption) throws Exception {
        if (key.length != 32) {
            throw new IllegalArgumentException("ChaCha20 key must be 32 bytes");
        }
        if (nonce.length != 12) {
            throw new IllegalArgumentException("ChaCha20 nonce must be 12 bytes");
        }
        Cipher cipher = Cipher.getInstance("ChaCha20");
        ChaCha20ParameterSpec spec = new ChaCha20ParameterSpec(nonce, 0);
        cipher.init(forEncryption ? Cipher.ENCRYPT_MODE : Cipher.DECRYPT_MODE, new SecretKeySpec(key, "ChaCha20"), spec);

        return cipher.doFinal(padded);
    }

    // private ExecutorService asyncExecutor =
    // Executors.newVirtualThreadPerTaskExecutor();

    // public <T> AsyncTask<T> async(Callable<T> r) {
    // Future<T> res = asyncExecutor.submit(r);
    // return new AsyncTask<T>() {
    // @Override
    // public T await() throws Exception {
    // return res.get();
    // }
    // };
    // }

    @Override
    public NostrTransport newTransport() {
        return new WebsocketTransport(this, executor);
    }

    @Override
    public <T> AsyncTask<T> promisify(BiConsumer<Consumer<T>, Consumer<Throwable>> func, NostrExecutor executor) {
        CompletableFuture<T> fut = new CompletableFuture<>();
        if (executor != null && executor instanceof VtNostrExecutor) {
            ((VtNostrExecutor) executor).executor.submit(() -> {
                    try {
                        func.accept(
                            r -> {
                                fut.complete(r);
                            },
                            err -> {
                                fut.completeExceptionally(err);
                            }
                        );
                    } catch (Throwable e) {
                        fut.completeExceptionally(e);
                    }
                });
        } else {
            func.accept(
                r -> {
                    fut.complete(r);
                },
                err -> {
                    fut.completeExceptionally(err);
                }
            );
        }
        return new AsyncTask<T>() {
            @Override
            public T await() throws Exception {
                return fut.get();
            }

            @Override
            public boolean isDone() {
                return fut.isDone();
            }

            @Override
            public boolean isFailed() {
                try {
                    fut.get();
                    return false;
                } catch (Throwable e) {
                    return true;
                }
            }

            @Override
            public boolean isSuccess() {
                try {
                    fut.get();
                    return true;
                } catch (Throwable e) {
                    return false;
                }
            }

            @Override
            public <R> AsyncTask<R> then(Function<T, R> func2) {
                return promisify(
                    (res, rej) -> {
                        if (executor != null && executor instanceof VtNostrExecutor) {
                            fut.handleAsync(
                                (result, exception) -> {
                                    if (exception != null) {
                                        rej.accept(exception);
                                        return null;
                                    }

                                    try {
                                        res.accept(func2.apply(result));
                                    } catch (Throwable e) {
                                        rej.accept(e);
                                    }
                                    return null;
                                },
                                ((VtNostrExecutor) executor).executor
                            );
                        } else {
                            fut.handle((result, exception) -> {
                                if (exception != null) {
                                    rej.accept(exception);
                                    return null;
                                }

                                try {
                                    res.accept(func2.apply(result));
                                } catch (Throwable e) {
                                    rej.accept(e);
                                }
                                return null;
                            });
                        }
                    },
                    executor
                );
            }

            @Override
            public <R> AsyncTask<R> compose(Function<T, AsyncTask<R>> func2) {
                return promisify(
                    (res, rej) -> {
                        if (executor != null && executor instanceof VtNostrExecutor) {
                            fut.handleAsync(
                                (result, exception) -> {
                                    if (exception != null) {
                                        rej.accept(exception);
                                        return null;
                                    }

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
                                    return null;
                                },
                                ((VtNostrExecutor) executor).executor
                            );
                        } else {
                            fut.handle((result, exception) -> {
                                if (exception != null) {
                                    rej.accept(exception);
                                    return null;
                                }

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
                                return null;
                            });
                        }
                    },
                    executor
                );
            }

            @Override
            public AsyncTask<T> catchException(Consumer<Throwable> func2) {
                // synchronized (fut) {
                if (executor != null && executor instanceof VtNostrExecutor) {
                    fut.handleAsync(
                        (result, exception) -> {
                            if (exception != null) {
                                func2.accept(exception);
                            }
                            return null;
                        },
                        ((VtNostrExecutor) executor).executor
                    );
                } else {
                    fut.handle((result, exception) -> {
                        if (exception != null) {
                            func2.accept(exception);
                        }
                        return null;
                    });
                }
                // }
                return this;
            }
        };
    }

    @Override
    public <T> AsyncTask<T> wrapPromise(BiConsumer<Consumer<T>, Consumer<Throwable>> func) {
        return (AsyncTask<T>) promisify(func, null);
    }

    @Override
    public <T> AsyncTask<List<T>> awaitAll(List<AsyncTask<T>> promises) {
        return wrapPromise((res, rej) -> {
            if (promises.size() == 0) {
                res.accept(new ArrayList<>());
                return;
            }

            AtomicInteger count = new AtomicInteger(promises.size());
            List<T> results = new ArrayList<>(count.get()); // FIXME: should be concurrent
            for (int i = 0; i < count.get(); i++) {
                results.add(null);
            }

            for (int i = 0; i < promises.size(); i++) {
                final int index = i;
                AsyncTask<T> promise = promises.get(i);

                promise
                    .catchException(e -> {
                        e.printStackTrace();
                        rej.accept(e);
                    })
                    .then(result -> {
                        int remaining = count.decrementAndGet();
                        results.set(index, result);
                        if (remaining == 0) {
                            res.accept(results);
                        }
                        return null;
                    });
            }
        });
    }

    private ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    private class VtNostrExecutor implements NostrExecutor {

        protected final ExecutorService executor;

        public VtNostrExecutor(ExecutorService executor) {
            this.executor = executor;
        }

        @Override
        public <T> AsyncTask<T> run(Callable<T> r) {
            return wrapPromise((res, rej) -> {
                executor.submit(() -> {
                    try {
                        res.accept(r.call());
                    } catch (Exception e) {
                        rej.accept(e);
                    }
                });
            });
        }

        @Override
        public <T> AsyncTask<T> runLater(Callable<T> r, long delay, TimeUnit unit) {
            long delayMs = unit.toMillis(delay);
            if (delayMs == 0) {
                return run(r);
            }
            return wrapPromise((res, rej) -> {
                executor.submit(() -> {
                    try {
                        Thread.sleep(delayMs);
                        res.accept(r.call());
                    } catch (Exception e) {
                        rej.accept(e);
                    }
                });
            });
        }

        @Override
        public void close() {}
    }

    private NostrExecutor newVtExecutor() {
        return new VtNostrExecutor(executor);
    }

    @Override
    public NostrExecutor newRelayExecutor() {
        return newVtExecutor();
    }

    @Override
    public NostrExecutor newSubscriptionExecutor() {
        return newVtExecutor();
    }

    @Override
    public NostrExecutor newSignerExecutor() {
        return newVtExecutor();
    }

    @Override
    public long getTimestampSeconds() {
        return System.currentTimeMillis() / 1000;
    }

    @Override
    public <T> Queue<T> newConcurrentQueue(Class<T> claz) {
        return new ConcurrentLinkedQueue<T>();
    }

    @Override
    public AsyncTask<String> signAsync(String data, NostrPrivateKey privKey) {
        return wrapPromise((res, rej) -> {
            CompletableFuture.runAsync(() -> {
                try {
                    String sig = sign(data, privKey);
                    res.accept(sig);
                } catch (Exception e) {
                    rej.accept(e);
                }
            });
        });
    }

    @Override
    public AsyncTask<Boolean> verifyAsync(String data, String sign, NostrPublicKey pubKey) {
        return wrapPromise((res, rej) -> {
            CompletableFuture.runAsync(() -> {
                try {
                    boolean verified = verify(data, sign, pubKey);
                    res.accept(verified);
                } catch (Exception e) {
                    rej.accept(e);
                }
            });
        });
    }
}
