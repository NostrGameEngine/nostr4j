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
 * Consider a message acknowledged if at least one relay acknowledges it.
 * This is a more lenient policy, allowing for partial acknowledgments.
 * If any ack is successful, the overall status is success.
 * If all acks are pending or failed, the overall status is pending or failure.
 */
public class NostrPoolAnyAckPolicy implements NostrPoolAckPolicy {

    private static final NostrPoolAnyAckPolicy INSTANCE = new NostrPoolAnyAckPolicy();

    public static NostrPoolAckPolicy get() {
        return INSTANCE;
    }

    private static final Logger logger = Logger.getLogger(NostrPoolAnyAckPolicy.class.getName());

    @Override
    public Status apply(List<AsyncTask<NostrMessageAck>> t) {
        boolean atLeastOneNonFailed = false;
        boolean atLeastOneSuccess = false;
        for (AsyncTask<NostrMessageAck> ackTask : t) {
            try {
                // still waiting
                if (!ackTask.isDone()) {
                    atLeastOneNonFailed = true;
                    continue;
                }

                // failed
                if (ackTask.isFailed()) {
                    continue;
                }

                NostrMessageAck ack = ackTask.await();
                // waiting or success
                if (ack.getStatus() != Status.FAILURE) {
                    atLeastOneNonFailed = true;
                }

                // success
                if (ack.getStatus() == Status.SUCCESS) {
                    atLeastOneSuccess = true;
                    break;
                }
            } catch (Exception e) {
                logger.warning("Error processing ack task: " + e.getMessage());
            }
        }
        logger.finer(
            "Ack results - At least one non-failed: " + atLeastOneNonFailed + ", At least one success: " + atLeastOneSuccess
        );
        if (atLeastOneSuccess) {
            return Status.SUCCESS;
        } else if (atLeastOneNonFailed) {
            return Status.PENDING;
        } else {
            return Status.FAILURE;
        }
    }
}
