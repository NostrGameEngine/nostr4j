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
import org.ngengine.nostr4j.event.SignedNostrEvent;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class EventTrackerBenchmark {

    @State(Scope.Benchmark)
    public static class WindowState {

        @Param({ "1024", "8192", "65536" })
        public int capacity;

        private InspectableEventTracker tracker;
        private SignedNostrEvent newestEvent;
        private SignedNostrEvent oldestEvent;
        private long sequence;
        private long baseTimestamp;

        @Setup(Level.Trial)
        public void setUp() {
            tracker = new InspectableEventTracker(capacity);
            baseTimestamp = Instant.now().getEpochSecond() - capacity;
            for (int i = 0; i < capacity; i++) {
                SignedNostrEvent event = BenchmarkEventFactory.event(baseTimestamp + i, 1, i);
                if (i == 0) {
                    oldestEvent = event;
                }
                newestEvent = event;
                tracker.seen(event);
            }
            sequence = capacity;
        }

        private SignedNostrEvent nextEvent() {
            long next = sequence++;
            return BenchmarkEventFactory.event(baseTimestamp + next, 1, next);
        }
    }

    @Benchmark
    public boolean ingestForwardUnique(WindowState state) {
        return state.tracker.seen(state.nextEvent());
    }

    @Benchmark
    public boolean detectRecentDuplicate(WindowState state) {
        return state.tracker.seen(state.newestEvent);
    }

    @Benchmark
    public boolean detectOldestDuplicate(WindowState state) {
        return state.tracker.seen(state.oldestEvent);
    }
}
