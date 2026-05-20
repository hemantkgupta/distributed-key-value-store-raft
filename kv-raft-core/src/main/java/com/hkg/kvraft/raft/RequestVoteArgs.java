package com.hkg.kvraft.raft;

/**
 * Request part of the Raft RequestVote RPC. Sent by a candidate to peers
 * when it starts an election. Followers grant the vote if (a) they have
 * not voted in the current term, and (b) the candidate's log is at least
 * as up-to-date as the follower's (Raft §5.4.1 election restriction).
 */
public record RequestVoteArgs(
        long term,
        String candidateId,
        long lastLogIndex,
        long lastLogTerm) {

    public RequestVoteArgs {
        if (term < 0) {
            throw new IllegalArgumentException("term must be >= 0, got " + term);
        }
        if (candidateId == null || candidateId.isBlank()) {
            throw new IllegalArgumentException("candidateId must be non-blank");
        }
        if (lastLogIndex < 0) {
            throw new IllegalArgumentException("lastLogIndex must be >= 0, got " + lastLogIndex);
        }
        if (lastLogTerm < 0) {
            throw new IllegalArgumentException("lastLogTerm must be >= 0, got " + lastLogTerm);
        }
    }
}
