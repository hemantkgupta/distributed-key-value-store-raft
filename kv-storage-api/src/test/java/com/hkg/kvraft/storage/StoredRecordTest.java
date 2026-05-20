package com.hkg.kvraft.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hkg.kvraft.common.Key;
import com.hkg.kvraft.common.Value;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class StoredRecordTest {
    @Test
    void computesExpiryFromMutationTtl() {
        Instant timestamp = Instant.parse("2026-04-21T10:00:00Z");
        MutationRecord mutation = new MutationRecord(
                Key.utf8("ttl"),
                Optional.of(new Value(new byte[] {1})),
                false,
                timestamp,
                Optional.of(Duration.ofSeconds(5)),
                "m1"
        );

        StoredRecord record = StoredRecord.from(mutation);

        assertThat(record.expiresAt()).contains(timestamp.plusSeconds(5));
        assertThat(record.isLiveAt(timestamp.plusSeconds(4))).isTrue();
        assertThat(record.isExpiredAt(timestamp.plusSeconds(5))).isTrue();
    }

    @Test
    void ordersVersionsByTimestampThenMutationId() {
        Key key = Key.utf8("k");
        Instant timestamp = Instant.parse("2026-04-21T10:00:00Z");
        StoredRecord older = record(key, timestamp, "m1");
        StoredRecord newerTimestamp = record(key, timestamp.plusMillis(1), "m0");
        StoredRecord newerTieBreaker = record(key, timestamp, "m2");

        assertThat(newerTimestamp.compareVersionTo(older)).isPositive();
        assertThat(newerTieBreaker.compareVersionTo(older)).isPositive();
        assertThat(older.compareVersionTo(newerTimestamp)).isNegative();
    }

    @Test
    void convertsStoredRecordBackToMutationRecordForRepair() {
        Key key = Key.utf8("k");
        Instant timestamp = Instant.parse("2026-04-21T10:00:00Z");
        StoredRecord storedRecord = new StoredRecord(
                key,
                Optional.of(new Value(new byte[] {1})),
                false,
                timestamp,
                Optional.of(timestamp.plusSeconds(30)),
                "m1"
        );

        MutationRecord mutation = storedRecord.toMutationRecord();

        assertThat(mutation.key()).isEqualTo(key);
        assertThat(mutation.value()).isEqualTo(storedRecord.value());
        assertThat(mutation.tombstone()).isFalse();
        assertThat(mutation.timestamp()).isEqualTo(timestamp);
        assertThat(mutation.ttl()).contains(Duration.ofSeconds(30));
        assertThat(mutation.mutationId()).isEqualTo("m1");
    }

    @Test
    void rejectsExpiryBeforeTimestamp() {
        Key key = Key.utf8("k");
        Instant timestamp = Instant.parse("2026-04-21T10:00:00Z");

        assertThatThrownBy(() -> new StoredRecord(
                key,
                Optional.of(new Value(new byte[] {1})),
                false,
                timestamp,
                Optional.of(timestamp.minusMillis(1)),
                "m1"
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("expiry must not be before timestamp");
    }

    private static StoredRecord record(Key key, Instant timestamp, String mutationId) {
        return new StoredRecord(
                key,
                Optional.of(new Value(new byte[] {1})),
                false,
                timestamp,
                Optional.empty(),
                mutationId
        );
    }
}
