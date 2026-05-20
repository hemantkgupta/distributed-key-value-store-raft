package com.hkg.kvraft.raft;

import java.time.Duration;
import java.util.Random;

/**
 * Time source for the Raft server. Wrapped so tests can use a deterministic
 * fake clock without depending on wall-clock timing.
 *
 * <p>{@code now()} returns a logical monotonic clock in milliseconds.
 * {@code randomElectionTimeout()} returns a random duration in the
 * configured election-timeout range.
 */
public interface RaftClock {

    long now();

    Duration randomElectionTimeout();

    /** Real-time clock with a configured min/max election timeout. */
    static RaftClock systemClock(Duration min, Duration max) {
        Random random = new Random();
        return new RaftClock() {
            @Override
            public long now() {
                return System.currentTimeMillis();
            }

            @Override
            public Duration randomElectionTimeout() {
                long minMs = min.toMillis();
                long maxMs = max.toMillis();
                long range = maxMs - minMs;
                if (range <= 0) return min;
                return Duration.ofMillis(minMs + random.nextInt((int) range + 1));
            }
        };
    }
}
