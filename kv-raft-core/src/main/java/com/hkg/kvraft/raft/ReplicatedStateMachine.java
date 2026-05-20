package com.hkg.kvraft.raft;

/**
 * The application boundary the Raft core hands committed entries to.
 *
 * <p>Implementations are guaranteed to receive entries in commit-index
 * order, exactly once per entry. The state machine is responsible for
 * interpreting the opaque {@code command} bytes and applying them to
 * its own state.
 *
 * <p>Implementations should be deterministic: every replica's state
 * machine, given the same sequence of entries, must reach the same
 * state. This is what makes a Raft-replicated state machine equivalent
 * across replicas.
 */
public interface ReplicatedStateMachine {

    /**
     * Apply one committed log entry. Called by the Raft server on its
     * apply thread; must not block on cross-replica I/O.
     */
    void apply(LogEntry entry);
}
