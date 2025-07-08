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
 * Consider a message acknowledged when all acks are received.
 * This is the strictest policy, ensuring that all relays have acknowledged the message.
 * If any ack is pending or fails, the overall status is pending or failure.
 */
public class NostrPoolAllAckPolicy implements NostrPoolAckPolicy {

    private static final NostrPoolAllAckPolicy INSTANCE = new NostrPoolAllAckPolicy();

    public static NostrPoolAckPolicy get() {
        return INSTANCE;
    }

    private static final Logger logger = Logger.getLogger(NostrPoolAllAckPolicy.class.getName());

    @Override
    public Status apply(List<AsyncTask<NostrMessageAck>> t) {
        for (AsyncTask<NostrMessageAck> ackTask : t) {
            try {
                // if one is pending, we wait
                if (!ackTask.isDone()) {
                    logger.finer("Ack results - Still waiting for task: " + ackTask);
                    return Status.PENDING;
                }

                // if one failed, we fail
                if (ackTask.isFailed()) {
                    logger.finer("Ack results - Task failed: " + ackTask);
                    return Status.FAILURE;
                }

                NostrMessageAck ack = ackTask.await();

                // if one is pending, we wait
                if (ack.getStatus() == Status.PENDING) {
                    logger.finer("Ack results - Still waiting for ack: " + ack);
                    return Status.PENDING;
                }

                // if one failed, we fail
                if (ack.getStatus() == Status.FAILURE) {
                    logger.finer("Ack results - Ack failed: " + ack);
                    return Status.FAILURE;
                }
            } catch (Exception e) {
                logger.warning("Error processing ack task: " + e.getMessage());
                return Status.FAILURE; // if we catch an exception, we consider it a failure
            }
        }

        return Status.SUCCESS; // if we reach here, all acks are successful
    }
}
