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

import java.util.logging.Level;
import java.util.logging.Logger;
import org.ngengine.nostr4j.TestLogger;

public class TestBlossom {

    private static final Logger logger = TestLogger.getRoot(Level.FINEST);
    // @Test
    // public void testBlossom() throws Exception {
    //     NostrKeyPairSigner signer = new NostrKeyPairSigner(new NostrKeyPair());
    //     BlossomPool pool = new BlossomPool(signer);
    //     try {
    //         pool.ensureEndpoint(new BlossomEndpoint("https://blossom.primal.net/"));

    //         ByteBuffer sourceBlob = ByteBuffer.allocate(10);
    //         for (int i = 0; i < 10; i++) {
    //             sourceBlob.put((byte) i);
    //         }
    //         sourceBlob.flip();

    //         BlobDescriptor desc = pool.upload(sourceBlob).await();
    //         System.out.println("Uploaded blob: " + desc);

    //         assertEquals(desc.getSha256(), "1f825aa2f0020ef7cf91dfa30da4668d791c5d4824fc8e41354b89ec05795ab3");
    //         assertEquals(desc.getSize(), 10);

    //         List<BlobDescriptor> blobs = pool.list(signer.getPublicKey().await()).await();
    //         assertEquals(blobs.size(), 1);

    //         assertEquals(blobs.get(0).getSha256(), desc.getSha256());

    //         pool.delete(desc.getSha256()).await();

    //         List<BlobDescriptor> emptyList = pool.list(signer.getPublicKey().await()).await();
    //         assertEquals(emptyList.size(), 0);
    //     } finally {
    //         pool.close();
    //     }
    // }

    // @Test
    // public void testBlossomMultipleFiles() throws Exception {
    //     NostrKeyPairSigner signer = new NostrKeyPairSigner(new NostrKeyPair());
    //     BlossomPool pool = new BlossomPool(signer);
    //     try {
    //         pool.ensureEndpoint(new BlossomEndpoint("https://blossom.primal.net/"));

    //         int fileCount = 3;
    //         ByteBuffer[] blobsToUpload = new ByteBuffer[fileCount];
    //         String[] expectedHashes = new String[fileCount];
    //         BlobDescriptor[] descriptors = new BlobDescriptor[fileCount];

    //         // Prepare and upload multiple blobs
    //         for (int i = 0; i < fileCount; i++) {
    //             blobsToUpload[i] = ByteBuffer.allocate(10);
    //             for (int j = 0; j < 10; j++) {
    //                 blobsToUpload[i].put((byte) (i * 10 + j));
    //             }
    //             blobsToUpload[i].flip();

    //             descriptors[i] = pool.upload(blobsToUpload[i]).await();
    //             System.out.println("Uploaded blob " + i + ": " + descriptors[i]);
    //             assertEquals(descriptors[i].getSize(), 10);
    //             expectedHashes[i] = descriptors[i].getSha256();
    //         }

    //         // List blobs and check all are present
    //         List<BlobDescriptor> blobs = pool.list(signer.getPublicKey().await()).await();
    //         assertEquals(blobs.size(), fileCount);

    //         for (int i = 0; i < fileCount; i++) {
    //             int j = i;
    //             boolean found = blobs.stream().anyMatch(b -> b.getSha256().equals(expectedHashes[j]));
    //             assertEquals(true, found);
    //         }

    //         // Delete all blobs
    //         for (int i = 0; i < fileCount; i++) {
    //             pool.delete(expectedHashes[i]).await();
    //         }

    //         // List should be empty
    //         List<BlobDescriptor> emptyList = pool.list(signer.getPublicKey().await()).await();
    //         assertEquals(emptyList.size(), 0);
    //     } finally {
    //         pool.close();
    //     }
    // }
}
