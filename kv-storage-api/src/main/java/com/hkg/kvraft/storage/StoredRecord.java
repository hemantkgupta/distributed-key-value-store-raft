package com.hkg.kvraft.storage;

import com.hkg.kvraft.common.Key;
import com.hkg.kvraft.common.Value;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

public record StoredRecord(
        Key key,
        Optional<Value> value,
        boolean tombstone,
        Instant timestamp,
        Optional<Instant> expiresAt,
        String mutationId
) {
    public StoredRecord {
        if (key == null) {
            throw new IllegalArgumentException("key must not be null");
        }
        value = value == null ? Optional.empty() : value;
        if (!tombstone && value.isEmpty()) {
            throw new IllegalArgumentException("live record must carry a value");
        }
        if (timestamp == null) {
            throw new IllegalArgumentException("timestamp must not be null");
        }
        expiresAt = expiresAt == null ? Optional.empty() : expiresAt;
        if (expiresAt.isPresent() && expiresAt.get().isBefore(timestamp)) {
            throw new IllegalArgumentException("expiry must not be before timestamp");
        }
        if (mutationId == null || mutationId.isBlank()) {
            throw new IllegalArgumentException("mutation id must not be blank");
        }
    }

    public static StoredRecord from(MutationRecord mutation) {
        Optional<Instant> expiresAt = mutation.ttl().map(mutation.timestamp()::plus);
        return new StoredRecord(
                mutation.key(),
                mutation.value(),
                mutation.tombstone(),
                mutation.timestamp(),
                expiresAt,
                mutation.mutationId()
        );
    }

    public boolean isExpiredAt(Instant now) {
        if (now == null) {
            throw new IllegalArgumentException("now must not be null");
        }
        return expiresAt.map(expiry -> !now.isBefore(expiry)).orElse(false);
    }

    public boolean isLiveAt(Instant now) {
        return !tombstone && !isExpiredAt(now);
    }

    public MutationRecord toMutationRecord() {
        Optional<Duration> ttl = expiresAt.map(expiry -> Duration.between(timestamp, expiry));
        return new MutationRecord(key, value, tombstone, timestamp, ttl, mutationId);
    }

    public int compareVersionTo(StoredRecord other) {
        int timestampCompare = timestamp.compareTo(other.timestamp);
        if (timestampCompare != 0) {
            return timestampCompare;
        }
        return mutationId.compareTo(other.mutationId);
    }
}
