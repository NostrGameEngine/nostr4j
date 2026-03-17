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
package org.ngengine.nostr4j;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.ngengine.nostr4j.event.tracker.PassthroughEventTracker;
import org.ngengine.nostr4j.proto.NostrMessageAck;
import org.ngengine.platform.AsyncTask;

public class TestNostrSubscription {

    private NostrSubscription newSubscription() {
        return new NostrSubscription(
            "sub-id",
            List.of(new NostrFilter().withKind(1)),
            new PassthroughEventTracker(),
            sub -> Collections.<AsyncTask<NostrMessageAck>>emptyList(),
            (sub, closeMessage) -> Collections.<AsyncTask<NostrMessageAck>>emptyList()
        );
    }

    @Test
    public void testCloseListenersReceiveImmutableReasonSnapshot() throws Exception {
        NostrSubscription sub = newSubscription();
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<List<String>> received = new AtomicReference<>();
        AtomicBoolean immutable = new AtomicBoolean(false);

        sub.addCloseListener((ignored, reasons) -> {
            received.set(reasons);
            try {
                reasons.add("mutated");
                fail("Close reasons snapshot must be immutable");
            } catch (UnsupportedOperationException expected) {
                immutable.set(true);
            } finally {
                latch.countDown();
            }
        });

        sub.open();
        sub.registerClosure("closed by relay");
        sub.callCloseListeners();

        assertTrue("Timed out waiting for close listener", latch.await(2, TimeUnit.SECONDS));
        assertTrue(immutable.get());
        assertEquals(List.of("closed by relay"), received.get());
    }

    @Test
    public void testCloseClearsExecutorState() {
        NostrSubscription sub = newSubscription();

        sub.open();
        assertNotNull(sub.getExecutor());

        sub.close();

        assertFalse(sub.isOpened());
        try {
            sub.getExecutor();
            fail("Expected executor to be unavailable after close");
        } catch (IllegalStateException expected) {}
    }
}
