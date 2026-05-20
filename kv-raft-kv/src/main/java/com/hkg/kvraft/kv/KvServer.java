package com.hkg.kvraft.kv;

import com.hkg.kvraft.common.Key;
import com.hkg.kvraft.common.Value;
import com.hkg.kvraft.raft.RaftServer;

import java.util.Objects;
import java.util.Optional;

/**
 * Thin client-facing wrapper around a {@link RaftServer} and a
 * {@link RaftKvStateMachine}. Decodes client-API calls into Raft log
 * submissions, then waits for them to be applied locally before returning
 * the state machine's response.
 *
 * <p>Reads in Phase 2 are served from the leader's local state (not
 * through Raft). This is fast but not strictly linearizable; Phase 4
 * adds leader leases that fix the gap.
 */
public final class KvServer {

    private final RaftServer raft;
    private final RaftKvStateMachine stateMachine;

    public KvServer(RaftServer raft, RaftKvStateMachine stateMachine) {
        this.raft = Objects.requireNonNull(raft);
        this.stateMachine = Objects.requireNonNull(stateMachine);
    }

    public KvResult put(Key key, Value value, String clientRequestId) {
        PutCommand command = new PutCommand(key, value, clientRequestId);
        return submitWrite(command);
    }

    public KvResult delete(Key key, String clientRequestId) {
        DeleteCommand command = new DeleteCommand(key, clientRequestId);
        return submitWrite(command);
    }

    /**
     * Local read at the leader. Phase 4 will require a valid leader lease.
     */
    public KvResult get(Key key) {
        KvResponse response = stateMachine.read(key);
        return KvResult.applied(response);
    }

    public String selfId() {
        return raft.selfId();
    }

    public RaftServer raft() {
        return raft;
    }

    private KvResult submitWrite(KvCommand command) {
        byte[] payload = KvCommandCodec.encode(command);
        String reqId = command instanceof PutCommand p ? p.clientRequestId()
                : ((DeleteCommand) command).clientRequestId();
        RaftServer.SubmitResult submit = raft.submit(payload, reqId);
        if (!submit.accepted()) {
            return KvResult.notLeader(submit.knownLeader());
        }
        // Caller drives the cluster forward; once the entry is applied,
        // the state machine has a response for this log index.
        return KvResult.submitted(submit.index(), submit.term());
    }

    /**
     * Look up the state machine's response for a submitted log index.
     * Returns empty if the entry has not yet been applied locally.
     */
    public Optional<KvResponse> responseFor(long logIndex) {
        return stateMachine.responseFor(logIndex);
    }

    // ----- Result type -----

    /**
     * The outcome of a client-API call. {@code applied} means the response
     * is ready; {@code submitted} means the command was accepted by the
     * leader and is in flight (the caller must drive the cluster forward
     * and then query {@link #responseFor(long)}); {@code notLeader} means
     * the caller should retry against {@code knownLeader}.
     */
    public record KvResult(
            boolean accepted,
            KvResponse response,
            long logIndex,
            long term,
            String knownLeader) {

        public static KvResult applied(KvResponse response) {
            return new KvResult(true, response, 0, 0, null);
        }

        public static KvResult submitted(long index, long term) {
            return new KvResult(true, null, index, term, null);
        }

        public static KvResult notLeader(String knownLeader) {
            return new KvResult(false, null, 0, 0, knownLeader);
        }

        public boolean inflight() {
            return accepted && response == null;
        }
    }
}
