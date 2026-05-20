package com.hkg.kvraft.raft;

/**
 * Response part of the Raft AppendEntries RPC.
 *
 * <p>{@code success} is true only when the follower accepted the entries
 * (i.e. {@code prevLogIndex / prevLogTerm} matched and the term was not
 * stale). The follower's current {@code term} is always returned for the
 * leader's step-down check.
 *
 * <p>{@code matchIndex} is the highest log index that the follower has
 * accepted so the leader can advance {@code nextIndex} / {@code matchIndex}
 * for that follower.
 */
public record AppendEntriesResult(long term, boolean success, long matchIndex) {

    public AppendEntriesResult {
        if (term < 0) {
            throw new IllegalArgumentException("term must be >= 0, got " + term);
        }
        if (matchIndex < 0) {
            throw new IllegalArgumentException("matchIndex must be >= 0, got " + matchIndex);
        }
    }
}
