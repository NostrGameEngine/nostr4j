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
package org.ngengine.nostr4j.platform.teavm;

import org.ngengine.nostr4j.utils.NostrUtils;
import org.teavm.jso.JSExportClasses;

@JSExportClasses(
    {
        org.ngengine.nostr4j.keypair.NostrKeyPair.class,
        org.ngengine.nostr4j.keypair.NostrPublicKey.class,
        org.ngengine.nostr4j.NostrFilter.class,
        org.ngengine.nostr4j.NostrRelay.class,
        org.ngengine.nostr4j.nip50.NostrSearchFilter.class,
        org.ngengine.nostr4j.utils.ExponentialBackoff.class,
        org.ngengine.nostr4j.utils.ScheduledAction.class,
        org.ngengine.nostr4j.utils.ByteBufferList.class,
        org.ngengine.nostr4j.event.SignedNostrEvent.class,
        org.ngengine.nostr4j.event.tracker.ForwardSlidingWindowEventTracker.class,
        org.ngengine.nostr4j.event.tracker.NaiveEventTracker.class,
        org.ngengine.nostr4j.event.tracker.PassthroughEventTracker.class,
        org.ngengine.nostr4j.platform.jvm.JVMThreadedPlatform.class,
        org.ngengine.nostr4j.platform.jvm.WebsocketTransport.class,
        org.ngengine.nostr4j.NostrPool.class,
        org.ngengine.nostr4j.signer.NostrKeyPairSigner.class,
        org.ngengine.nostr4j.nip01.Nip01MetadataFilter.class,
        org.ngengine.nostr4j.nip01.Nip01Metadata.class,
        org.ngengine.nostr4j.nip01.Nip01MetadataListener.class,
    }
)
public class TeaVMModule {
    static {
        NostrUtils.setPlatform(new TeaVMPlatform());
    }
}
