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
package org.ngengine.nostr4j.proto;

import java.time.Instant;
import java.util.function.BiConsumer;
import org.ngengine.nostr4j.NostrRelay;

public class NostrMessageAck {

    public enum Status {
        SUCCESS,
        FAILURE,
        PENDING,
    }

    private final String id;
    private final Instant sentAt;
    private Status status = Status.PENDING;
    private String message;
    private final NostrRelay relay;
    private final BiConsumer<NostrMessageAck, String> successCallback;
    private final BiConsumer<NostrMessageAck, String> failureCallback;

    NostrMessageAck(
        NostrRelay relay,
        String id,
        Instant sentAt,
        BiConsumer<NostrMessageAck, String> successCallback,
        BiConsumer<NostrMessageAck, String> failureCallback
    ) {
        this.id = id;
        this.sentAt = sentAt;
        this.successCallback = successCallback;
        this.failureCallback = failureCallback;
        this.relay = relay;
    }

    public void callSuccessCallback(String message) {
        this.status = Status.SUCCESS;
        this.message = message;

        if (successCallback != null) {
            successCallback.accept(this, message);
        }
    }

    public void callFailureCallback(String message) {
        this.status = Status.FAILURE;
        this.message = message;
        if (failureCallback != null) {
            failureCallback.accept(this, message);
        }
    }

    protected void setRemoteStatus(Status status, String message) {
        this.status = status;
        this.message = message;
    }

    public Status getStatus() {
        return status;
    }

    public NostrRelay get() throws Exception {
        if (status == Status.PENDING) return null;
        if (status == Status.SUCCESS) return relay;
        throw new Exception(message);
    }

    public String getMessage() {
        return message;
    }

    public NostrRelay getRelay() {
        return relay;
    }

    public String getId() {
        return id;
    }

    public Instant getSentAt() {
        return sentAt;
    }
}
