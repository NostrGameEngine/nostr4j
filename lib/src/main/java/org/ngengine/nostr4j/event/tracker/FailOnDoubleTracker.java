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
package org.ngengine.nostr4j.event.tracker;

import java.util.HashMap;
import org.ngengine.nostr4j.NostrSubscription;
import org.ngengine.nostr4j.event.SignedNostrEvent;

public class FailOnDoubleTracker implements EventTracker {

    public HashMap<String, StackTraceElement[]> seenEvents = new HashMap<>();

    public FailOnDoubleTracker() {}

    @Override
    public boolean seen(SignedNostrEvent event) {
        if (seenEvents.putIfAbsent(event.getIdentifier().id, new Exception().getStackTrace()) == null) {
            return false;
        } else {
            throw new RuntimeException(
                "Events was already seen: " +
                event.getIdentifier().id +
                "\n" +
                "First seen stacktrace: " +
                stackTraceToString(seenEvents.get(event.getIdentifier().id)) +
                "\n" +
                "Event: " +
                event.toString()
            );
        }
    }

    private String stackTraceToString(StackTraceElement[] trace) {
        StringBuilder sb = new StringBuilder();

        for (StackTraceElement traceElement : trace) sb.append("\n\tat " + traceElement);
        return sb.toString();
    }

    public void clear() {
        seenEvents.clear();
    }

    @Override
    public void tuneFor(NostrSubscription sub) {}
}
