/**
 * BSD 3-Clause License. Copyright (c) 2025, Riccardo Balbo.
 */
package org.ngengine.blossom4j;

import static org.junit.Assert.*;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
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
        return descriptorAt("https://blob.example/" + hash, hash, size);
    }

    private static byte[] descriptorAt(String url, String hash, int size) {
        return NGEPlatform
            .get()
            .toJSON(Map.of("url", url, "sha256", hash, "size", size, "type", "application/octet-stream", "uploaded", 1))
            .getBytes(StandardCharsets.UTF_8);
    }

    @Test
    public void uploadAcceptsCdnAndAccountScopedOriginsWithoutRequestingThem() throws Exception {
        byte[] original = new byte[] { 1, 2, 3 };
        String hash = NGEUtils.bytesToHex(NGEPlatform.get().sha256(original));
        for (String origin : new String[] {
            "https://blob.example",
            "https://cdn.example",
            "https://npub1account.blossom.band",
        }) {
            for (String suffix : new String[] { "", "?token=x%2Fy&download=1", "#preview", "?token=x%2Fy#preview" }) {
                Stub endpoint = new Stub();
                String resource = origin + "/" + hash + ".png" + suffix;
                endpoint.response = descriptorAt(resource, hash, original.length);
                assertEquals(resource, endpoint.upload(original, "image/png", null).await().blobs().get(0).getUrl());
                assertEquals(1, endpoint.requests);
                endpoint.response = descriptorAt(resource, "0".repeat(64), original.length);
                expectFailure(endpoint.upload(original, "image/png", null));
                endpoint.response = descriptorAt(resource, hash, original.length + 1);
                expectFailure(endpoint.upload(original, "image/png", null));
            }
        }
    }

    @Test
    public void descriptorRejectsUnsafeOrAmbiguousUrlsAndKeepsConfiguredHttpSupport() throws Exception {
        String hash = "a".repeat(64);
        BlossomEndpoint endpoint = new BlossomEndpoint("https://blob.example");
        for (String url : new String[] {
            "http://cdn.example/" + hash + ".png",
            "file:///" + hash,
            "javascript:alert(1)",
            "/" + hash,
            "https://user:secret@cdn.example/" + hash,
            "https://cdn.example/elsewhere/" + hash,
            "https://cdn.example/%2e%2e/" + hash,
            "https://cdn.example/" + "b".repeat(64) + ".png",
            "https://cdn.example/%61" + hash.substring(1),
            "https://cdn.example/" + hash + ".png/extra",
            "https://cdn.example/%",
        }) {
            BlobDescriptor descriptor = new BlobDescriptor(url, hash, 3, "image/png", Instant.EPOCH, null);
            assertThrows(url, IOException.class, () -> endpoint.validateDescriptor(descriptor));
        }
        BlossomEndpoint local = new BlossomEndpoint("http://127.0.0.1:8080/prefix");
        local.validateDescriptor(
            new BlobDescriptor("http://127.0.0.1:8080/prefix/" + hash + ".png", hash, 3, "image/png", Instant.EPOCH, null)
        );
    }

    private static void expectFailure(AsyncTask<?> task) {
        try {
            task.await();
            fail("Substituted content must fail integrity validation");
        } catch (Exception expected) {}
    }

    private static class Stub extends BlossomEndpoint {

        byte[] response;
        int requests;

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
            requests++;
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
            requests++;
            return AsyncTask.completed(new NGEHttpResponse(200, Map.of(), response, true));
        }
    }
}
