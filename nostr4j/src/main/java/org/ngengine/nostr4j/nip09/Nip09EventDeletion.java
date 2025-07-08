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

package org.ngengine.nostr4j.nip09;

import org.ngengine.nostr4j.event.NostrEvent;
import org.ngengine.nostr4j.event.SignedNostrEvent;
import org.ngengine.nostr4j.event.UnsignedNostrEvent;

public class Nip09EventDeletion {

    public static final int EVENT_DELETION_KIND = 5;

    public static UnsignedNostrEvent createDeletionEvent(String reason, SignedNostrEvent... events) {
        UnsignedNostrEvent event = new UnsignedNostrEvent();
        event.withKind(EVENT_DELETION_KIND);
        event.withContent(reason == null ? "" : reason);
        for (SignedNostrEvent e : events) {
            NostrEvent.Coordinates c = e.getCoordinates();
            event.withTag(c.type(), c.coords());
            event.withTag("k", "" + e.getKind());
        }
        return event;
    }

    public static UnsignedNostrEvent createDeletionEvent(String reason, NostrEvent.Coordinates... coordinates) {
        UnsignedNostrEvent event = new UnsignedNostrEvent();
        event.withKind(5);
        event.withContent(reason == null ? "" : reason);
        for (NostrEvent.Coordinates c : coordinates) {
            event.withTag(c.type(), c.coords());
            event.withTag("k", c.kind());
        }
        return event;
    }
}
