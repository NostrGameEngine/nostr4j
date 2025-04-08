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

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.ngengine.nostr4j.event.SignedNostrEvent;
import org.ngengine.nostr4j.event.UnsignedNostrEvent;
import org.ngengine.nostr4j.event.tracker.PassthroughEventTracker;
import org.ngengine.nostr4j.platform.jvm.JVMThreadedPlatform;
import org.ngengine.nostr4j.signer.NostrKeyPairSigner;
import org.ngengine.nostr4j.transport.NostrMessage;
import org.ngengine.nostr4j.utils.NostrUtils;

public class Benchmarks {

    private static final int EVENTS = 200;

    public Collection<List<Object>> generateMessages(String subId)
        throws Exception {
        Collection<List<Object>> messages = new ArrayList<>();
        String baseContent = "";
        for (int i = 0; i < EVENTS; i++) {
            baseContent += "a";
        }
        NostrKeyPairSigner signer = NostrKeyPairSigner.generate();
        for (int i = 0; i < EVENTS; i++) {
            UnsignedNostrEvent event = new UnsignedNostrEvent();
            for (int t = 0; t < i; t++) {
                event.setTag("t", "nada");
            }
            event.setKind(1);
            event.setContent(baseContent.substring(0, EVENTS - i));
            SignedNostrEvent signed = signer.sign(event);
            List<Object> message = new ArrayList<>();
            message.addAll(NostrMessage.toSerial(signed));
            message.add(1, subId);
            messages.add(message);
        }
        return messages;
    }

    Collection<List<Object>> messages;
    NostrSubscription sub;
    NostrPool pool;
    NostrRelay relay;

    public Benchmarks(boolean trusted, boolean threaded) throws Exception {
        if (threaded) NostrUtils.setPlatform(new JVMThreadedPlatform());
        pool = new NostrPool();

        relay = new NostrRelay("ws://127.0.0.1:8087");
        relay.setVerifyEvents(!trusted);
        relay.setAsyncEventsVerification(false);
        pool.ensureRelay(relay);
        NostrSubscription sub = pool.subscribe(
            new NostrFilter(),
            PassthroughEventTracker.class
        );
        messages = generateMessages(sub.getSubId());
    }

    public String run(int iterations) throws Exception {
        long sum = 0;
        long min = Long.MAX_VALUE;
        // warmup
        for (List<Object> message : messages) {
            pool.onRelayMessage(relay, SignedNostrEvent.parse(message));
        }

        for (int i = 0; i < iterations; i++) {
            long t = System.nanoTime();
            for (List<Object> message : messages) {
                pool.onRelayMessage(relay, SignedNostrEvent.parse(message));
            }
            long iterationSum = System.nanoTime() - t;
            sum += iterationSum;
            if (iterationSum < min) {
                min = iterationSum;
            }
        }

        sum /= iterations;
        return (
            "avg " +
            ((double) sum / 1000000.) +
            "ms min " +
            ((double) min / 1000000.) +
            "ms"
        );
    }

    //TODO: rewrite this as it doesn't work now that the relay is non blocking
    public static void main(String[] args) throws Exception {
        System.out.println(
            "Java version: " + System.getProperty("java.version")
        );
        Benchmarks benchmark = new Benchmarks(false, false);
        String t;

        benchmark = new Benchmarks(false, false);
        t = benchmark.run(6);
        System.out.println("Time: " + (t));

        benchmark = new Benchmarks(false, true);
        t = benchmark.run(6);
        System.out.println("Time (threaded): " + (t));

        benchmark = new Benchmarks(true, false);
        t = benchmark.run(6);
        System.out.println("Time (trusted): " + (t));
    }
}
