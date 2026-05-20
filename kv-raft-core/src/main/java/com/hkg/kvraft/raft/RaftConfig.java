package com.hkg.kvraft.raft;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * Static configuration of a single Raft server within a group. Captures
 * the peer list, this server's identity, and the timing parameters that
 * govern elections and heartbeats.
 *
 * <p>{@code minElectionTimeout} and {@code maxElectionTimeout} define the
 * range from which each server randomly draws its election-timeout per
 * tick to avoid synchronized split votes. {@code heartbeatInterval} is
 * how often the leader sends heartbeats; it should be substantially
 * smaller than {@code minElectionTimeout} (typical ratio: 10×).
 */
public record RaftConfig(
        String selfId,
        List<String> peers,
        Duration minElectionTimeout,
        Duration maxElectionTimeout,
        Duration heartbeatInterval) {

    public RaftConfig {
        Objects.requireNonNull(selfId, "selfId");
        if (selfId.isBlank()) {
            throw new IllegalArgumentException("selfId must be non-blank");
        }
        Objects.requireNonNull(peers, "peers");
        peers = List.copyOf(peers);
        if (peers.contains(selfId)) {
            throw new IllegalArgumentException("peers must not contain selfId");
        }
        Objects.requireNonNull(minElectionTimeout, "minElectionTimeout");
        Objects.requireNonNull(maxElectionTimeout, "maxElectionTimeout");
        Objects.requireNonNull(heartbeatInterval, "heartbeatInterval");
        if (minElectionTimeout.isNegative() || minElectionTimeout.isZero()) {
            throw new IllegalArgumentException("minElectionTimeout must be > 0");
        }
        if (maxElectionTimeout.compareTo(minElectionTimeout) < 0) {
            throw new IllegalArgumentException("maxElectionTimeout must be >= minElectionTimeout");
        }
        if (heartbeatInterval.compareTo(minElectionTimeout) >= 0) {
            throw new IllegalArgumentException(
                    "heartbeatInterval must be < minElectionTimeout (got "
                            + heartbeatInterval + " vs " + minElectionTimeout + ")");
        }
    }

    /**
     * The number of nodes in the cluster including this one.
     */
    public int clusterSize() {
        return peers.size() + 1;
    }

    /**
     * Majority size: {@code floor(N/2) + 1}.
     */
    public int majoritySize() {
        return clusterSize() / 2 + 1;
    }

    /**
     * Convenience factory with sensible defaults: 150-300ms election, 50ms heartbeat.
     */
    public static RaftConfig withDefaults(String selfId, List<String> peers) {
        return new RaftConfig(
                selfId,
                peers,
                Duration.ofMillis(150),
                Duration.ofMillis(300),
                Duration.ofMillis(50));
    }
}
