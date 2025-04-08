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
package org.ngengine.nostr4j.listeners.sub;

import org.ngengine.nostr4j.event.SignedNostrEvent;

/**
 * A listener interface for receiving events through a Nostr subscription.
 * <p>
 * This interface defines the callback method that is invoked when an event
 * matching the subscription's filter is received from a relay.
 * </p>
 * <p>
 * Example usage:
 * </p>
 * <pre>
 * subscription.listenEvent((event, stored) -> {
 *     System.out.println("Received event: " + event.getContent());
 *     if (stored) {
 *         System.out.println("This is a historical event from the relay's database");
 *     } else {
 *         System.out.println("This is a new, real-time event");
 *     }
 * });
 * </pre>
 */
public interface NostrSubEventListener extends NostrSubListener {
    /**
     * Called when an event matching the subscription's filter is received.
     * <p>
     * This method is invoked for each event that matches the subscription's filter
     * criteria and passes any duplicate detection checks implemented by the
     * subscription's event tracker.
     * </p>
     *
     * @param event The signed Nostr event that was received
     * @param stored Whether this event came from the relay's stored history (true)
     *               or is a new event (false)
     */
    void onSubEvent(SignedNostrEvent event, boolean stored);
}
