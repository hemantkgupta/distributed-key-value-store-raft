package com.hkg.kvraft.raft;

import java.util.List;
import java.util.Optional;

/**
 * Persistence boundary for the Raft replicated log.
 *
 * <p>Implementations may keep the log in memory (for tests) or on disk
 * (production). The contract is the same: monotonic indices starting at 1,
 * append-only with the exception of {@link #truncateAfter(long)} which is
 * called by followers when their log diverges from the leader's.
 *
 * <p>Indices are 1-based. {@code lastIndex() == 0} means the log is empty.
 */
public interface RaftLog {

    /**
     * Append an entry. The entry's index must equal {@code lastIndex() + 1};
     * implementations should validate and throw {@link IllegalArgumentException}
     * on mismatch.
     */
    void append(LogEntry entry);

    /**
     * Return entries starting at the given index (inclusive) up to lastIndex.
     * Returns an empty list if the start index is past lastIndex.
     */
    List<LogEntry> entriesFrom(long fromIndex);

    /**
     * Drop all entries with index > {@code afterIndex}. A value of 0 truncates
     * the entire log. Used by followers on AppendEntries conflict.
     */
    void truncateAfter(long afterIndex);

    /**
     * The index of the last entry, or 0 if the log is empty.
     */
    long lastIndex();

    /**
     * The term of the last entry, or 0 if the log is empty.
     */
    long lastTerm();

    /**
     * Look up an entry by index. Returns empty if the index is out of range.
     */
    Optional<LogEntry> get(long index);

    /**
     * The total number of entries currently in the log. Convenience for tests.
     */
    default long size() {
        return lastIndex();
    }
}
