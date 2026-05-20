package com.hkg.kvraft.storage;

import com.hkg.kvraft.common.Key;
import java.util.List;
import java.util.Optional;

public interface StorageEngine extends AutoCloseable {
    void apply(MutationRecord mutation);

    Optional<StoredRecord> get(Key key);

    List<StoredRecord> scanAll();

    byte[] digest(Key key);

    @Override
    void close();
}
