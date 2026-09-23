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

package org.ngengine.site.demos.quickstart;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.ngengine.nostr4j.NostrFilter;
import org.ngengine.nostr4j.NostrPool;
import org.ngengine.nostr4j.NostrRelay;
import org.ngengine.nostr4j.NostrSubscription;
import org.ngengine.nostr4j.event.SignedNostrEvent;
import org.ngengine.nostr4j.event.UnsignedNostrEvent;
import org.ngengine.nostr4j.event.tracker.NaiveEventTracker;
import org.ngengine.nostr4j.keypair.NostrKeyPair;
import org.ngengine.nostr4j.proto.NostrMessageAck;
import org.ngengine.nostr4j.signer.NostrKeyPairSigner;
import org.ngengine.platform.AsyncExecutor;
import org.ngengine.platform.AsyncTask;
import org.ngengine.platform.NGEPlatform;
import org.ngengine.platform.teavm.TeaVMPlatform;
import org.teavm.jso.JSBody;
import org.teavm.jso.JSFunctor;
import org.teavm.jso.JSObject;

/** Complete browser example: subscribe to a filter, publish a note, and receive it. */
public final class QuickStartDemo {

    private final Consumer<String> output;
    private final String content;
    private Consumer<SignedNostrEvent> received;
    private boolean seen;

    private QuickStartDemo(String content, Consumer<String> output) {
        this.content = content;
        this.output = output;
    }

    private void onEvent(NostrSubscription subscription, SignedNostrEvent event, boolean stored) {
        if (seen) return;
        seen = true;
        output.accept("Received: " + event.getContent());
        received.accept(event);
    }

    private void execute() throws Exception {
        if (content == null || content.isBlank() || content.getBytes(StandardCharsets.UTF_8).length > 1024) {
            throw new IllegalArgumentException("Write a note of at most 1024 UTF-8 bytes");
        }
        NostrKeyPair keys = new NostrKeyPair();
        NostrPool pool = new NostrPool();
        AsyncExecutor executor = NGEPlatform.get().newAsyncExecutor();
        AsyncTask<SignedNostrEvent> delivery = AsyncTask.create((resolve, reject) -> {
            received = resolve;
            executor.runLater(
                () -> {
                    reject.accept(new IllegalStateException("No event returned within 25 seconds"));
                    pool.close();
                    return null;
                },
                25,
                TimeUnit.SECONDS
            );
        });
        try {
            // Publish a short note with a temporary key.
            NostrKeyPairSigner signer = new NostrKeyPairSigner(keys);
            AsyncTask<NostrRelay> connection = pool.ensureRelay("wss://relay.ngengine.org");
            connection.await();

            NostrFilter filter = new NostrFilter();
            filter.withKind(1);
            filter.withAuthor(keys.getPublicKey());
            filter.since(Instant.now().minusSeconds(1));

            NostrSubscription subscription = pool.subscribe(filter, NaiveEventTracker::new);
            subscription.addEventListener(this::onEvent);
            List<AsyncTask<NostrMessageAck>> opened = subscription.open();
            AsyncTask.awaitAll(opened);

            UnsignedNostrEvent draft = new UnsignedNostrEvent();
            draft.withKind(1);
            draft.withContent(content);
            long expiresAt = Instant.now().plusSeconds(300).getEpochSecond();
            draft.withTag("expiration", Long.toString(expiresAt));

            AsyncTask<SignedNostrEvent> signing = signer.sign(draft);
            SignedNostrEvent event = signing.await();
            List<AsyncTask<NostrMessageAck>> published = pool.publish(event);
            for (AsyncTask<NostrMessageAck> task : published) {
                NostrMessageAck acknowledgement = task.await();
                if (acknowledgement.getStatus() == NostrMessageAck.Status.FAILURE) {
                    throw new IllegalStateException(acknowledgement.getMessage());
                }
            }
            output.accept("Event ID: " + event.getId());
            output.accept("Signed and published. Waiting for the subscription…");
            delivery.await();
            subscription.close();
            output.accept("Subscription closed; ephemeral identity discarded.");
        } finally {
            pool.close();
            keys.close();
            executor.close();
        }
    }

    @JSFunctor
    interface Log extends JSObject {
        void call(String text);
    }

    @JSFunctor
    interface Run extends JSObject {
        void call(Log log, Log done);
    }

    @JSFunctor
    interface Publish extends JSObject {
        void call(String content, Log log, Log done);
    }

    @JSBody(script = "return {};")
    private static native JSObject newObject();

    @JSBody(params = { "api" }, script = "window.QuickStartDemo = api;")
    private static native void setGlobal(JSObject api);

    @JSBody(params = { "api", "run" }, script = "api.run = run;")
    private static native void install(JSObject api, Run run);

    @JSBody(params = { "api", "publish" }, script = "api.publish = publish;")
    private static native void installPublish(JSObject api, Publish publish);

    private static void launch(String content, Log log, Log done) {
        AsyncExecutor executor = NGEPlatform.get().newAsyncExecutor();
        executor.run(() -> {
            try {
                new QuickStartDemo(content, log::call).execute();
                done.call("ok");
            } catch (Exception error) {
                log.call("Failed: " + error.getMessage());
                done.call("error");
            } finally {
                executor.close();
            }
            return null;
        });
    }

    public static void main(String[] args) {
        NGEPlatform.set(new TeaVMPlatform());
        JSObject api = newObject();
        install(api, (log, done) -> launch("Hello from Nostr4J!", log, done));
        installPublish(api, QuickStartDemo::launch);
        setGlobal(api);
    }
}
