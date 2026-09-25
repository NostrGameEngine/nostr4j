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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.Test;
import org.ngengine.nostr4j.event.SignedNostrEvent;
import org.ngengine.nostr4j.pool.ackpolicy.NostrPoolAllAckPolicy;
import org.ngengine.nostr4j.pool.ackpolicy.NostrPoolAnyAckPolicy;
import org.ngengine.nostr4j.pool.ackpolicy.NostrPoolQuorumAckPolicy;
import org.ngengine.nostr4j.proto.NostrMessage;
import org.ngengine.nostr4j.proto.NostrMessageAck;
import org.ngengine.platform.AsyncTask;

public class TestNostrPoolAckPolicy {

    private static final class PendingAck {

        final AsyncTask<NostrMessageAck> task;
        Consumer<NostrMessageAck> resolve;
        Consumer<Throwable> reject;

        PendingAck() {
            task =
                AsyncTask.create((res, rej) -> {
                    resolve = res;
                    reject = rej;
                });
        }

        void settle(NostrMessageAck.Status status) {
            NostrMessageAck ack = NostrMessage.ack(null, "event", Instant.now(), null, null);
            if (status == NostrMessageAck.Status.SUCCESS) ack.callSuccessCallback("ok"); else ack.callFailureCallback(
                "rejected"
            );
            resolve.accept(ack);
        }
    }

    private static NostrPool pool(List<AsyncTask<NostrMessageAck>> tasks) {
        return new NostrPool() {
            @Override
            protected List<AsyncTask<NostrMessageAck>> sendMessage(NostrMessage message) {
                return tasks;
            }
        };
    }

    @Test
    public void anySucceedsWithoutWaitingForOtherRelays() throws Exception {
        PendingAck first = new PendingAck();
        PendingAck second = new PendingAck();
        PendingAck third = new PendingAck();
        List<AsyncTask<NostrMessageAck>> tasks = List.of(first.task, second.task, third.task);
        AtomicInteger checks = new AtomicInteger();
        AsyncTask<List<AsyncTask<NostrMessageAck>>> result = pool(tasks)
            .publish(
                (SignedNostrEvent) null,
                acks -> {
                    checks.incrementAndGet();
                    return NostrPoolAnyAckPolicy.get().apply(acks);
                }
            );

        first.settle(NostrMessageAck.Status.FAILURE);
        assertFalse(result.isDone());
        second.settle(NostrMessageAck.Status.SUCCESS);
        assertSame(tasks, result.await());
        assertFalse(third.task.isDone());
        third.settle(NostrMessageAck.Status.FAILURE);
        assertEquals(2, checks.get());
    }

    @Test
    public void allWaitsForEachDistinctSettlement() throws Exception {
        PendingAck first = new PendingAck();
        PendingAck second = new PendingAck();
        List<AsyncTask<NostrMessageAck>> tasks = List.of(first.task, second.task);
        AsyncTask<List<AsyncTask<NostrMessageAck>>> result = pool(tasks)
            .publish((SignedNostrEvent) null, NostrPoolAllAckPolicy.get());

        first.settle(NostrMessageAck.Status.SUCCESS);
        assertFalse(result.isDone());
        second.settle(NostrMessageAck.Status.SUCCESS);
        assertSame(tasks, result.await());
    }

    @Test
    public void quorumWaitsUntilEnoughAcksSucceed() throws Exception {
        PendingAck first = new PendingAck();
        PendingAck second = new PendingAck();
        PendingAck third = new PendingAck();
        List<AsyncTask<NostrMessageAck>> tasks = List.of(first.task, second.task, third.task);
        AsyncTask<List<AsyncTask<NostrMessageAck>>> result = pool(tasks)
            .publish((SignedNostrEvent) null, NostrPoolQuorumAckPolicy.get());

        first.reject.accept(new IllegalStateException("relay unavailable"));
        assertFalse(result.isDone());
        second.settle(NostrMessageAck.Status.SUCCESS);
        assertFalse(result.isDone());
        third.settle(NostrMessageAck.Status.SUCCESS);
        assertSame(tasks, result.await());
    }

    @Test
    public void rejectsAfterLastTaskWhenPolicyCannotSucceed() throws Exception {
        PendingAck first = new PendingAck();
        PendingAck second = new PendingAck();
        AsyncTask<List<AsyncTask<NostrMessageAck>>> result = pool(List.of(first.task, second.task))
            .publish((SignedNostrEvent) null, NostrPoolAnyAckPolicy.get());

        first.reject.accept(new IllegalStateException("relay unavailable"));
        assertFalse(result.isDone());
        second.settle(NostrMessageAck.Status.FAILURE);
        assertTrue(result.isFailed());
        try {
            result.await();
            fail("Expected the ACK policy to fail");
        } catch (IllegalStateException expected) {
            assertEquals("Failed to achieve required acknowledgements", expected.getMessage());
        }
    }

    @Test
    public void resolvesWithoutRelayTasks() throws Exception {
        AsyncTask<List<AsyncTask<NostrMessageAck>>> result = pool(List.of())
            .publish((SignedNostrEvent) null, NostrPoolAnyAckPolicy.get());
        assertTrue(result.await().isEmpty());
    }

    @Test
    public void policyChecksCanRunConcurrently() throws Exception {
        PendingAck first = new PendingAck();
        PendingAck second = new PendingAck();
        List<AsyncTask<NostrMessageAck>> tasks = List.of(first.task, second.task);
        CyclicBarrier barrier = new CyclicBarrier(2);
        AtomicInteger checks = new AtomicInteger();
        AsyncTask<List<AsyncTask<NostrMessageAck>>> result = pool(tasks)
            .publish(
                (SignedNostrEvent) null,
                acks -> {
                    checks.incrementAndGet();
                    try {
                        barrier.await(5, TimeUnit.SECONDS);
                    } catch (Exception error) {
                        throw new IllegalStateException(error);
                    }
                    return NostrMessageAck.Status.SUCCESS;
                }
            );

        Thread a = new Thread(() -> first.settle(NostrMessageAck.Status.SUCCESS));
        Thread b = new Thread(() -> second.settle(NostrMessageAck.Status.SUCCESS));
        a.start();
        b.start();
        a.join(6_000);
        b.join(6_000);
        assertFalse(a.isAlive());
        assertFalse(b.isAlive());
        assertEquals(2, checks.get());
        assertSame(tasks, result.await());
    }
}
