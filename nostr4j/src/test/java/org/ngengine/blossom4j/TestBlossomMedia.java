package org.ngengine.blossom4j;

import static org.junit.Assert.*;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import org.ngengine.nostr4j.event.SignedNostrEvent;
import org.ngengine.platform.AsyncTask;
import org.ngengine.platform.NGEPlatform;
import org.ngengine.platform.NGEUtils;
import org.ngengine.platform.transport.NGEHttpResponse;

public class TestBlossomMedia {

    static class Server extends BlossomEndpoint {

        final AtomicInteger mirrors = new AtomicInteger(), media = new AtomicInteger(), uploads = new AtomicInteger();
        boolean unsupported, hostile;

        Server(String url) {
            super(url);
        }

        @Override
        public AsyncTask<NGEHttpResponse> httpRequest(
            String endpoint,
            String method,
            Map<String, String> headers,
            byte[] body,
            SignedNostrEvent auth
        ) {
            if (endpoint.equals("mirror")) mirrors.incrementAndGet();
            if (endpoint.equals("media")) media.incrementAndGet();
            if (endpoint.equals("upload")) uploads.incrementAndGet();
            if (unsupported && !endpoint.equals("upload")) return AsyncTask.completed(
                new NGEHttpResponse(404, Map.of(), new byte[0], false)
            );
            String hash = NGEUtils.bytesToHex(NGEPlatform.get().sha256(new byte[] { 1, 2, 3 }));
            Map<String, Object> descriptor = Map.of(
                "url",
                (hostile ? "https://tracking.example" : getUrl()) + "/" + hash + ".png",
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
    public void missingOptionalEndpointsAreSkippedAndRemembered() throws Exception {
        Server good = new Server("https://good.test");
        Server old = new Server("https://old.test");
        old.unsupported = true;
        try (BlossomPool pool = new BlossomPool()) {
            pool.ensureEndpoint(old);
            pool.ensureEndpoint(good);
            assertTrue(pool.mirror("https://origin.test/picture.png").await().getUrl().startsWith(good.getUrl()));
            pool.mirror("https://origin.test/other.png").await();
            assertEquals(1, old.mirrors.get());
            assertTrue(pool.uploadMedia(new byte[] { 1, 2, 3 }, "image/png").await().getUrl().startsWith(good.getUrl()));
            assertEquals(1, old.media.get());
        }
    }

    @Test
    public void optimizationFallsBackToUploadAndRemoteDescriptorCannotEscapeConfiguredServer() throws Exception {
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
            fail("A server must not return the original/third-party host");
        } catch (Exception expected) {}
        assertThrows(IllegalArgumentException.class, () -> hostile.mirror("file:///etc/passwd", null).await());
    }
}
