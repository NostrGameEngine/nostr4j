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
package org.ngengine.nostr4j.cliclient;

import java.util.logging.Level;
import java.util.logging.Logger;
import org.ngengine.nostr4j.NostrFilter;
import org.ngengine.nostr4j.NostrPool;
import org.ngengine.nostr4j.NostrRelay;
import org.ngengine.nostr4j.NostrSubscription;
import org.ngengine.nostr4j.TestLogger;
import org.ngengine.nostr4j.event.tracker.FailOnDoubleTracker;

public class NostrCli {

    private static final Logger rootLogger = TestLogger.getRoot(Level.FINEST);

    public static void main(String _a[]) throws Exception {
        // initialize pool
        NostrPool pool = new NostrPool();
        // add relays
        pool.connectRelay(new NostrRelay("ws://127.0.0.1:8087"));

        // listen for notices
        pool.addNoticeListener((relay, msg, error) -> {
            if (error != null) {
                System.out.println("Error: " + msg + " from relay: " + relay);
            } else {
                System.out.println("Notice: " + msg + " from relay: " + relay);
            }
        });

        // initialize subscription
        NostrSubscription sub = pool.subscribe(new NostrFilter().withKind(1).limit(3), ()->new FailOnDoubleTracker());

        // append listeners
        sub.addCloseListener((s, reason )-> {
            System.out.println("Subscription closed: reason: " + reason);
        });

        sub.addEventListener((s, event, stored) -> {
            System.out.println("Event: " + event + " stored: " + stored);
        });

        sub.addEoseListener((s, relay,all) -> {
            System.out.println("Eose: " + all);
        });

        // start sub
        sub.open().await();

        // System.out.println("started: " + sub);
        rootLogger.info("started: " + sub);

        // sleep for ever
        while (true) {
            try {
                Thread.sleep(Long.MAX_VALUE);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }
}
