package com.hkg.kvraft.storage.rocksdb;

import com.hkg.kvraft.common.Key;
import com.hkg.kvraft.common.Value;
import com.hkg.kvraft.storage.StoredRecord;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.Optional;

final class StoredRecordCodec {
    private static final int FORMAT_VERSION = 1;

    private StoredRecordCodec() {
    }

    static byte[] encode(StoredRecord record) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeInt(FORMAT_VERSION);
            writeBytes(out, record.key().bytes());
            out.writeBoolean(record.tombstone());
            writeInstant(out, record.timestamp());
            out.writeBoolean(record.expiresAt().isPresent());
            if (record.expiresAt().isPresent()) {
                writeInstant(out, record.expiresAt().get());
            }
            out.writeUTF(record.mutationId());
            if (record.value().isPresent()) {
                writeBytes(out, record.value().get().bytes());
            } else {
                out.writeInt(-1);
            }
            out.flush();
            return bytes.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("in-memory encode failed", exception);
        }
    }

    static StoredRecord decode(byte[] encoded) {
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(encoded));
            int version = in.readInt();
            if (version != FORMAT_VERSION) {
                throw new IllegalArgumentException("unsupported stored record format " + version);
            }
            Key key = new Key(readBytes(in));
            boolean tombstone = in.readBoolean();
            Instant timestamp = readInstant(in);
            Optional<Instant> expiresAt = in.readBoolean() ? Optional.of(readInstant(in)) : Optional.empty();
            String mutationId = in.readUTF();
            int valueLength = in.readInt();
            Optional<Value> value = Optional.empty();
            if (valueLength >= 0) {
                byte[] valueBytes = new byte[valueLength];
                in.readFully(valueBytes);
                value = Optional.of(new Value(valueBytes));
            }
            return new StoredRecord(key, value, tombstone, timestamp, expiresAt, mutationId);
        } catch (IOException exception) {
            throw new IllegalArgumentException("invalid stored record payload", exception);
        }
    }

    private static void writeBytes(DataOutputStream out, byte[] bytes) throws IOException {
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    private static byte[] readBytes(DataInputStream in) throws IOException {
        int length = in.readInt();
        if (length < 0) {
            throw new IllegalArgumentException("negative byte length");
        }
        byte[] bytes = new byte[length];
        in.readFully(bytes);
        return bytes;
    }

    private static void writeInstant(DataOutputStream out, Instant instant) throws IOException {
        out.writeLong(instant.getEpochSecond());
        out.writeInt(instant.getNano());
    }

    private static Instant readInstant(DataInputStream in) throws IOException {
        return Instant.ofEpochSecond(in.readLong(), in.readInt());
    }
}
