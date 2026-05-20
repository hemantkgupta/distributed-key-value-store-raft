package com.hkg.kvraft.storage.rocksdb;

import com.hkg.kvraft.common.Key;
import com.hkg.kvraft.storage.MutationRecord;
import com.hkg.kvraft.storage.StorageEngine;
import com.hkg.kvraft.storage.StoredRecord;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.rocksdb.Options;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import org.rocksdb.RocksIterator;

public final class RocksDbStorageEngine implements StorageEngine {
    static {
        RocksDB.loadLibrary();
    }

    private final Options options;
    private final RocksDB db;

    private RocksDbStorageEngine(Options options, RocksDB db) {
        this.options = options;
        this.db = db;
    }

    public static RocksDbStorageEngine open(Path path) {
        try {
            Files.createDirectories(path);
            Options options = new Options().setCreateIfMissing(true);
            RocksDB db = RocksDB.open(options, path.toString());
            return new RocksDbStorageEngine(options, db);
        } catch (Exception exception) {
            throw new StorageEngineException("failed to open RocksDB at " + path, exception);
        }
    }

    @Override
    public void apply(MutationRecord mutation) {
        StoredRecord candidate = StoredRecord.from(mutation);
        Optional<StoredRecord> existing = get(mutation.key());
        if (existing.isPresent() && candidate.compareVersionTo(existing.get()) < 0) {
            return;
        }

        try {
            db.put(mutation.key().bytes(), StoredRecordCodec.encode(candidate));
        } catch (RocksDBException exception) {
            throw new StorageEngineException("failed to apply mutation " + mutation.mutationId(), exception);
        }
    }

    @Override
    public Optional<StoredRecord> get(Key key) {
        try {
            byte[] encoded = db.get(key.bytes());
            if (encoded == null) {
                return Optional.empty();
            }
            return Optional.of(StoredRecordCodec.decode(encoded));
        } catch (RocksDBException exception) {
            throw new StorageEngineException("failed to read key " + key, exception);
        }
    }

    @Override
    public List<StoredRecord> scanAll() {
        ArrayList<StoredRecord> records = new ArrayList<>();
        try (RocksIterator iterator = db.newIterator()) {
            for (iterator.seekToFirst(); iterator.isValid(); iterator.next()) {
                records.add(StoredRecordCodec.decode(iterator.value()));
            }
            iterator.status();
            return List.copyOf(records);
        } catch (RocksDBException exception) {
            throw new StorageEngineException("failed to scan RocksDB records", exception);
        }
    }

    @Override
    public byte[] digest(Key key) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] encoded = db.get(key.bytes());
            if (encoded != null) {
                digest.update(encoded);
            }
            return digest.digest();
        } catch (RocksDBException exception) {
            throw new StorageEngineException("failed to digest key " + key, exception);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by the JDK", exception);
        }
    }

    @Override
    public void close() {
        db.close();
        options.close();
    }
}
