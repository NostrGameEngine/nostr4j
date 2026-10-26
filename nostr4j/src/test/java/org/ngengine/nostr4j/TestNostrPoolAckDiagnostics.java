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
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS
 * OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION)
 * HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT
 * LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY
 * OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH
 * DAMAGE.
 */
package org.ngengine.nostr4j;

import static org.junit.Assert.*;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.Test;
import org.ngengine.nostr4j.event.SignedNostrEvent;
import org.ngengine.nostr4j.proto.NostrMessage;
import org.ngengine.nostr4j.proto.NostrMessageAck;
import org.ngengine.platform.AsyncTask;

public class TestNostrPoolAckDiagnostics {

    private static NostrPool pool(List<AsyncTask<NostrMessageAck>> tasks) {
        return new NostrPool() {
            @Override
            protected List<AsyncTask<NostrMessageAck>> sendMessage(NostrMessage message) {
                return tasks;
            }
        };
    }

    private static NostrMessageAck rejected(String address, String reason) {
        NostrRelay relay = address == null ? null : new NostrRelay(address);
        NostrMessageAck ack = NostrMessage.ack(relay, "event", Instant.now(), null, null);
        ack.callFailureCallback(reason);
        return ack;
    }

    private static IllegalStateException failure(List<AsyncTask<NostrMessageAck>> tasks) throws Exception {
        try {
            pool(tasks).publish((SignedNostrEvent) null).await();
            throw new AssertionError("Expected rejected publication");
        } catch (IllegalStateException expected) {
            assertEquals("Failed to achieve required acknowledgements", expected.getMessage());
            return expected;
        }
    }

    @Test(timeout = 5000)
    public void allRejectedRelaysRetainTheirIndividualReasons() throws Exception {
        IllegalStateException error = failure(
            List.of(
                AsyncTask.completed(rejected("wss://relay-one.example", "replaced: have newer event")),
                AsyncTask.completed(rejected("wss://relay-two.example", "rate-limited: slow down"))
            )
        );
        assertEquals(2, error.getSuppressed().length);
        assertEquals(
            "Relay wss://relay-one.example rejected publication: replaced: have newer event",
            error.getSuppressed()[0].getMessage()
        );
        assertEquals(
            "Relay wss://relay-two.example rejected publication: rate-limited: slow down",
            error.getSuppressed()[1].getMessage()
        );
    }

    @Test(timeout = 5000)
    public void transportFailureIsRetainedAlongsideRejectedAck() throws Exception {
        IllegalArgumentException transport = new IllegalArgumentException("transport unavailable");
        IllegalStateException error = failure(
            List.of(AsyncTask.failed(transport), AsyncTask.completed(rejected(null, "auth-required: authenticate")))
        );
        assertEquals(2, error.getSuppressed().length);
        assertSame(transport, error.getSuppressed()[0].getCause());
        assertTrue(error.getSuppressed()[1].getMessage().contains("auth-required: authenticate"));
    }

    @Test(timeout = 5000)
    public void relayAddressOmitsCredentialsAndReasonIsBoundedToOneLine() throws Exception {
        IllegalStateException error = failure(
            List.of(
                AsyncTask.completed(
                    rejected(
                        "wss://user:password@relay.example:8443/private?token=secret#fragment",
                        "blocked:\n" + "x".repeat(600)
                    )
                )
            )
        );
        String detail = error.getSuppressed()[0].getMessage();
        assertTrue(detail.startsWith("Relay wss://relay.example:8443 rejected publication: blocked: "));
        assertFalse(detail.contains("password"));
        assertFalse(detail.contains("secret"));
        assertFalse(detail.contains("private"));
        assertFalse(detail.contains("\n"));
        assertTrue(detail.endsWith("..."));
        assertTrue(detail.length() < 600);
    }

    @Test(timeout = 5000)
    public void missingRejectionMessageStillIdentifiesTheFailure() throws Exception {
        IllegalStateException error = failure(List.of(AsyncTask.completed(rejected(null, null))));
        assertEquals("Relay unknown rejected publication: no reason supplied", error.getSuppressed()[0].getMessage());
    }

    @Test(timeout = 5000)
    public void successfulRelayStillResolvesWithoutWaitingForAnotherRelay() throws Exception {
        AtomicReference<Consumer<NostrMessageAck>> resolve = new AtomicReference<>();
        AsyncTask<NostrMessageAck> pending = AsyncTask.create((res, rej) -> resolve.set(res));
        NostrMessageAck accepted = NostrMessage.ack(null, "event", Instant.now(), null, null);
        accepted.callSuccessCallback("saved");
        List<AsyncTask<NostrMessageAck>> tasks = List.of(
            AsyncTask.completed(rejected(null, "replaced: have newer event")),
            AsyncTask.completed(accepted),
            pending
        );
        assertSame(tasks, pool(tasks).publish((SignedNostrEvent) null).await());
        assertFalse(pending.isDone());
        resolve.get().accept(accepted);
    }
}
