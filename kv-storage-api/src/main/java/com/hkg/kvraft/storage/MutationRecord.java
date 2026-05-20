package com.hkg.kvraft.storage;

import com.hkg.kvraft.common.Key;
import com.hkg.kvraft.common.Value;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

public record MutationRecord(
        Key key,
        Optional<Value> value,
        boolean tombstone,
        Instant timestamp,
        Optional<Duration> ttl,
        String mutationId
) {
    public MutationRecord {
        if (key == null) {
            throw new IllegalArgumentException("key must not be null");
        }
        value = value == null ? Optional.empty() : value;
        if (!tombstone && value.isEmpty()) {
            throw new IllegalArgumentException("non-delete mutation must carry a value");
        }
        if (timestamp == null) {
            throw new IllegalArgumentException("timestamp must not be null");
        }
        ttl = ttl == null ? Optional.empty() : ttl;
        if (mutationId == null || mutationId.isBlank()) {
            throw new IllegalArgumentException("mutation id must not be blank");
        }
    }
}
