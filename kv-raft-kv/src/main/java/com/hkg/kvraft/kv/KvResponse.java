package com.hkg.kvraft.kv;

import com.hkg.kvraft.common.Value;

import java.util.Optional;

/**
 * The state machine's response to applying one command. For writes,
 * {@code value} is empty and {@code ok} is true on success. For reads
 * (served outside the Raft log in Phase 2), {@code value} carries the
 * read result.
 */
public record KvResponse(boolean ok, Optional<Value> value, String message) {

    public static KvResponse success() {
        return new KvResponse(true, Optional.empty(), "");
    }

    public static KvResponse withValue(Optional<Value> readValue) {
        return new KvResponse(true, readValue, "");
    }

    public static KvResponse failure(String message) {
        return new KvResponse(false, Optional.empty(), message);
    }
}
