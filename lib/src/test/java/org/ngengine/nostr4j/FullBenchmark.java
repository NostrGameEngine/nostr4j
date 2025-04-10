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

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.ngengine.nostr4j.event.UnsignedNostrEvent;
import org.ngengine.nostr4j.event.tracker.NaiveEventTracker;
import org.ngengine.nostr4j.platform.AsyncTask;
import org.ngengine.nostr4j.signer.NostrKeyPairSigner;
import org.ngengine.nostr4j.transport.NostrMessageAck;

// docker run -p8087:8080 --rm -it -v./src/test/resources/strfry.conf:/etc/strfry.conf -vtest-nostr4j-strfry-db:/strfry-db ghcr.io/hoytech/strfry:latest

public class FullBenchmark {

    private Logger rootLogger = TestLogger.getRoot(Level.INFO);
    private static final int EVENTS = 200;

    AtomicInteger read = new AtomicInteger(0);
    private String content = "";

    public FullBenchmark() throws Exception {
        for (int i = 0; i < 512; i++) {
            content += "a";
        }
    }

    public void run() throws Exception {
        read.set(0);

        long initStarted = System.currentTimeMillis();
        NostrPool writer = new NostrPool();
        writer.connectRelay(new NostrRelay("ws://127.0.0.1:8087"));
        NostrPool reader = new NostrPool();
        reader.connectRelay(new NostrRelay("ws://127.0.0.1:8087"));

        String testId = System.currentTimeMillis() + "-" + Math.random();
        NostrKeyPairSigner signer = NostrKeyPairSigner.generate();
        List<String> track = new ArrayList<>();

        System.out.println("Init time: " + (System.currentTimeMillis() - initStarted) + " ms");

        long sendStarted = System.currentTimeMillis();

        List<AsyncTask<List<NostrMessageAck>>> sent = new ArrayList<>();

        for (int i = 0; i < EVENTS; i++) {
            UnsignedNostrEvent event = new UnsignedNostrEvent();
            event.setKind(1);
            event.setTag("t", testId);
            event.setContent(content.substring(0, i));
            event.setCreatedAt(Instant.now());
            event.setTag("eventId", i + "");
            signer
                .sign(event)
                .then(signed -> {
                    sent.add(writer.send(signed));
                    return null;
                });
            // System.out.println("Sending "+i+"/"+EVENTS);

            // Thread.sleep(10);
        }

        System.out.println("Send time: " + (System.currentTimeMillis() - sendStarted) + " ms");

        long receiveStarted = System.currentTimeMillis();
        NostrSubscription sub = reader.subscribe(new NostrFilter().kind(1).tag("t", testId), NaiveEventTracker.class);
        sub.listenEvent((event, stored) -> {
            String i = event.getTag("eventId")[1];
            if (track.contains(i)) {
                assert false : "Duplicate event: " + i;
            } else {
                track.add(i);
            }

            if (read.incrementAndGet() == EVENTS) {
                System.out.println("Receive time: " + (System.currentTimeMillis() - receiveStarted) + " ms");
                System.out.println("Total time: " + (System.currentTimeMillis() - initStarted) + " ms");
                sub.close();
            }
        });

        sub.open();
        while (read.get() < EVENTS) {
            Thread.sleep(100);
        }
    }

    public static void main(String[] args) throws Exception {
        FullBenchmark fb = new FullBenchmark();
        for (int i = 0; i < 10; i++) {
            fb.run();
        }
    }
}
