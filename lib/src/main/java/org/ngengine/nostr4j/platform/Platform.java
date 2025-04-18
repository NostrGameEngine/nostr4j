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
package org.ngengine.nostr4j.platform;

import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Queue;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import org.ngengine.nostr4j.keypair.NostrPrivateKey;
import org.ngengine.nostr4j.keypair.NostrPublicKey;
import org.ngengine.nostr4j.transport.NostrTransport;
import org.ngengine.nostr4j.transport.RTCTransport;

public interface Platform {
    byte[] generatePrivateKey() throws Exception;
    byte[] genPubKey(byte[] secKey) throws Exception;
    String toJSON(Object obj) throws Exception;
    <T> T fromJSON(String json, Class<T> claz) throws Exception;

    byte[] secp256k1SharedSecret(byte[] privKey, byte[] pubKey) throws Exception;
    byte[] hmac(byte[] key, byte[] data1, byte[] data2) throws Exception;
    byte[] hkdf_extract(byte[] salt, byte[] ikm) throws Exception;
    byte[] hkdf_expand(byte[] prk, byte[] info, int length) throws Exception;
    String base64encode(byte[] data) throws Exception;
    byte[] base64decode(String data) throws Exception;
    byte[] chacha20(byte[] key, byte[] nonce, byte[] data, boolean forEncryption) throws Exception;

    NostrTransport newTransport();

    RTCTransport newRTCTransport(String connId, Collection<String> stunServers);

    String sha256(String data) throws NoSuchAlgorithmException;
    byte[] sha256(byte[] data) throws NoSuchAlgorithmException;
    String sign(String data, NostrPrivateKey privKey) throws Exception;
    boolean verify(String data, String sign, NostrPublicKey pubKey) throws Exception;

    AsyncTask<String> signAsync(String data, NostrPrivateKey privKey);

    AsyncTask<Boolean> verifyAsync(String data, String sign, NostrPublicKey pubKey);
    byte[] randomBytes(int n);

    NostrExecutor newRelayExecutor();

    NostrExecutor newSubscriptionExecutor();

    NostrExecutor newSignerExecutor();

    NostrExecutor newPoolExecutor();

    <T> AsyncTask<T> promisify(BiConsumer<Consumer<T>, Consumer<Throwable>> func, NostrExecutor executor);

    <T> AsyncTask<T> wrapPromise(BiConsumer<Consumer<T>, Consumer<Throwable>> func);

    <T> AsyncTask<List<T>> awaitAll(List<AsyncTask<T>> promises);

    long getTimestampSeconds();

    <T> Queue<T> newConcurrentQueue(Class<T> claz);

    AsyncTask<String> httpGet(String url, Duration timeout);
}
