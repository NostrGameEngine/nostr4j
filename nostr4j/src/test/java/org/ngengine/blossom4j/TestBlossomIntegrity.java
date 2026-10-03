/** BSD 3-Clause License. Copyright (c) 2025, Riccardo Balbo. */
package org.ngengine.blossom4j;

import static org.junit.Assert.*;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.Test;
import org.ngengine.nostr4j.event.SignedNostrEvent;
import org.ngengine.platform.AsyncTask;
import org.ngengine.platform.NGEPlatform;
import org.ngengine.platform.NGEUtils;
import org.ngengine.platform.transport.NGEHttpResponse;

public class TestBlossomIntegrity {

    @Test
    public void rejectsSubstitutedDownloadsAndUploadDescriptors() throws Exception {
        byte[] original = new byte[] { 1, 2, 3 };
        String hash = NGEUtils.bytesToHex(NGEPlatform.get().sha256(original));
        Stub endpoint = new Stub();
        endpoint.response = new byte[] { 4, 5, 6 };
        expectFailure(endpoint.get(hash, null, null));
        endpoint.response = original;
        assertArrayEquals(original, endpoint.get(hash, null, null).await().data());
        endpoint.response = descriptor("0".repeat(64), 3);
        expectFailure(endpoint.upload(original, null, null));
        expectFailure(endpoint.upload(ByteBuffer.wrap(original), null, null));
        endpoint.response = descriptor(hash, 4);
        expectFailure(endpoint.upload(original, null, null));
        endpoint.response = descriptor(hash, 3);
        assertEquals(hash, endpoint.upload(original, null, null).await().blobs().get(0).getSha256());
    }

    private static byte[] descriptor(String hash, int size) {
        return NGEPlatform
            .get()
            .toJSON(
                Map.of(
                    "url",
                    "https://blob.example/" + hash,
                    "sha256",
                    hash,
                    "size",
                    size,
                    "type",
                    "application/octet-stream",
                    "uploaded",
                    1
                )
            )
            .getBytes(StandardCharsets.UTF_8);
    }

    private static void expectFailure(AsyncTask<?> task) {
        try {
            task.await();
            fail("Substituted content must fail integrity validation");
        } catch (Exception expected) {}
    }

    private static class Stub extends BlossomEndpoint {

        byte[] response;

        Stub() {
            super("https://blob.example");
        }

        @Override
        public AsyncTask<NGEHttpResponse> httpRequest(
            String path,
            String method,
            Map<String, String> headers,
            SignedNostrEvent event
        ) {
            return AsyncTask.completed(new NGEHttpResponse(200, Map.of(), response, true));
        }

        @Override
        public AsyncTask<NGEHttpResponse> httpRequest(
            String path,
            String method,
            Map<String, String> headers,
            byte[] body,
            SignedNostrEvent event
        ) {
            return AsyncTask.completed(new NGEHttpResponse(200, Map.of(), response, true));
        }
    }
}
