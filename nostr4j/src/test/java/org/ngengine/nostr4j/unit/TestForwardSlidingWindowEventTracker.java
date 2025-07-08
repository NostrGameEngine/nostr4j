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
package org.ngengine.nostr4j.unit;

import static org.junit.Assert.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.Before;
import org.junit.Test;
import org.ngengine.nostr4j.event.SignedNostrEvent;
import org.ngengine.nostr4j.event.tracker.ForwardSlidingWindowEventTracker;
import org.ngengine.nostr4j.keypair.NostrPrivateKey;
import org.ngengine.nostr4j.keypair.NostrPublicKey;

public class TestForwardSlidingWindowEventTracker {

    // Test constants
    private static final int MAX_EVENTS = 50;
    private static final int MIN_EVENTS = 10;
    private static final long WINDOW_SECONDS = 10; // 5 minutes
    private static final long WINDOW_MARGIN_SECONDS = 5; // 5 minutes

    private TestableEventTracker tracker;
    private long currentTimeSeconds;

    // Subclass for testing time-dependent behavior
    private static class TestableEventTracker extends ForwardSlidingWindowEventTracker {

        private long mockTime = System.currentTimeMillis();

        public TestableEventTracker(int maxTrackedEvents, int minTrackedEvents) {
            super(
                maxTrackedEvents,
                minTrackedEvents,
                WINDOW_SECONDS,
                TimeUnit.SECONDS,
                WINDOW_MARGIN_SECONDS,
                TimeUnit.SECONDS
            );
        }

        public void setMockTime(long timeMillis) {
            mockTime = timeMillis;
        }

        public long getCutOffTimestampS() {
            return cutOffS;
        }

        public Collection<SignedNostrEvent.Identifier> getAll() {
            return super.getAll();
        }

        public int count() {
            return super.count();
        }

        @Override
        protected long currentTimeSeconds() {
            return mockTime / 1000;
        }

        @Override
        public void update() {
            super.update();
        }
    }

    private NostrPublicKey pubkey;

    /**
     * Creates a real SignedNostrEvent with specified timestamp and event ID
     */
    private SignedNostrEvent createEvent(long timestampSeconds, String id) {
        try {
            if (pubkey == null) {
                pubkey = NostrPrivateKey.generate().getPublicKey();
            }
            SignedNostrEvent event = new SignedNostrEvent(
                id,
                pubkey,
                0,
                "",
                Instant.ofEpochSecond(timestampSeconds),
                "",
                (List<List<String>>) new ArrayList<List<String>>()
            );
            return event;
        } catch (Exception e) {
            throw new RuntimeException("Failed to create event", e);
        }
    }

    @Before
    public void setUp() {
        tracker = new TestableEventTracker(MAX_EVENTS, MIN_EVENTS);
        currentTimeSeconds = System.currentTimeMillis() / 1000;
    }

    // ------ Basic Event Tracking Tests ------

    @Test
    public void testNewEventShouldBeMarkedAsUnseen() {
        SignedNostrEvent event = createEvent(currentTimeSeconds, "event1");

        assertFalse("New event should not be marked as seen", tracker.seen(event));
        assertEquals("Event count should be 1", 1, tracker.count());
    }

    @Test
    public void testDuplicateEventShouldBeMarkedAsSeen() {
        SignedNostrEvent event = createEvent(currentTimeSeconds, "event1");

        assertFalse("First occurrence should not be marked as seen", tracker.seen(event));
        assertTrue("Duplicate should be marked as seen", tracker.seen(event));
        assertEquals("Event count should still be 1", 1, tracker.count());
    }

    @Test
    public void testDifferentEventsShouldBeUnique() {
        SignedNostrEvent event1 = createEvent(currentTimeSeconds, "event1");
        SignedNostrEvent event2 = createEvent(currentTimeSeconds + 1, "event2");

        assertFalse("First event should not be seen", tracker.seen(event1));
        assertFalse("Second event should not be seen", tracker.seen(event2));
        assertEquals("Event count should be 2", 2, tracker.count());
    }

    // ------ Event Ordering Tests ------

    @Test
    public void testEventsShouldBeOrderedByTimestamp() {
        // Add events in reverse chronological order
        SignedNostrEvent event3 = createEvent(currentTimeSeconds + 30, "event3");
        SignedNostrEvent event2 = createEvent(currentTimeSeconds + 20, "event2");
        SignedNostrEvent event1 = createEvent(currentTimeSeconds + 10, "event1");

        tracker.seen(event3);
        tracker.seen(event2);
        tracker.seen(event1);

        Collection<SignedNostrEvent.Identifier> allEvents = tracker.getAll();

        // should be sorted by newer timestamps first
        long lastTimestamp = Long.MAX_VALUE;
        for (SignedNostrEvent.Identifier id : allEvents) {
            assertTrue("Events should be in descending order", id.createdAt <= lastTimestamp);
            lastTimestamp = id.createdAt;
        }
    }

    // ------ Maximum Event Limit Tests ------

    @Test
    public void testShouldEnforceMaximumLimit() {
        // Add MAX_EVENTS + 10 events
        for (int i = 0; i < MAX_EVENTS + 10; i++) {
            SignedNostrEvent event = createEvent(currentTimeSeconds + i, "event" + i);
            tracker.seen(event);
        }

        assertEquals("Should trim to maximum event limit", MAX_EVENTS, tracker.count());

        // Verify the oldest events were removed
        SignedNostrEvent oldestEvent = createEvent(currentTimeSeconds, "event0");
        assertTrue("Oldest event should be marked as seen (trimmed)", tracker.seen(oldestEvent));
    }

    // ------ Time Window Behavior Tests ------

    @Test
    public void testShouldMarkOldEventsAsSeen() {
        // Current time
        tracker.setMockTime(currentTimeSeconds * 1000);

        // Add event with timestamp now
        SignedNostrEvent recentEvent = createEvent(currentTimeSeconds, "recent");
        assertFalse("Recent event should not be seen", tracker.seen(recentEvent));

        // Advance time by WINDOW_SECONDS + 10
        long newTime = (currentTimeSeconds + WINDOW_SECONDS + 10) * 1000;
        tracker.setMockTime(newTime);

        // Force update window
        tracker.update();

        // Test old event is now considered seen
        assertTrue("After time window, event should be considered seen", tracker.seen(recentEvent));

        // New event should still be accepted
        SignedNostrEvent newEvent = createEvent(currentTimeSeconds + WINDOW_SECONDS + 5, "new");
        assertFalse("New event should not be seen", tracker.seen(newEvent));
    }

    @Test
    public void testShouldRemoveOldEvents() {
        // Add MIN_EVENTS + 5 events with current time
        for (int i = 0; i < MIN_EVENTS + 5; i++) {
            SignedNostrEvent event = createEvent(currentTimeSeconds, "event" + i);
            tracker.seen(event);
        }

        // Advance time by WINDOW_SECONDS + MARGIN
        long newTime = (currentTimeSeconds + WINDOW_SECONDS + WINDOW_MARGIN_SECONDS) * 1000;
        tracker.setMockTime(newTime);

        // Add newer events to trigger update
        for (int i = 0; i < 5; i++) {
            SignedNostrEvent event = createEvent(currentTimeSeconds + WINDOW_SECONDS + WINDOW_MARGIN_SECONDS + i, "new" + i);
            tracker.seen(event);
        }

        // All the events should be after the cutoff
        long cutOffTimeStampSeconds = tracker.getCutOffTimestampS();
        assertTrue("Cutoff time should be in the past", cutOffTimeStampSeconds < newTime / 1000);
        assertTrue("There should be at least one event", tracker.count() > 0);
        for (SignedNostrEvent.Identifier id : tracker.getAll()) {
            assertTrue(
                "Event should be after cutoff " + id.id + " " + id.createdAt + ">=" + cutOffTimeStampSeconds,
                id.createdAt >= cutOffTimeStampSeconds
            );
        }

        assertTrue("invalid cutoff", cutOffTimeStampSeconds == ((newTime / 1000) - (WINDOW_SECONDS - WINDOW_MARGIN_SECONDS)));
    }

    // ------ Edge Cases Tests ------

    @Test
    public void testShouldHandleEmptyTracker() {
        assertEquals("New tracker should be empty", 0, tracker.count());
        assertTrue("Event list should be empty", tracker.getAll().isEmpty());
    }

    @Test
    public void testShouldRespectMinimumEvents() {
        // Add MIN_EVENTS - 1 events
        for (int i = 0; i < MIN_EVENTS - 1; i++) {
            SignedNostrEvent event = createEvent(currentTimeSeconds - WINDOW_SECONDS - 10, "old" + i);
            tracker.seen(event);
        }

        // Advance time
        long newTime = (currentTimeSeconds + WINDOW_SECONDS + 10) * 1000;
        tracker.setMockTime(newTime);

        // Force update
        tracker.update();

        // Should keep all events despite being old (below minimum)
        assertEquals("Should not remove events below minimum", MIN_EVENTS - 1, tracker.count());
    }

    @Test
    public void testShouldHandleIdenticalTimestamps() {
        Set<String> eventIds = new HashSet<>();
        final int EVENT_COUNT = 20;

        // Add multiple events with same timestamp
        for (int i = 0; i < EVENT_COUNT; i++) {
            String id = "same-time-" + i;
            eventIds.add(id);
            SignedNostrEvent event = createEvent(currentTimeSeconds, id);
            assertFalse("Event should be marked as not seen", tracker.seen(event));
        }

        // Test all events are tracked properly
        assertEquals("All events should be tracked", EVENT_COUNT, tracker.count());

        // Test each event is correctly identified as seen
        for (String id : eventIds) {
            SignedNostrEvent event = createEvent(currentTimeSeconds, id);
            assertTrue("Event should be recognized as seen", tracker.seen(event));
        }
    }

    // ------ Performance Tests ------

    @Test
    public void testShouldHandleLargeNumberOfEvents() {
        final int LARGE_COUNT = 5000;

        // Create tracker with larger limits for this test
        TestableEventTracker largeTracker = new TestableEventTracker(LARGE_COUNT, 1000);

        long startTime = System.currentTimeMillis();

        // Add many events
        for (int i = 0; i < LARGE_COUNT; i++) {
            SignedNostrEvent event = createEvent(currentTimeSeconds + i, "event" + i);
            largeTracker.seen(event);
        }

        // Look up each event
        for (int i = 0; i < LARGE_COUNT; i++) {
            SignedNostrEvent event = createEvent(currentTimeSeconds + i, "event" + i);
            assertTrue("Event should be recognized", largeTracker.seen(event));
        }

        long duration = System.currentTimeMillis() - startTime;

        // This is a flexible performance check - mainly for regression testing
        assertTrue("Large operation should complete in reasonable time", duration < 10000);
    }

    @Test
    public void testOutOfOrderEventInsertion() {
        // Create events with out-of-order timestamps
        SignedNostrEvent oldEvent = createEvent(currentTimeSeconds - 100, "old");
        SignedNostrEvent midEvent = createEvent(currentTimeSeconds, "mid");
        SignedNostrEvent newEvent = createEvent(currentTimeSeconds + 100, "new");

        // Add them out of order
        tracker.seen(midEvent);
        tracker.seen(newEvent);
        tracker.seen(oldEvent);

        // Verify correct order in collection
        Collection<SignedNostrEvent.Identifier> allEvents = tracker.getAll();
        assertEquals("Should have 3 events", 3, allEvents.size());

        // Check order (newest to oldest)
        long lastTimestamp = Long.MAX_VALUE;
        for (SignedNostrEvent.Identifier id : allEvents) {
            assertTrue("Events should maintain correct order", id.createdAt <= lastTimestamp);
            lastTimestamp = id.createdAt;
        }
    }

    @Test
    public void testExactlyMinimumEvents() {
        // Add exactly MIN_EVENTS old events
        for (int i = 0; i < MIN_EVENTS; i++) {
            SignedNostrEvent event = createEvent(currentTimeSeconds - WINDOW_SECONDS - 10, "old" + i);
            tracker.seen(event);
        }

        // Advance time to make events old
        tracker.setMockTime((currentTimeSeconds + WINDOW_SECONDS + 10) * 1000);
        tracker.update();

        // All events should be kept even though they're old (exact minimum)
        assertEquals("Should keep exactly minimum events", MIN_EVENTS, tracker.count());

        // Now add one more event
        SignedNostrEvent newEvent = createEvent(currentTimeSeconds + WINDOW_SECONDS + 5, "new");
        tracker.seen(newEvent);

        // Should now have old events removed since we're above minimum
        assertTrue("Should have removed old events when above minimum", tracker.count() < MIN_EVENTS + 1);
        assertTrue("New event should be kept", tracker.count() >= 1);
    }

    @Test
    public void testMultipleTimeWindowUpdates() {
        // Add initial events
        for (int i = 0; i < MIN_EVENTS; i++) {
            tracker.seen(createEvent(currentTimeSeconds + i, "event" + i));
        }

        // First time advance
        tracker.setMockTime((currentTimeSeconds + WINDOW_SECONDS + 10) * 1000);
        tracker.update();
        long firstCutoff = tracker.getCutOffTimestampS();

        // Add more events after first cutoff
        for (int i = 0; i < 5; i++) {
            tracker.seen(createEvent(currentTimeSeconds + WINDOW_SECONDS + 20 + i, "new1-" + i));
        }

        // Second time advance
        tracker.setMockTime((currentTimeSeconds + WINDOW_SECONDS * 2 + 20) * 1000);
        tracker.update();
        long secondCutoff = tracker.getCutOffTimestampS();

        // Add more events after second cutoff
        for (int i = 0; i < 5; i++) {
            tracker.seen(createEvent(currentTimeSeconds + WINDOW_SECONDS * 2 + 30 + i, "new2-" + i));
        }

        // Verify cutoffs increased
        assertTrue("Second cutoff should be later than first " + secondCutoff + " " + firstCutoff, secondCutoff > firstCutoff);

        // Verify only newest events are kept
        for (SignedNostrEvent.Identifier id : tracker.getAll()) {
            assertTrue("Event should be after second cutoff", id.createdAt >= secondCutoff);
        }
    }

    @Test
    public void testInterleavedOldAndNewEvents() {
        // Initial setup
        tracker.setMockTime(currentTimeSeconds * 1000);

        // Add some events
        for (int i = 0; i < 5; i++) {
            tracker.seen(createEvent(currentTimeSeconds + i, "initial" + i));
        }

        // Advance time
        tracker.setMockTime((currentTimeSeconds + WINDOW_SECONDS + 10) * 1000);
        tracker.update();

        // Now add alternating old and new events
        for (int i = 0; i < 10; i++) {
            if (i % 2 == 0) {
                // Old event (before cutoff)
                tracker.seen(createEvent(currentTimeSeconds + i, "old" + i));
            } else {
                // New event (after cutoff)
                tracker.seen(createEvent(currentTimeSeconds + WINDOW_SECONDS + 20 + i, "new" + i));
            }
        }

        // Verify only new events were added
        for (SignedNostrEvent.Identifier id : tracker.getAll()) {
            assertTrue(
                "Only events after cutoff should be tracked",
                id.createdAt >= tracker.getCutOffTimestampS() || id.id.startsWith("new")
            );
        }
    }

    @Test
    public void testMultipleUpdateCallsWithoutChanges() {
        // Add events
        for (int i = 0; i < 20; i++) {
            tracker.seen(createEvent(currentTimeSeconds + i, "event" + i));
        }

        // Get current state
        int initialCount = tracker.count();
        long initialCutoff = tracker.getCutOffTimestampS();

        // Call update multiple times without changing conditions
        for (int i = 0; i < 5; i++) {
            tracker.update();
        }

        // Verify no change in state
        assertEquals("Multiple updates shouldn't change event count", initialCount, tracker.count());
        assertEquals("Multiple updates shouldn't change cutoff", initialCutoff, tracker.getCutOffTimestampS());
    }

    @Test
    public void testEventsJustBeforeMaximumLimit() {
        // Add MAX_EVENTS - 1 events
        for (int i = 0; i < MAX_EVENTS - 1; i++) {
            tracker.seen(createEvent(currentTimeSeconds + i, "event" + i));
        }

        // Should not trigger any removals
        assertEquals("Should have exactly MAX_EVENTS - 1 events", MAX_EVENTS - 1, tracker.count());

        // Add one more to exactly hit the limit
        tracker.seen(createEvent(currentTimeSeconds + WINDOW_SECONDS - 1, "eventMax"));

        assertEquals("Should have exactly MAX_EVENTS events", MAX_EVENTS, tracker.count());

        // Add one more to exceed the limit
        tracker.seen(createEvent(currentTimeSeconds + WINDOW_SECONDS, "eventOver"));

        assertEquals("Should still have MAX_EVENTS after exceeding limit", MAX_EVENTS, tracker.count());

        // The oldest event should be gone
        SignedNostrEvent oldestEvent = createEvent(currentTimeSeconds, "event0");
        assertTrue("Oldest event should be marked as seen (removed)", tracker.seen(oldestEvent));
    }
}
