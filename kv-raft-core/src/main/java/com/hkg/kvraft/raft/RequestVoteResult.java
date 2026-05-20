package com.hkg.kvraft.raft;

/**
 * Response part of the Raft RequestVote RPC. The follower's
 * {@code currentTerm} is always returned so the candidate can step down
 * if it discovers a higher term.
 */
public record RequestVoteResult(long term, boolean voteGranted) {

    public RequestVoteResult {
        if (term < 0) {
            throw new IllegalArgumentException("term must be >= 0, got " + term);
        }
    }
}
