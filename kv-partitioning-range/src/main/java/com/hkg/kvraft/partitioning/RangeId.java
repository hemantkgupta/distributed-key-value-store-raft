package com.hkg.kvraft.partitioning;

import java.util.Objects;

/**
 * Stable identifier for one range. Survives splits — when range {@code R}
 * splits into {@code R} (keeping its id) and {@code R'} (a new id), the
 * left half retains the original RangeId.
 */
public record RangeId(long id) {
    public RangeId {
        if (id < 1) {
            throw new IllegalArgumentException("range id must be >= 1, got " + id);
        }
    }

    public static RangeId of(long id) {
        return new RangeId(id);
    }
}
