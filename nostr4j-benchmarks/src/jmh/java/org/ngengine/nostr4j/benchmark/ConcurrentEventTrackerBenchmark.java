/**
 * BSD 3-Clause License
 *
 * Copyright (c) 2025, Riccardo Balbo
 *
 * See the LICENSE file in the project root for the full license text.
 */
package org.ngengine.nostr4j.benchmark;

import java.time.Instant;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.ngengine.nostr4j.event.SignedNostrEvent;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Threads;

@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Threads(4)
public class ConcurrentEventTrackerBenchmark {

    @State(Scope.Benchmark)
    public static class SharedState {

        private static final int CAPACITY = 8_192;

        private InspectableEventTracker tracker;
        private SignedNostrEvent newestEvent;
        private AtomicLong sequence;
        private long baseTimestamp;

        @Setup(Level.Trial)
        public void setUp() {
            tracker = new InspectableEventTracker(CAPACITY);
            baseTimestamp = Instant.now().getEpochSecond() - CAPACITY;
            for (int i = 0; i < CAPACITY; i++) {
                newestEvent = BenchmarkEventFactory.event(baseTimestamp + i, 30, i);
                tracker.seen(newestEvent);
            }
            sequence = new AtomicLong(CAPACITY);
        }

        private SignedNostrEvent nextEvent() {
            long next = sequence.getAndIncrement();
            return BenchmarkEventFactory.event(baseTimestamp + next, 30, next);
        }
    }

    @Benchmark
    public boolean ingestForwardUnique(SharedState state) {
        return state.tracker.seen(state.nextEvent());
    }

    @Benchmark
    public boolean detectRecentDuplicate(SharedState state) {
        return state.tracker.seen(state.newestEvent);
    }
}
