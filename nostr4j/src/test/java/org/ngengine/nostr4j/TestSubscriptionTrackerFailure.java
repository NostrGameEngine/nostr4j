package org.ngengine.nostr4j;

import static org.junit.Assert.assertThrows;

import org.junit.Test;

public class TestSubscriptionTrackerFailure {

    @Test
    public void trackerFailureAndNullCannotOpenSubscription() {
        NostrPool pool = new NostrPool();
        assertThrows(
            IllegalStateException.class,
            () ->
                pool.subscribe(
                    new NostrFilter(),
                    () -> {
                        throw new IllegalArgumentException("broken tracker");
                    }
                )
        );
        assertThrows(IllegalStateException.class, () -> pool.subscribe(new NostrFilter(), () -> null));
        pool.clean();
    }
}
