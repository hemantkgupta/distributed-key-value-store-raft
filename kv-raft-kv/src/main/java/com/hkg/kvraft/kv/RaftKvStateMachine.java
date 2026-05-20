package com.hkg.kvraft.kv;

import com.hkg.kvraft.common.Key;
import com.hkg.kvraft.raft.LogEntry;
import com.hkg.kvraft.raft.ReplicatedStateMachine;
import com.hkg.kvraft.storage.MutationRecord;
import com.hkg.kvraft.storage.StorageEngine;
import com.hkg.kvraft.storage.StoredRecord;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * KV state machine layered on top of Raft. Decodes log entries into
 * {@link KvCommand}s and applies them to a backing {@link StorageEngine}.
 *
 * <h2>Idempotency</h2>
 * Each command carries a {@code clientRequestId}. The state machine keeps
 * a bounded dedup table mapping clientRequestId → cached response. A
 * replayed command (same id) returns the cached response without
 * re-applying. This handles the canonical client-retry-after-leader-change
 * scenario without duplicate side effects.
 *
 * <h2>Reads</h2>
 * Reads are served from the leader's local storage state, not through the
 * Raft log. This is fast but not strictly linearizable — Phase 4 adds
 * leader leases that fix this gap. For now, "read your own write" works
 * after a {@code submit} + {@code awaitApplied} round trip.
 *
 * <h2>Thread safety</h2>
 * {@code apply} is called by the Raft server's single apply thread; it
 * is not designed for concurrent calls. {@code read} is safe to call
 * from any thread.
 */
public final class RaftKvStateMachine implements ReplicatedStateMachine {

    private final StorageEngine storage;
    private final Map<String, KvResponse> dedup = new HashMap<>();
    private final Map<Long, KvResponse> responsesByIndex = new HashMap<>();

    public RaftKvStateMachine(StorageEngine storage) {
        this.storage = storage;
    }

    @Override
    public void apply(LogEntry entry) {
        KvCommand command = KvCommandCodec.decode(entry.command());
        String requestId = clientRequestIdOf(command);

        KvResponse cached = dedup.get(requestId);
        if (cached != null) {
            responsesByIndex.put(entry.index(), cached);
            return;
        }

        KvResponse response = applyCommand(command, entry);
        dedup.put(requestId, response);
        responsesByIndex.put(entry.index(), response);
    }

    /**
     * Look up the response produced when the entry at the given index was
     * applied. Used by the server to route results back to clients.
     */
    public Optional<KvResponse> responseFor(long logIndex) {
        return Optional.ofNullable(responsesByIndex.get(logIndex));
    }

    /**
     * Local point read. Not linearizable until Phase 4 adds leader leases.
     */
    public KvResponse read(Key key) {
        Optional<StoredRecord> record = storage.get(key);
        if (record.isEmpty() || record.get().tombstone() || !record.get().isLiveAt(Instant.now())) {
            return KvResponse.withValue(Optional.empty());
        }
        return KvResponse.withValue(record.get().value());
    }

    /**
     * Test/inspection helper: how many distinct client requests have been
     * applied. Should stay bounded under idempotent retry.
     */
    public int dedupSize() {
        return dedup.size();
    }

    // ----- Internals -----

    private KvResponse applyCommand(KvCommand command, LogEntry entry) {
        Instant ts = Instant.ofEpochMilli(entry.term() * 1_000_000L + entry.index());
        if (command instanceof PutCommand p) {
            MutationRecord mutation = new MutationRecord(
                    p.key(),
                    Optional.of(p.value()),
                    false,
                    ts,
                    Optional.empty(),
                    p.clientRequestId());
            storage.apply(mutation);
            return KvResponse.success();
        }
        if (command instanceof DeleteCommand d) {
            MutationRecord mutation = new MutationRecord(
                    d.key(),
                    Optional.empty(),
                    true,
                    ts,
                    Optional.empty(),
                    d.clientRequestId());
            storage.apply(mutation);
            return KvResponse.success();
        }
        throw new IllegalArgumentException("unknown command type " + command.getClass());
    }

    private static String clientRequestIdOf(KvCommand command) {
        if (command instanceof PutCommand p) {
            return p.clientRequestId();
        }
        if (command instanceof DeleteCommand d) {
            return d.clientRequestId();
        }
        throw new IllegalArgumentException("unknown command type " + command.getClass());
    }
}
