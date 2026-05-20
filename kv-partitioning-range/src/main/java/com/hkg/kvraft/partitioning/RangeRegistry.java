package com.hkg.kvraft.partitioning;

import com.hkg.kvraft.common.Key;

import java.util.List;
import java.util.Optional;

/**
 * Lookup of which range owns a given key, and metadata about all ranges
 * in the cluster.
 *
 * <p>Production implementations store this in a strongly-consistent
 * substrate (TiKV's PD, CockroachDB's metaRangeDescriptor table). The
 * registry must agree across all nodes — stale entries cause routing
 * mistakes.
 *
 * <p>Implementations are expected to be thread-safe: routing reads can
 * happen from any thread.
 */
public interface RangeRegistry {

    /**
     * Find the range that owns the given key. Returns empty if the key
     * is not covered by any range (which is a registry-corruption symptom;
     * a healthy registry always has total keyspace coverage).
     */
    Optional<RangeDescriptor> lookup(Key key);

    /**
     * All ranges currently in the cluster, in startKey order. Used by
     * range scans, monitoring, and admin tools.
     */
    List<RangeDescriptor> allRanges();

    /**
     * Register a new range. Used during initial cluster setup and after
     * splits / merges.
     */
    void put(RangeDescriptor descriptor);

    /**
     * Remove a range. Used after merges that subsumed it.
     */
    void remove(RangeId rangeId);
}
