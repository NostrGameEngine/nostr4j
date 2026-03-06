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

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import org.ngengine.nostr4j.event.SignedNostrEvent;
import org.ngengine.platform.AsyncTask;
import org.ngengine.platform.NGEPlatform;

public class Propagator {

    public static void main(String[] args) throws Exception {
        String relayListUrl = "https://raw.githubusercontent.com/sesseor/nostr-relays-list/refs/heads/main/relays.txt";
        List<String> sourceRelay = Arrays.asList(args[0]);
        String sourceEvent = args[1];

        NostrPool pool = new NostrPool();
        for (String relay : sourceRelay) {
            pool.connectRelay(new NostrRelay(relay));
        }

        List<SignedNostrEvent> events = pool.fetch(new NostrFilter().withId(sourceEvent), 1, null).await();
        SignedNostrEvent event = events.get(0);
        if (event == null) {
            System.out.println("Event not found");
            return;
        }

        pool.close();

        String relayList = NGEPlatform.get().httpGet(relayListUrl, Duration.ofSeconds(60), null).await();
        String[] relays = relayList.split("\n");
        for (String relay : relays) {
            try {
                System.out.println("Publishing to " + relay);
                relay = relay.trim();
                if (relay.isEmpty() || relay.startsWith("#")) {
                    continue;
                }
                pool = new NostrPool();
                pool.ensureRelay(relay).await();
                AsyncTask.all(pool.publish(event)).await();
                System.out.println("Published to " + relay);
                pool.close();
            } catch (Exception e) {}
        }
    }
}
