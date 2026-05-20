package com.hkg.kvraft.kv;

import com.hkg.kvraft.common.Key;

import java.util.Objects;

/**
 * Delete a key. Implemented as a tombstone write so the deletion replicates
 * like any other write.
 */
public record DeleteCommand(Key key, String clientRequestId) implements KvCommand {
    public DeleteCommand {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(clientRequestId, "clientRequestId");
    }
}
