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
package org.ngengine.nostr4j.platform.jvm;

import java.util.Collection;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.ngengine.nostr4j.platform.AsyncTask;
import org.ngengine.nostr4j.platform.NostrExecutor;
import org.ngengine.nostr4j.transport.NostrTransport;
import org.ngengine.nostr4j.transport.RTCTransport;

public class JVMThreadedPlatform extends JVMAsyncPlatform {

    public JVMThreadedPlatform() {
        super();
    }

    private class TNostrExecutor implements NostrExecutor {

        protected final ScheduledExecutorService executor;

        public TNostrExecutor() {
            this.executor = Executors.newScheduledThreadPool(1);
        }

        @Override
        public <T> AsyncTask<T> run(Callable<T> r) {
            return wrapPromise((res, rej) -> {
                executor.submit(() -> {
                    try {
                        res.accept(r.call());
                    } catch (Exception e) {
                        rej.accept(e);
                    }
                });
            });
        }

        @Override
        public <T> AsyncTask<T> runLater(Callable<T> r, long delay, TimeUnit unit) {
            return wrapPromise((res, rej) -> {
                executor.schedule(
                    () -> {
                        try {
                            res.accept(r.call());
                        } catch (Exception e) {
                            rej.accept(e);
                        }
                    },
                    delay,
                    unit
                );
            });
        }

        @Override
        public void close() {
            executor.shutdown();
        }
    }

    @Override
    public NostrExecutor newRelayExecutor() {
        return new TNostrExecutor();
    }

    @Override
    public NostrExecutor newSubscriptionExecutor() {
        return new TNostrExecutor();
    }

    @Override
    public NostrExecutor newSignerExecutor() {
        return new TNostrExecutor();
    }

    @Override
    public NostrExecutor newPoolExecutor() {
        return new TNostrExecutor();
    }

    @Override
    public NostrTransport newTransport() {
        return new WebsocketTransport(this, Executors.newScheduledThreadPool(1));
    }

    @Override
    public RTCTransport newRTCTransport(String connId, Collection<String> stunServers) {
        JVMRTCTransport transport = new JVMRTCTransport();
        transport.start(connId, stunServers);
        return transport;
    }
}
