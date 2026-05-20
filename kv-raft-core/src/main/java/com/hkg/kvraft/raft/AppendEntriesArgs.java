package com.hkg.kvraft.raft;

import java.util.List;
import java.util.Objects;

/**
 * Request part of the Raft AppendEntries RPC. Sent by the leader to
 * replicate log entries and to provide heartbeats (with empty
 * {@code entries}). Followers reject the call if {@code term} is lower
 * than their {@code currentTerm}, and reject log entries when
 * {@code (prevLogIndex, prevLogTerm)} does not match the entry they
 * already have at that position.
 */
public record AppendEntriesArgs(
        long term,
        String leaderId,
        long prevLogIndex,
        long prevLogTerm,
        List<LogEntry> entries,
        long leaderCommit) {

    public AppendEntriesArgs {
        if (term < 0) {
            throw new IllegalArgumentException("term must be >= 0, got " + term);
        }
        if (leaderId == null || leaderId.isBlank()) {
            throw new IllegalArgumentException("leaderId must be non-blank");
        }
        if (prevLogIndex < 0) {
            throw new IllegalArgumentException("prevLogIndex must be >= 0, got " + prevLogIndex);
        }
        if (prevLogTerm < 0) {
            throw new IllegalArgumentException("prevLogTerm must be >= 0, got " + prevLogTerm);
        }
        if (leaderCommit < 0) {
            throw new IllegalArgumentException("leaderCommit must be >= 0, got " + leaderCommit);
        }
        Objects.requireNonNull(entries, "entries");
        entries = List.copyOf(entries);
    }
}
