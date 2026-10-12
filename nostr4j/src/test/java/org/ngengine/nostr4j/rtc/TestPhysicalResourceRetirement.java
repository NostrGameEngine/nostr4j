/**
 * BSD 3-Clause License
 *
 * Copyright (c) 2026, Riccardo Balbo
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

package org.ngengine.nostr4j.rtc;

import static org.junit.Assert.*;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.Test;
import org.ngengine.platform.AsyncExecutor;
import org.ngengine.platform.AsyncTask;
import org.ngengine.platform.NGEPlatform;

/**
 * Failure, async completion and at-most-once provider disposal contracts.
 */
public class TestPhysicalResourceRetirement {

    @Test
    public void synchronousFailureDoesNotSkipOthersOrPermitBlindRetry() {
        AtomicInteger failing = new AtomicInteger(), healthy = new AtomicInteger(), settled = new AtomicInteger();
        IllegalStateException first = new IllegalStateException("first provider failed");
        IllegalArgumentException second = new IllegalArgumentException("second provider failed");
        PhysicalResourceRetirement retirement = new PhysicalResourceRetirement(
            List.of(
                () -> {
                    failing.incrementAndGet();
                    throw first;
                },
                () -> {
                    healthy.incrementAndGet();
                    return AsyncTask.completed(null);
                },
                () -> AsyncTask.failed(second)
            ),
            result -> settled.incrementAndGet()
        );
        retirement.start();
        retirement.start();
        assertEquals(1, failing.get());
        assertEquals(1, healthy.get());
        assertEquals(1, settled.get());
        assertTrue(retirement.isSettled());
        assertFalse(retirement.isSuccessful());
        assertSame(first, retirement.getFailure());
        assertArrayEquals(new Throwable[] { second }, first.getSuppressed());
    }

    @Test
    public void unresolvedNativeCloseCannotReportSuccessEarly() {
        AtomicReference<Consumer<Void>> resolve = new AtomicReference<>();
        AsyncTask<Void> pending = AsyncTask.create((accept, reject) -> resolve.set(accept));
        AtomicInteger starts = new AtomicInteger(), settled = new AtomicInteger();
        PhysicalResourceRetirement retirement = new PhysicalResourceRetirement(
            List.of(() -> {
                starts.incrementAndGet();
                return pending;
            }),
            result -> settled.incrementAndGet()
        );
        retirement.start();
        retirement.start();
        assertFalse(retirement.isSettled());
        assertFalse(retirement.isSuccessful());
        assertEquals(0, settled.get());
        resolve.get().accept(null);
        assertTrue(retirement.isSuccessful());
        assertEquals(1, settled.get());
        assertEquals(1, starts.get());
    }

    @Test(timeout = 5000L)
    public void closeCompletionSurvivesShutdownOfItsTaskExecutor() throws Exception {
        AsyncExecutor executor = NGEPlatform.get().newAsyncExecutor("retirement-provider-executor");
        AtomicReference<Consumer<Void>> resolve = new AtomicReference<>();
        CountDownLatch registered = new CountDownLatch(1), settled = new CountDownLatch(1);
        AsyncTask<Void> pending = AsyncTask.create(
            (accept, reject) -> {
                resolve.set(accept);
                registered.countDown();
            },
            executor
        );
        try {
            assertTrue(registered.await(2L, TimeUnit.SECONDS));
            executor.close();
            PhysicalResourceRetirement retirement = new PhysicalResourceRetirement(
                List.of(() -> pending),
                result -> settled.countDown()
            );
            retirement.start();
            assertFalse(retirement.isSettled());
            resolve.get().accept(null);
            assertTrue(settled.await(2L, TimeUnit.SECONDS));
            assertTrue(retirement.isSuccessful());
        } finally {
            executor.close();
        }
    }

    @Test
    public void providerAndSettlementCallbacksDoNotInheritTheRetirementMonitor() {
        AtomicReference<PhysicalResourceRetirement> owner = new AtomicReference<>();
        PhysicalResourceRetirement retirement = new PhysicalResourceRetirement(
            List.of(() -> {
                assertFalse(Thread.holdsLock(owner.get()));
                return AsyncTask.completed(null);
            }),
            result -> {
                assertFalse(Thread.holdsLock(result));
                assertTrue(result.isSuccessful());
            }
        );
        owner.set(retirement);
        retirement.start();
        assertTrue(retirement.isSuccessful());
    }
}
