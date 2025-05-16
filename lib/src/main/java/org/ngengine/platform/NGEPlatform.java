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
package org.ngengine.platform;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.logging.Logger;

import org.ngengine.platform.transport.RTCTransport;
import org.ngengine.platform.transport.WebsocketTransport;

public abstract class NGEPlatform {
    private static volatile NGEPlatform platform;
    private static final Logger logger = Logger.getLogger(NGEPlatform.class.getName());

    public static void set(NGEPlatform platform) {
        NGEPlatform.platform = platform;
    }

    public static NGEPlatform get() {
        if (NGEPlatform.platform == null) { // DCL
            synchronized (NGEPlatform.class) {
                if (NGEPlatform.platform == null) {
                    logger.warning("Platform not set, using default JVM platform.");
                    String defaultPlatformClass = "org.ngengine.nostr4j.platform.jvm.JVMAsyncPlatform";
                    try {
                        Class<?> clazz = Class.forName(defaultPlatformClass);
                        NGEPlatform.platform = (NGEPlatform) clazz.getDeclaredConstructor().newInstance();
                    } catch (Exception e) {
                        throw new RuntimeException("Failed to load default platform: " + defaultPlatformClass, e);
                    }
                }
            }
        }
        return NGEPlatform.platform;
    }

    public abstract byte[] generatePrivateKey();
    
    public abstract byte[] genPubKey(byte[] secKey);
    
    public abstract String toJSON(Object obj);
    
    public abstract <T> T fromJSON(String json, Class<T> claz);

    public abstract byte[] secp256k1SharedSecret(byte[] privKey, byte[] pubKey);
    
    public abstract byte[] hmac(byte[] key, byte[] data1, byte[] data2);
    
    public abstract byte[] hkdf_extract(byte[] salt, byte[] ikm);
    
    public abstract byte[] hkdf_expand(byte[] prk, byte[] info, int length);
    
    public abstract String base64encode(byte[] data);
    public abstract byte[] base64decode(String data);
    
    public abstract byte[] chacha20(byte[] key, byte[] nonce, byte[] data, boolean forEncryption);

    public abstract WebsocketTransport newTransport();

    public abstract RTCTransport newRTCTransport(RTCSettings settings, String connId, Collection<String> stunServers);

    public abstract String sha256(String data);
    
    public abstract byte[] sha256(byte[] data);
    
    public abstract String sign(String data, byte privKey[]) throws FailedToSignException;
    
    public abstract boolean verify(String data, String sign, byte pubKey[]);

    public abstract AsyncTask<String> signAsync(String data, byte privKey[]);

    public abstract AsyncTask<Boolean> verifyAsync(String data, String sign, byte pubKey[]);
    
    public abstract byte[] randomBytes(int n);

    public abstract AsyncExecutor newRelayExecutor();

    public abstract AsyncExecutor newSubscriptionExecutor();

    public abstract AsyncExecutor newSignerExecutor();

    public abstract AsyncExecutor newPoolExecutor();

    public abstract <T> AsyncTask<T> promisify(BiConsumer<Consumer<T>, Consumer<Throwable>> func, AsyncExecutor executor);

    public abstract <T> AsyncTask<T> wrapPromise(BiConsumer<Consumer<T>, Consumer<Throwable>> func);

    public abstract <T> AsyncTask<List<T>> awaitAll(List<AsyncTask<T>> promises);

    public abstract <T> AsyncTask<List<AsyncTask<T>>> awaitAllSettled(List<AsyncTask<T>> promises);

    public abstract long getTimestampSeconds();

    public abstract <T> Queue<T> newConcurrentQueue(Class<T> claz);

    public abstract AsyncTask<String> httpGet(String url, Duration timeout, Map<String, String> headers);

    public abstract void setClipboardContent(String data);
    
    public abstract String getClipboardContent();
}
