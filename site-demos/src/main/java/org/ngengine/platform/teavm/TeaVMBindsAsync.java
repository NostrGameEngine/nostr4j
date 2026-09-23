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
package org.ngengine.platform.teavm;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;
import org.ngengine.platform.NGEPlatform;
import org.ngengine.platform.teavm.webrtc.RTCIceCandidate;
import org.ngengine.platform.teavm.webrtc.RTCPeerConnection;
import org.ngengine.platform.teavm.webrtc.RTCSessionDescription;
import org.ngengine.platform.transport.NGEHttpResponse;

/**
 * Site-local compatibility shim for nge-platform-teavm 0.3.0-SNAPSHOT.
 *
 * The published bridge awaits JS promises from inside @Async callback bodies.
 * Those callbacks execute outside TeaVM's Java thread and fail with "Suspension
 * point reached from non-threading context". Ordinary Java methods propagate
 * Promise.await() to their executor-backed caller, which TeaVM can transform.
 *
 * Retains the public ABI of the upstream class; no crypto or wire-format changes.
 * Remove when the upstream bridge is fixed and browser RTC tests pass without it.
 */
public final class TeaVMBindsAsync {

    private TeaVMBindsAsync() {}

    public static byte[] scrypt(byte[] p, byte[] salt, int n, int r, int parallelism, int length) {
        return NGEPlatform.get().scrypt(p, salt, n, r, parallelism, length);
    }

    public static Boolean vfileExists(String name, String path) {
        return TeaVMBinds.vfileExistsPromise(name, path).await().booleanValue();
    }

    public static byte[] vfileRead(String name, String path) {
        return TeaVMBinds.vfileReadPromise(name, path).await().getData();
    }

    public static void vfileWrite(String name, String path, byte[] data) {
        TeaVMBinds.vfileWritePromise(name, path, data).await();
    }

    public static void vfileDelete(String name, String path) {
        TeaVMBinds.vfileDeletePromise(name, path).await();
    }

    public static String[] vfileListAll(String name) {
        var result = TeaVMBinds.vfileListAllPromise(name).await();
        String[] values = new String[result == null ? 0 : result.getLength()];
        for (int i = 0; i < values.length; i++) values[i] = result.get(i).stringValue();
        return values;
    }

    public static void rtcSetLocalDescription(RTCPeerConnection connection, String sdp, String type) {
        TeaVMBinds.rtcSetLocalDescriptionPromise(connection, sdp, type).await();
    }

    public static void rtcSetRemoteDescription(RTCPeerConnection connection, String sdp, String type) {
        TeaVMBinds.rtcSetRemoteDescriptionPromise(connection, sdp, type).await();
    }

    public static void rtcAddIceCandidate(RTCPeerConnection connection, RTCIceCandidate candidate) {
        TeaVMBinds.rtcAddIceCandidatePromise(connection, candidate).await();
    }

    public static RTCSessionDescription rtcCreateAnswer(RTCPeerConnection connection) {
        return TeaVMBinds.rtcCreateAnswerPromise(connection).await();
    }

    public static RTCSessionDescription rtcCreateOffer(RTCPeerConnection connection) {
        return TeaVMBinds.rtcCreateOfferPromise(connection).await();
    }

    public static NGEHttpResponse fetch(String method, String url, String headers, byte[] body, int timeout) {
        return response(TeaVMBinds.fetchPromise(method, url, headers, body, timeout).await());
    }

    public static NGEHttpResponse fetchBuffer(String method, String url, String headers, ByteBuffer body, int timeout) {
        return response(TeaVMBinds.fetchBufferPromise(method, url, headers, body, timeout).await());
    }

    private static NGEHttpResponse response(TeaVMHttpResponse response) {
        int status = response.getStatus();
        NGEPlatform platform = NGEPlatform.get();
        Map<String, List<String>> headers = platform.fromJSON(response.getHeaders(), Map.class);
        byte[] data = TeaVMPlatform.readHttpResponseBody(response, platform);
        return new NGEHttpResponse(status, headers, data, status >= 200 && status < 300);
    }
}
