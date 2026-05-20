package com.hkg.kvraft.kv;

import com.hkg.kvraft.common.Key;
import com.hkg.kvraft.common.Value;
import com.hkg.kvraft.raft.LogEntry;
import com.hkg.kvraft.storage.MutationRecord;
import com.hkg.kvraft.storage.StorageEngine;
import com.hkg.kvraft.storage.StoredRecord;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class RaftKvStateMachineTest {

    @Test
    void putAppliesToStorage() {
        FakeStorage storage = new FakeStorage();
        RaftKvStateMachine sm = new RaftKvStateMachine(storage);

        Key key = new Key("k".getBytes());
        Value value = new Value("v".getBytes());
        sm.apply(entryFor(1, 1, new PutCommand(key, value, "req-1")));

        assertThat(storage.applied).hasSize(1);
        assertThat(storage.applied.get(0).key()).isEqualTo(key);
        assertThat(storage.applied.get(0).value()).contains(value);
    }

    @Test
    void deleteAppliesTombstone() {
        FakeStorage storage = new FakeStorage();
        RaftKvStateMachine sm = new RaftKvStateMachine(storage);

        sm.apply(entryFor(1, 1,
                new DeleteCommand(new Key("k".getBytes()), "req-1")));

        assertThat(storage.applied).hasSize(1);
        assertThat(storage.applied.get(0).tombstone()).isTrue();
    }

    @Test
    void duplicateClientRequestIdIsNotReapplied() {
        FakeStorage storage = new FakeStorage();
        RaftKvStateMachine sm = new RaftKvStateMachine(storage);

        PutCommand command = new PutCommand(
                new Key("k".getBytes()), new Value("v".getBytes()), "req-1");
        sm.apply(entryFor(1, 1, command));
        // Same command, different log index (replay scenario).
        sm.apply(entryFor(1, 2, command));

        assertThat(storage.applied).hasSize(1);
        assertThat(sm.dedupSize()).isEqualTo(1);
    }

    @Test
    void replayedCommandProducesSameResponse() {
        FakeStorage storage = new FakeStorage();
        RaftKvStateMachine sm = new RaftKvStateMachine(storage);

        PutCommand command = new PutCommand(
                new Key("k".getBytes()), new Value("v".getBytes()), "req-1");
        sm.apply(entryFor(1, 1, command));
        sm.apply(entryFor(1, 2, command));

        KvResponse r1 = sm.responseFor(1).orElseThrow();
        KvResponse r2 = sm.responseFor(2).orElseThrow();
        assertThat(r1).isEqualTo(r2);
    }

    @Test
    void readReturnsAppliedValue() {
        FakeStorage storage = new FakeStorage();
        RaftKvStateMachine sm = new RaftKvStateMachine(storage);

        Key key = new Key("k".getBytes());
        Value value = new Value("v".getBytes());
        sm.apply(entryFor(1, 1, new PutCommand(key, value, "req-1")));

        KvResponse response = sm.read(key);
        assertThat(response.ok()).isTrue();
        assertThat(response.value()).contains(value);
    }

    @Test
    void readReturnsEmptyForDeleted() {
        FakeStorage storage = new FakeStorage();
        RaftKvStateMachine sm = new RaftKvStateMachine(storage);

        Key key = new Key("k".getBytes());
        sm.apply(entryFor(1, 1,
                new PutCommand(key, new Value("v".getBytes()), "req-1")));
        sm.apply(entryFor(1, 2, new DeleteCommand(key, "req-2")));

        KvResponse response = sm.read(key);
        assertThat(response.value()).isEmpty();
    }

    @Test
    void readReturnsEmptyForUnknownKey() {
        RaftKvStateMachine sm = new RaftKvStateMachine(new FakeStorage());
        KvResponse response = sm.read(new Key("unknown".getBytes()));
        assertThat(response.value()).isEmpty();
    }

    @Test
    void responsesByIndexAreRecorded() {
        RaftKvStateMachine sm = new RaftKvStateMachine(new FakeStorage());
        sm.apply(entryFor(1, 1,
                new PutCommand(new Key("k".getBytes()), new Value("v".getBytes()), "req-1")));
        sm.apply(entryFor(1, 2,
                new DeleteCommand(new Key("k".getBytes()), "req-2")));

        assertThat(sm.responseFor(1)).isPresent();
        assertThat(sm.responseFor(2)).isPresent();
        assertThat(sm.responseFor(99)).isEmpty();
    }

    private static LogEntry entryFor(long term, long index, KvCommand command) {
        return new LogEntry(term, index, KvCommandCodec.encode(command),
                command instanceof PutCommand p ? p.clientRequestId()
                        : ((DeleteCommand) command).clientRequestId());
    }

    /** Minimal storage stub. Only point reads + tombstone-aware get. */
    private static final class FakeStorage implements StorageEngine {
        final List<MutationRecord> applied = new ArrayList<>();
        final Map<Key, StoredRecord> records = new HashMap<>();

        @Override
        public void apply(MutationRecord mutation) {
            applied.add(mutation);
            records.put(mutation.key(), StoredRecord.from(mutation));
        }

        @Override
        public Optional<StoredRecord> get(Key key) {
            return Optional.ofNullable(records.get(key));
        }

        @Override
        public List<StoredRecord> scanAll() {
            return new ArrayList<>(records.values());
        }

        @Override
        public byte[] digest(Key key) {
            return new byte[0];
        }

        @Override
        public void close() {
        }
    }
}
