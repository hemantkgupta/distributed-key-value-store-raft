package com.hkg.kvraft.kv;

/**
 * A command applied to the KV state machine. Sealed so the codec can
 * exhaustively switch on it.
 */
public sealed interface KvCommand permits PutCommand, DeleteCommand {
}
