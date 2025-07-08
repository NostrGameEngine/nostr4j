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
package org.ngengine.nostr4j.nip39;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import org.ngengine.nostr4j.event.NostrEvent;
import org.ngengine.nostr4j.event.NostrEvent.TagValue;
import org.ngengine.nostr4j.event.UnsignedNostrEvent;
import org.ngengine.nostr4j.nip01.Nip01UserMetadata;

public class Nip39ExternalIdentities extends Nip01UserMetadata {

    private transient List<ExternalIdentity> externalIdentities;

    public Nip39ExternalIdentities(Nip01UserMetadata nip01) {
        super(nip01.getSourceEvent());
    }

    public Nip39ExternalIdentities(NostrEvent source) {
        super(source);
    }

    public List<ExternalIdentity> getExternalIdentities() {
        if (externalIdentities == null) {
            List<ExternalIdentity> externalIdentities = new ArrayList<>();
            Collection<TagValue> is = getSourceEvent().getTag("i");
            for (TagValue t : is) {
                String platformAndId[] = t.get(0).split(":", 1);
                List<String> proofs = new ArrayList<>();
                for (int i = 1; i < t.size(); i++) {
                    proofs.add(t.get(i));
                }
                ExternalIdentity identity = new GenericIdentity(platformAndId[0], platformAndId[1], proofs);
                externalIdentities.add(identity);
            }
            this.externalIdentities = externalIdentities;
        }
        return externalIdentities;
    }

    public void setExternalIdentity(String platform, String identity, List<String> proofs) {
        setExternalIdentity(new GenericIdentity(platform, identity, proofs));
    }

    public void setExternalIdentity(ExternalIdentity identity) {
        if (externalIdentities == null) {
            externalIdentities = new ArrayList<>();
        } else {
            removeExternalIdentity(identity.getPlatform());
        }
        externalIdentities.add(identity);
    }

    public void removeExternalIdentity(ExternalIdentity identity) {
        if (externalIdentities == null) {
            return;
        }
        externalIdentities.remove(identity);
    }

    public void removeExternalIdentity(String platform) {
        removeExternalIdentity(platform, null);
    }

    public void removeExternalIdentity(String platform, String identity) {
        if (externalIdentities == null) {
            return;
        }
        Iterator<ExternalIdentity> iterator = externalIdentities.iterator();
        while (iterator.hasNext()) {
            ExternalIdentity i = iterator.next();
            if (i.getPlatform().equals(platform) && (identity == null || i.getIdentity().equals(identity))) {
                iterator.remove();
            }
        }
    }

    public ExternalIdentity getExternalIdentity(String platform, String identity) {
        if (externalIdentities == null) {
            return null;
        }
        for (ExternalIdentity i : externalIdentities) {
            if (i.getPlatform().equals(platform) && (identity == null || i.getIdentity().equals(identity))) {
                return i;
            }
        }
        return null;
    }

    public ExternalIdentity getExternalIdentity(String platform) {
        return getExternalIdentity(platform, null);
    }

    public void clearExternalIdentities() {
        getExternalIdentities().clear();
    }

    @Override
    public UnsignedNostrEvent toUpdateEvent() {
        UnsignedNostrEvent event = super.toUpdateEvent();
        for (ExternalIdentity identity : getExternalIdentities()) {
            List<String> values = new ArrayList<>();
            values.add(identity.getPlatform() + ":" + identity.getIdentity());
            for (String proof : identity.getProof()) {
                values.add(proof);
            }
            event.withTag("i", values);
        }
        return event;
    }
}
