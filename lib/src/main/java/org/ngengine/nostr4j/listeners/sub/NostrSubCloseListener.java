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

import java.util.List;

/**
 * A listener interface for subscription closure events.
 * <p>
 * This interface defines a callback method that is invoked when a subscription
 * is closed, either by the client or by the relay. The listener receives information
 * about why the subscription was closed.
 * </p>
 * <p>
 * Example usage:
 * </p>
 * <pre>
 * subscription.listenClose(reasons -> {
 *     System.out.println("Subscription closed for the following reasons:");
 *     for (String reason : reasons) {
 *         System.out.println(" - " + reason);
 *     }
 * });
 * </pre>
 */
public interface NostrSubCloseListener extends NostrSubListener {
    /**
     * Called when a subscription is closed.
     * <p>
     * This method is invoked when a subscription is closed, providing a list
     * of reasons why the closure occurred. Multiple reasons may be provided if
     * the subscription was closed by multiple relays or had multiple closure events.
     * </p>
     *
     * @param reasons A list of string reasons explaining why the subscription was closed
     */
    void onSubClose(List<String> reasons);
}
