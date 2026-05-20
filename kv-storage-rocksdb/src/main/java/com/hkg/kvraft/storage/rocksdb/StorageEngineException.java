package com.hkg.kvraft.storage.rocksdb;

public final class StorageEngineException extends RuntimeException {
    public StorageEngineException(String message, Throwable cause) {
        super(message, cause);
    }
}
