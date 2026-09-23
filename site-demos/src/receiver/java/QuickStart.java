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

import org.ngengine.nostr4j.NostrFilter;
import org.ngengine.nostr4j.NostrPool;
import org.ngengine.nostr4j.NostrSubscription;
import org.ngengine.nostr4j.event.SignedNostrEvent;
import org.ngengine.nostr4j.event.UnsignedNostrEvent;
import org.ngengine.nostr4j.keypair.NostrKeyPair;
import org.ngengine.nostr4j.signer.NostrKeyPairSigner;
import org.ngengine.platform.AsyncTask;
import org.ngengine.platform.NGEPlatform;
import org.ngengine.platform.jvm.JVMAsyncPlatform;

public class QuickStart {

    public static void main(String[] args) throws Exception {
        // snippet:start
        NGEPlatform.set(new JVMAsyncPlatform());

        NostrKeyPair keys = new NostrKeyPair();
        NostrPool pool = new NostrPool();
        NostrSubscription subscription = null;
        try {
            pool.ensureRelay("wss://relay.ngengine.org").await();

            NostrFilter filter = new NostrFilter();
            filter.withKind(1);
            filter.withAuthor(keys.getPublicKey());

            subscription = pool.subscribe(filter);
            subscription.addEventListener((sub, event, stored) -> System.out.println("Received: " + event.getContent()));
            AsyncTask.awaitAll(subscription.open());

            UnsignedNostrEvent draft = new UnsignedNostrEvent();
            draft.withKind(1);
            draft.withContent("Hello from Nostr4J!");

            NostrKeyPairSigner signer = new NostrKeyPairSigner(keys);
            SignedNostrEvent note = signer.sign(draft).await();
            AsyncTask.awaitAll(pool.publish(note));
            System.out.println("Published: " + note.getId());

            // Keep this standalone program alive briefly to receive the note.
            Thread.sleep(5_000);
        } finally {
            if (subscription != null) subscription.close();
            pool.close();
            keys.close();
        }
        // snippet:end
    }
}
