package com.hkg.kvraft.partitioning;

import com.hkg.kvraft.common.Key;
import com.hkg.kvraft.common.NodeId;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Metadata describing one range of the keyspace and the Raft group that
 * owns it. Ranges are half-open: a key {@code k} belongs to this range iff
 * {@code (startKey absent OR startKey <= k) AND (endKey absent OR k < endKey)}.
 *
 * <p>{@code startKey} absent means the range extends to negative infinity
 * (the leftmost range). {@code endKey} absent means the range extends to
 * positive infinity (the rightmost range). A single-range cluster has
 * both absent — it covers the entire keyspace.
 *
 * <p>{@code generation} increments on every membership change (add, remove,
 * leaseholder change, split, merge). Clients use it to detect stale range
 * cache entries.
 */
public record RangeDescriptor(
        RangeId rangeId,
        Optional<Key> startKey,
        Optional<Key> endKey,
        long generation,
        List<NodeId> voters,
        NodeId leaseholder) {

    public RangeDescriptor {
        Objects.requireNonNull(rangeId, "rangeId");
        startKey = startKey == null ? Optional.empty() : startKey;
        endKey = endKey == null ? Optional.empty() : endKey;
        if (generation < 0) {
            throw new IllegalArgumentException("generation must be >= 0, got " + generation);
        }
        Objects.requireNonNull(voters, "voters");
        voters = List.copyOf(voters);
        if (voters.isEmpty()) {
            throw new IllegalArgumentException("voters must not be empty");
        }
        Objects.requireNonNull(leaseholder, "leaseholder");
        if (!voters.contains(leaseholder)) {
            throw new IllegalArgumentException("leaseholder must be in voters");
        }
        // Sanity: when both bounds are present, start < end.
        if (startKey.isPresent() && endKey.isPresent()) {
            if (compare(startKey.get(), endKey.get()) >= 0) {
                throw new IllegalArgumentException("startKey must be < endKey");
            }
        }
    }

    /**
     * Whether this range owns the given key.
     */
    public boolean contains(Key key) {
        Objects.requireNonNull(key, "key");
        if (startKey.isPresent() && compare(startKey.get(), key) > 0) {
            return false;
        }
        return endKey.map(e -> compare(key, e) < 0).orElse(true);
    }

    /**
     * Lexicographic byte comparison: {@code -1, 0, +1} semantics.
     */
    public static int compare(Key a, Key b) {
        byte[] ba = a.bytes();
        byte[] bb = b.bytes();
        int len = Math.min(ba.length, bb.length);
        for (int i = 0; i < len; i++) {
            int va = ba[i] & 0xFF;
            int vb = bb[i] & 0xFF;
            if (va != vb) return Integer.compare(va, vb);
        }
        return Integer.compare(ba.length, bb.length);
    }
}
