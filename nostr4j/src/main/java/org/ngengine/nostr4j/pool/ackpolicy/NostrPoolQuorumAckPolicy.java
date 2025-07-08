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
package org.ngengine.nostr4j.pool.ackpolicy;

import java.util.List;
import java.util.logging.Logger;
import org.ngengine.nostr4j.proto.NostrMessageAck;
import org.ngengine.nostr4j.proto.NostrMessageAck.Status;
import org.ngengine.platform.AsyncTask;

/**
 * Consider a message acknowledged if the majority of relays acknowledge it.
 * This policy requires a quorum of acknowledgments to consider the message successfully acknowledged.
 * If more than half of the acks are successful, the overall status is success.
 * If more than half of the acks fail, the overall status is failure.
 * If neither condition is met, the overall status is pending.
 */
public class NostrPoolQuorumAckPolicy implements NostrPoolAckPolicy {

    private static final NostrPoolQuorumAckPolicy INSTANCE = new NostrPoolQuorumAckPolicy();

    public static NostrPoolAckPolicy get() {
        return INSTANCE;
    }

    private static final Logger logger = Logger.getLogger(NostrPoolQuorumAckPolicy.class.getName());

    @Override
    public Status apply(List<AsyncTask<NostrMessageAck>> t) {
        int total = t.size();
        int success = 0;
        int failure = 0;
        int pending = 0;
        for (AsyncTask<NostrMessageAck> ackTask : t) {
            try {
                if (!ackTask.isDone()) {
                    pending++;
                    continue;
                }
                if (ackTask.isFailed()) {
                    failure++;
                    continue;
                }
                NostrMessageAck ack = ackTask.await();
                if (ack.getStatus() == Status.SUCCESS) {
                    success++;
                } else if (ack.getStatus() == Status.FAILURE) {
                    failure++;
                } else {
                    pending++;
                }
            } catch (Exception e) {
                failure++;
                logger.warning("Error processing ack task: " + e.getMessage());
            }
        }

        logger.finer(
            "Ack results - Total: " + total + ", Success: " + success + ", Failure: " + failure + ", Pending: " + pending
        );
        if (success > total / 2) {
            return Status.SUCCESS;
        } else if (failure > total / 2) {
            return Status.FAILURE;
        } else {
            return Status.PENDING;
        }
    }
}
