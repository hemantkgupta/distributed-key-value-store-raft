package com.hkg.kvraft.raft;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * In-memory {@link RaftLog} implementation. Suitable for tests and for
 * deployments that prefer to rely on RocksDB-backed log persistence wired
 * through a different boundary. Not durable across process restarts.
 */
public final class InMemoryRaftLog implements RaftLog {

    private final List<LogEntry> entries = new ArrayList<>();

    @Override
    public void append(LogEntry entry) {
        Objects.requireNonNull(entry, "entry");
        long expectedIndex = lastIndex() + 1;
        if (entry.index() != expectedIndex) {
            throw new IllegalArgumentException(
                    "expected entry index " + expectedIndex + ", got " + entry.index());
        }
        entries.add(entry);
    }

    @Override
    public List<LogEntry> entriesFrom(long fromIndex) {
        if (fromIndex < 1) {
            throw new IllegalArgumentException("fromIndex must be >= 1, got " + fromIndex);
        }
        if (fromIndex > lastIndex()) {
            return Collections.emptyList();
        }
        // entries list is 0-based; entry at log index i is at entries.get(i-1)
        int start = (int) (fromIndex - 1);
        return new ArrayList<>(entries.subList(start, entries.size()));
    }

    @Override
    public void truncateAfter(long afterIndex) {
        if (afterIndex < 0) {
            throw new IllegalArgumentException("afterIndex must be >= 0, got " + afterIndex);
        }
        if (afterIndex >= lastIndex()) {
            return;
        }
        // Keep entries with index <= afterIndex; drop the rest.
        // After truncation, entries.size() == afterIndex.
        while (entries.size() > afterIndex) {
            entries.remove(entries.size() - 1);
        }
    }

    @Override
    public long lastIndex() {
        return entries.size();
    }

    @Override
    public long lastTerm() {
        if (entries.isEmpty()) {
            return 0;
        }
        return entries.get(entries.size() - 1).term();
    }

    @Override
    public Optional<LogEntry> get(long index) {
        if (index < 1 || index > lastIndex()) {
            return Optional.empty();
        }
        return Optional.of(entries.get((int) (index - 1)));
    }
}
