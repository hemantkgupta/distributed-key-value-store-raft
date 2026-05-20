package com.hkg.kvraft.raft;

/**
 * Transport boundary for Raft RPCs. Implementations may use HTTP, gRPC,
 * in-process direct calls (for tests), or any other mechanism. The core
 * Raft logic remains transport-agnostic.
 *
 * <p>Implementations should be thread-safe: a single {@link RaftServer}
 * may issue concurrent RPCs to different peers.
 *
 * <p>Implementations may throw a {@link RaftTransportException} when the
 * peer is unreachable; the Raft server catches these and treats the RPC
 * as a no-op for the purposes of election / replication progress.
 */
public interface RaftTransport {

    RequestVoteResult requestVote(String peerId, RequestVoteArgs args) throws RaftTransportException;

    AppendEntriesResult appendEntries(String peerId, AppendEntriesArgs args) throws RaftTransportException;
}
