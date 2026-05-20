package com.hkg.kvraft.raft;

import java.util.Arrays;
import java.util.Objects;

/**
 * One entry in a Raft replicated log.
 *
 * <p>Every entry has a monotonically-increasing {@code index} (1-based) and a
 * {@code term} assigned by the leader that proposed it. The {@code command}
 * is the application-level payload — opaque bytes here so the Raft core
 * remains agnostic of the state machine it runs.
 *
 * <p>{@code clientRequestId} supports idempotent client retries: the state
 * machine keeps a dedup table keyed by (clientId, clientRequestId), and a
 * replayed command returns the cached response without re-applying.
 */
public record LogEntry(long term, long index, byte[] command, String clientRequestId) {

    public LogEntry {
        if (term < 0) {
            throw new IllegalArgumentException("term must be >= 0, got " + term);
        }
        if (index < 1) {
            throw new IllegalArgumentException("index must be >= 1, got " + index);
        }
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(clientRequestId, "clientRequestId");
        command = command.clone();
    }

    @Override
    public byte[] command() {
        return command.clone();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof LogEntry other)) return false;
        return term == other.term
                && index == other.index
                && clientRequestId.equals(other.clientRequestId)
                && Arrays.equals(command, other.command);
    }

    @Override
    public int hashCode() {
        return Objects.hash(term, index, clientRequestId, Arrays.hashCode(command));
    }

    @Override
    public String toString() {
        return "LogEntry{term=" + term + ", index=" + index
                + ", clientRequestId=" + clientRequestId
                + ", commandLen=" + command.length + "}";
    }
}
