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

import org.ngengine.nostr4j.NostrRelay;

/**
 * A listener interface for receiving End-of-Stored-Events (EOSE) notifications
 * from Nostr relays.
 * <p>
 * This interface defines a callback method that is invoked when a relay signals
 * that it has finished sending all stored events that match the subscription's
 * filter.
 * After EOSE is received, any subsequent events for the subscription will be
 * newly
 * published events rather than historical data.
 * </p>
 * <p>
 * Example usage:
 * </p>
 *
 * <pre>{@code
 * subscription.listenEose(everywhere -> {
 *     if (everywhere) {
 *         System.out.println("All relays have sent their stored events");
 *     } else {
 *         System.out.println("Some relay has sent its stored events");
 *     }
 * });}
 * </pre>
 */
public interface NostrSubEoseListener extends NostrSubListener {
    /**
     * Called when a relay signals that it has finished sending all stored events.
     * <p>
     * This method is invoked when an EOSE (End of Stored Events) message is received
     * from a relay, indicating that the relay has sent all historical events matching
     * the subscription's filter. After this point, only new events will be received.
     * </p>
     *
     * @param everyWhere If true, all connected relays have sent EOSE for this subscription.
     *                   If false, at least one relay has sent EOSE, but not all.
     */
    void onSubEose(NostrRelay relay, boolean everyWhere);
}
