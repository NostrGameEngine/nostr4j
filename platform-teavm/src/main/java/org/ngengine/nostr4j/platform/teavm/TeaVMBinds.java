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

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.teavm.jso.JSByRef;
import org.teavm.jso.JSClass;
import org.teavm.jso.JSModule;
import org.teavm.jso.JSObject;
import org.teavm.jso.browser.TimerHandler;

@JSClass
@JSModule("./org/ngengine/nostr4j/platform/teavm/TeaVMBinds.js")
public interface TeaVMBinds extends JSObject {
    @JSByRef
    byte[] randomBytes(int length);

    @JSByRef
    byte[] generatePrivateKey();

    @JSByRef
    byte[] genPubKey(@JSByRef byte[] secKey);

    @JSByRef
    byte[] sha256(@JSByRef byte[] data);

    String toJSON(Object obj);

    Object fromJSON(String json);

    @JSByRef
    byte[] sign(@JSByRef byte[] data, @JSByRef byte[] privKeyBytes);

    boolean verify(
        @JSByRef byte[] data,
        @JSByRef byte[] pub,
        @JSByRef byte[] sig
    );

    @JSByRef
    byte[] secp256k1SharedSecret(
        @JSByRef byte[] privKey,
        @JSByRef byte[] pubKey
    );

    @JSByRef
    byte[] hmac(
        @JSByRef byte[] key,
        @JSByRef byte[] data1,
        @JSByRef byte[] data2
    );

    @JSByRef
    byte[] hkdf_extract(@JSByRef byte[] salt, @JSByRef byte[] ikm);

    @JSByRef
    byte[] hkdf_expand(@JSByRef byte[] prk, @JSByRef byte[] info, int length);

    String base64encode(@JSByRef byte[] data);

    @JSByRef
    byte[] base64decode(String data);

    @JSByRef
    byte[] chacha20(
        @JSByRef byte[] key,
        @JSByRef byte[] nonce,
        @JSByRef byte[] data
    );

    void setTimeout(TimerHandler fn, int delay);

    class TeaVMPromise<T> implements JSObject {

        public T result;
        public Throwable error;
        public boolean completed = false;
        public boolean failed = false;
        private final List<Consumer<T>> thenCallbacks = new ArrayList<>();
        private final List<Consumer<Throwable>> catchCallbacks =
            new ArrayList<>();

        public void resolve(T value) {
            if (!completed) {
                this.result = value;
                this.completed = true;
                for (Consumer<T> callback : thenCallbacks) {
                    callback.accept(value);
                }
            }
        }

        public void reject(Throwable error) {
            if (!completed) {
                this.error = error;
                this.completed = true;
                this.failed = true;
                for (Consumer<Throwable> callback : catchCallbacks) {
                    callback.accept(error);
                }
            }
        }

        public TeaVMPromise<T> then(Consumer<T> onFulfilled) {
            if (completed && !failed) {
                onFulfilled.accept(result);
            } else if (!completed) {
                thenCallbacks.add(onFulfilled);
            }
            return this;
        }

        public TeaVMPromise<T> catchError(Consumer<Throwable> onRejected) {
            if (completed && failed) {
                onRejected.accept(error);
            } else if (!completed) {
                catchCallbacks.add(onRejected);
            }
            return this;
        }
    }
}
