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

import static org.junit.Assert.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.Test;
import org.ngengine.nostr4j.event.SignedNostrEvent;
import org.ngengine.platform.AsyncTask;
import org.ngengine.platform.NGEPlatform;
import org.ngengine.platform.NGEUtils;
import org.ngengine.platform.transport.NGEHttpResponse;

public class TestBlossomMedia {

    static class Server extends BlossomEndpoint {

        final AtomicInteger mirrors = new AtomicInteger(), media = new AtomicInteger(), uploads = new AtomicInteger();
        final AtomicInteger mirrorCalls = new AtomicInteger();
        final Map<String, AtomicInteger> probes = new HashMap<>();
        boolean unsupported, hostile, genericPreflight;
        int probeStatus;
        Throwable probeFailure;

        int probes(String operation) {
            AtomicInteger count = probes.get(operation);
            return count == null ? 0 : count.get();
        }

        Server(String url) {
            super(url);
        }

        @Override
        public AsyncTask<BlossomResponse> mirror(String source, SignedNostrEvent auth) {
            mirrorCalls.incrementAndGet();
            return super.mirror(source, auth);
        }

        @Override
        public AsyncTask<NGEHttpResponse> httpRequest(
            String endpoint,
            String method,
            Map<String, String> headers,
            byte[] body,
            SignedNostrEvent auth
        ) {
            if (method.equals("OPTIONS")) {
                probes.computeIfAbsent(endpoint, key -> new AtomicInteger()).incrementAndGet();
                if (probeFailure != null) {
                    return AsyncTask.failed(probeFailure);
                }
                int status = probeStatus != 0
                    ? probeStatus
                    : unsupported && !genericPreflight && !endpoint.equals("upload") ? 404 : 204;
                return AsyncTask.completed(new NGEHttpResponse(status, Map.of(), new byte[0], status < 400));
            }
            if (endpoint.equals("mirror")) mirrors.incrementAndGet();
            if (endpoint.equals("media")) media.incrementAndGet();
            if (endpoint.equals("upload")) uploads.incrementAndGet();
            if (unsupported && !endpoint.equals("upload")) return AsyncTask.completed(
                new NGEHttpResponse(404, Map.of(), new byte[0], false)
            );
            String hash = NGEUtils.bytesToHex(NGEPlatform.get().sha256(new byte[] { 1, 2, 3 }));
            Map<String, Object> descriptor = Map.of(
                "url",
                (hostile ? "http://tracking.example" : getUrl()) + "/" + hash + ".png",
                "sha256",
                hash,
                "size",
                3,
                "type",
                "image/png",
                "uploaded",
                1
            );
            return AsyncTask.completed(
                new NGEHttpResponse(201, Map.of(), NGEPlatform.get().toJSON(descriptor).getBytes(StandardCharsets.UTF_8), true)
            );
        }
    }

    @Test
    public void supportProbesCacheBothOutcomesIndependentlyForEachOperation() throws Exception {
        Server available = new Server("https://available.test");
        Server missing = new Server("https://missing.test");
        missing.unsupported = true;
        for (String operation : new String[] { "mirror", "media", "upload" }) {
            assertTrue(available.supports(operation).await());
            assertTrue(available.supports(operation).await());
            assertEquals(1, available.probes(operation));
        }
        for (String operation : new String[] { "mirror", "media" }) {
            assertFalse(missing.supports(operation).await());
            assertFalse(missing.supports(operation).await());
            assertEquals(1, missing.probes(operation));
        }
        assertEquals(0, available.mirrors.get());
        assertEquals(0, available.media.get());
        assertEquals(0, available.uploads.get());
    }

    @Test
    public void temporaryProbeFailuresAreRetriedAndAuthorizationDoesNotDisableAnOperation() throws Exception {
        for (int status : new int[] { 429, 500, 503 }) {
            Server server = new Server("https://retry.test");
            server.probeStatus = status;
            assertThrows(IOException.class, () -> server.supports("mirror").await());
            server.probeStatus = 204;
            assertTrue(server.supports("mirror").await());
            assertEquals(2, server.probes("mirror"));
        }
        Server offline = new Server("https://offline.test");
        offline.probeFailure = new IOException("Offline");
        assertThrows(IOException.class, () -> offline.supports("mirror").await());
        offline.probeFailure = null;
        assertTrue(offline.supports("mirror").await());
        assertEquals(2, offline.probes("mirror"));
        for (int status : new int[] { 401, 403 }) {
            Server protectedServer = new Server("https://protected.test");
            protectedServer.probeStatus = status;
            assertTrue(protectedServer.supports("mirror").await());
            assertTrue(protectedServer.supports("mirror").await());
            assertEquals(1, protectedServer.probes("mirror"));
        }
    }

    @Test
    public void actualOperationCorrectsAnOptimisticPreflightResult() throws Exception {
        Server server = new Server("https://generic-preflight.test");
        server.unsupported = true;
        server.genericPreflight = true;
        assertTrue(server.supports("mirror").await());
        assertThrows(UnsupportedOperationException.class, () -> server.mirror("https://origin.test/file", null).await());
        assertFalse(server.supports("mirror").await());
        assertThrows(UnsupportedOperationException.class, () -> server.mirror("https://origin.test/other", null).await());
        assertEquals(1, server.probes("mirror"));
        assertEquals(1, server.mirrors.get());
    }

    @Test
    public void missingOptionalEndpointsAreSkippedAndRemembered() throws Exception {
        Server good = new Server("https://good.test");
        Server old = new Server("https://old.test");
        old.unsupported = true;
        try (BlossomPool pool = new BlossomPool()) {
            pool.ensureEndpoint(old);
            pool.ensureEndpoint(good);
            assertTrue(pool.mirror("https://origin.test/picture.png").await().getUrl().startsWith(good.getUrl()));
            pool.mirror("https://origin.test/other.png").await();
            assertEquals(0, old.mirrors.get());
            assertEquals(0, old.mirrorCalls.get());
            assertFalse(old.supports("mirror").await());
            assertEquals(1, old.probes("mirror"));
            assertTrue(pool.uploadMedia(new byte[] { 1, 2, 3 }, "image/png").await().getUrl().startsWith(good.getUrl()));
            assertEquals(0, old.media.get());
            assertEquals(1, old.probes("media"));
        }
    }

    @Test
    public void optionalMirrorFailureIsQuietButCompleteFailureStillRejects() throws Exception {
        CopyOnWriteArrayList<LogRecord> warnings = new CopyOnWriteArrayList<LogRecord>();
        Logger logger = Logger.getLogger(NGEPlatform.class.getName());
        Handler handler = new Handler() {
            public void publish(LogRecord record) {
                if (record.getLevel().intValue() >= Level.WARNING.intValue()) warnings.add(record);
            }

            public void flush() {}

            public void close() {}
        };
        logger.addHandler(handler);
        try (BlossomPool pool = new BlossomPool()) {
            Server old = new Server("https://old.test");
            old.unsupported = true;
            pool.ensureEndpoint(old);
            pool.ensureEndpoint(new Server("https://good.test"));
            assertNotNull(pool.mirror("https://origin.test/picture.png").await());
            assertTrue(warnings.toString(), warnings.isEmpty());
        } finally {
            logger.removeHandler(handler);
        }
        try (BlossomPool pool = new BlossomPool()) {
            Server old = new Server("https://old.test");
            old.unsupported = true;
            pool.ensureEndpoint(old);
            assertThrows(Exception.class, () -> pool.mirror("https://origin.test/picture.png").await());
            assertThrows(Exception.class, () -> pool.mirror("https://origin.test/again.png").await());
            assertEquals(0, old.mirrorCalls.get());
            assertEquals(1, old.probes("mirror"));
        }
    }

    @Test
    public void optimizationFallsBackToUploadAndRemoteDescriptorCannotDowngradeTransport() throws Exception {
        Server old = new Server("https://old.test");
        old.unsupported = true;
        try (BlossomPool pool = new BlossomPool()) {
            pool.ensureEndpoint(old);
            assertEquals(3, pool.uploadMedia(new byte[] { 1, 2, 3 }, "image/png").await().getSize());
            assertEquals(1, old.uploads.get());
        }
        Server hostile = new Server("https://trusted.test");
        hostile.hostile = true;
        try {
            hostile.mirror("https://origin.test/picture.png", null).await();
            fail("A server must not return a different HTTP origin");
        } catch (Exception expected) {}
        assertThrows(IllegalArgumentException.class, () -> hostile.mirror("file:///etc/passwd", null).await());
    }
}
