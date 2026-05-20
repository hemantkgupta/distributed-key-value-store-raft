package com.hkg.kvraft.storage.rocksdb;

import static org.assertj.core.api.Assertions.assertThat;

import com.hkg.kvraft.common.Key;
import com.hkg.kvraft.common.Value;
import com.hkg.kvraft.storage.MutationRecord;
import com.hkg.kvraft.storage.StoredRecord;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RocksDbStorageEngineTest {
    @TempDir
    Path tempDir;

    @Test
    void storesAndReloadsLiveRecord() {
        Key key = Key.utf8("cart:123");
        Instant timestamp = Instant.parse("2026-04-21T10:00:00Z");

        try (RocksDbStorageEngine storage = RocksDbStorageEngine.open(tempDir)) {
            storage.apply(put(key, "v1", timestamp, "m1"));
        }

        try (RocksDbStorageEngine storage = RocksDbStorageEngine.open(tempDir)) {
            Optional<StoredRecord> record = storage.get(key);
            assertThat(record).isPresent();
            assertThat(string(record.get().value().orElseThrow())).isEqualTo("v1");
            assertThat(record.get().isLiveAt(timestamp.plusSeconds(1))).isTrue();
        }
    }

    @Test
    void storesDeleteAsTombstone() {
        Key key = Key.utf8("profile:deleted");
        Instant timestamp = Instant.parse("2026-04-21T10:00:00Z");

        try (RocksDbStorageEngine storage = RocksDbStorageEngine.open(tempDir)) {
            storage.apply(put(key, "live", timestamp, "m1"));
            storage.apply(delete(key, timestamp.plusSeconds(1), "m2"));

            StoredRecord record = storage.get(key).orElseThrow();
            assertThat(record.tombstone()).isTrue();
            assertThat(record.value()).isEmpty();
            assertThat(record.isLiveAt(timestamp.plusSeconds(2))).isFalse();
        }
    }

    @Test
    void preservesNewerVersionWhenOlderMutationArrivesLate() {
        Key key = Key.utf8("late-repair");
        Instant timestamp = Instant.parse("2026-04-21T10:00:00Z");

        try (RocksDbStorageEngine storage = RocksDbStorageEngine.open(tempDir)) {
            storage.apply(put(key, "new", timestamp.plusSeconds(10), "m2"));
            storage.apply(put(key, "old", timestamp, "m1"));

            StoredRecord record = storage.get(key).orElseThrow();
            assertThat(string(record.value().orElseThrow())).isEqualTo("new");
            assertThat(record.mutationId()).isEqualTo("m2");
        }
    }

    @Test
    void storesTtlExpiryMetadataWithoutPhysicalRemoval() {
        Key key = Key.utf8("session:ttl");
        Instant timestamp = Instant.parse("2026-04-21T10:00:00Z");

        try (RocksDbStorageEngine storage = RocksDbStorageEngine.open(tempDir)) {
            storage.apply(new MutationRecord(
                    key,
                    Optional.of(value("session")),
                    false,
                    timestamp,
                    Optional.of(Duration.ofSeconds(30)),
                    "m1"
            ));

            StoredRecord record = storage.get(key).orElseThrow();
            assertThat(record.expiresAt()).contains(timestamp.plusSeconds(30));
            assertThat(record.isLiveAt(timestamp.plusSeconds(29))).isTrue();
            assertThat(record.isExpiredAt(timestamp.plusSeconds(30))).isTrue();
        }
    }

    @Test
    void digestChangesWhenRecordChanges() {
        Key key = Key.utf8("digest");
        Instant timestamp = Instant.parse("2026-04-21T10:00:00Z");

        try (RocksDbStorageEngine storage = RocksDbStorageEngine.open(tempDir)) {
            byte[] missingDigest = storage.digest(key);
            storage.apply(put(key, "v1", timestamp, "m1"));
            byte[] liveDigest = storage.digest(key);
            storage.apply(delete(key, timestamp.plusSeconds(1), "m2"));
            byte[] tombstoneDigest = storage.digest(key);

            assertThat(liveDigest).isNotEqualTo(missingDigest);
            assertThat(tombstoneDigest).isNotEqualTo(liveDigest);
        }
    }

    @Test
    void scansAllStoredRecordsIncludingTombstones() {
        Instant timestamp = Instant.parse("2026-04-21T10:00:00Z");

        try (RocksDbStorageEngine storage = RocksDbStorageEngine.open(tempDir)) {
            storage.apply(put(Key.utf8("cart:1"), "v1", timestamp, "m1"));
            storage.apply(put(Key.utf8("cart:2"), "v2", timestamp.plusSeconds(1), "m2"));
            storage.apply(delete(Key.utf8("cart:3"), timestamp.plusSeconds(2), "m3"));

            assertThat(storage.scanAll())
                    .extracting(record -> record.key().toString())
                    .containsExactlyInAnyOrder("cart:1", "cart:2", "cart:3");
            assertThat(storage.scanAll())
                    .filteredOn(StoredRecord::tombstone)
                    .singleElement()
                    .extracting(record -> record.key().toString())
                    .isEqualTo("cart:3");
        }
    }

    private static MutationRecord put(Key key, String value, Instant timestamp, String mutationId) {
        return new MutationRecord(
                key,
                Optional.of(value(value)),
                false,
                timestamp,
                Optional.empty(),
                mutationId
        );
    }

    private static MutationRecord delete(Key key, Instant timestamp, String mutationId) {
        return new MutationRecord(
                key,
                Optional.empty(),
                true,
                timestamp,
                Optional.empty(),
                mutationId
        );
    }

    private static Value value(String value) {
        return new Value(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String string(Value value) {
        return new String(value.bytes(), StandardCharsets.UTF_8);
    }
}
