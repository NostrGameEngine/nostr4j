/**
 * BSD 3-Clause License
 *
 * Copyright (c) 2026, Riccardo Balbo
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

package org.ngengine.nostr4j.rtc;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.logging.Logger;
import org.ngengine.platform.AsyncTask;

/**
 * Owns one captured physical disposal. Every operation is started once, outside
 * lifecycle monitors. An uncertain failure retains its operation/handle and is
 * quarantined rather than claiming that the physical capacity was released.
 */
final class PhysicalResourceRetirement {

    @FunctionalInterface
    interface Operation {
        AsyncTask<Void> close();
    }

    private static final Logger logger = Logger.getLogger(PhysicalResourceRetirement.class.getName());
    private final List<Step> steps;
    private final Consumer<PhysicalResourceRetirement> settled;
    private int remaining;
    private boolean started;
    private Throwable failure;

    private static final class Step {

        private Operation operation;
        private boolean done;

        private Step(Operation operation) {
            this.operation = Objects.requireNonNull(operation, "operation");
        }
    }

    PhysicalResourceRetirement(List<Operation> operations, Consumer<PhysicalResourceRetirement> settled) {
        this.steps = new ArrayList<>(operations.size());
        for (Operation operation : operations) steps.add(new Step(operation));
        this.remaining = steps.size();
        this.settled = Objects.requireNonNull(settled, "settled");
    }

    void start() {
        synchronized (this) {
            if (started) return;
            started = true;
        }
        if (steps.isEmpty()) {
            notifySettled();
            return;
        }
        for (Step step : steps) {
            try {
                AsyncTask<Void> task = Objects.requireNonNull(step.operation.close(), "Physical close returned no task");
                // Direct observation survives shutdown/rejection of the provider task's executor.
                task.observeCompletion(ignored -> complete(step, null), error -> complete(step, error));
            } catch (Throwable error) {
                complete(step, error);
            }
        }
    }

    private void complete(Step step, Throwable error) {
        boolean notify;
        synchronized (this) {
            if (step.done) return;
            step.done = true;
            if (error == null) {
                // Successful handles can be released; failed handles remain owned by their operation.
                step.operation = null;
            } else if (failure == null) {
                failure = error;
            } else if (failure != error) {
                failure.addSuppressed(error);
            }
            notify = --remaining == 0;
        }
        if (notify) notifySettled();
    }

    private void notifySettled() {
        try {
            settled.accept(this);
        } catch (Throwable error) {
            logger.severe("Failed to record physical resource retirement (" + error.getClass().getSimpleName() + ")");
        }
    }

    synchronized boolean isSettled() {
        return started && remaining == 0;
    }

    synchronized boolean isSuccessful() {
        return isSettled() && failure == null;
    }

    synchronized Throwable getFailure() {
        return failure;
    }
}
