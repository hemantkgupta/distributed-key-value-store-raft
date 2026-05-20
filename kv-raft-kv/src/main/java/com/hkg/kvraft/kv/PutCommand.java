package com.hkg.kvraft.kv;

import com.hkg.kvraft.common.Key;
import com.hkg.kvraft.common.Value;

import java.util.Objects;

/**
 * Write a value for a key. Re-applying the same {@code clientRequestId} is
 * a no-op (the state machine's dedup table returns the cached response).
 */
public record PutCommand(Key key, Value value, String clientRequestId) implements KvCommand {
    public PutCommand {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(clientRequestId, "clientRequestId");
    }
}
