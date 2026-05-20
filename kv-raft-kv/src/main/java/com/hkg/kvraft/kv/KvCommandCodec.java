package com.hkg.kvraft.kv;

import com.hkg.kvraft.common.Key;
import com.hkg.kvraft.common.Value;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * Binary codec for {@link KvCommand} payloads carried inside Raft log
 * entries. Deterministic — the same command always produces the same
 * bytes. No JSON dependency; raw {@code DataOutputStream} with explicit
 * length prefixes.
 *
 * <p>Wire format:
 * <pre>
 *   byte tag        // 1=PUT, 2=DELETE
 *   varInt keyLen   // 4 bytes
 *   bytes  keyData
 *   varInt valLen   // 4 bytes (only for PUT)
 *   bytes  valData  // (only for PUT)
 *   varInt reqIdLen // 4 bytes
 *   bytes  reqIdUtf // request id as UTF-8
 * </pre>
 */
public final class KvCommandCodec {

    private static final byte TAG_PUT = 1;
    private static final byte TAG_DELETE = 2;

    private KvCommandCodec() {
    }

    public static byte[] encode(KvCommand command) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(baos)) {
            if (command instanceof PutCommand p) {
                out.writeByte(TAG_PUT);
                writeBytes(out, p.key().bytes());
                writeBytes(out, p.value().bytes());
                writeBytes(out, p.clientRequestId().getBytes(StandardCharsets.UTF_8));
            } else if (command instanceof DeleteCommand d) {
                out.writeByte(TAG_DELETE);
                writeBytes(out, d.key().bytes());
                writeBytes(out, d.clientRequestId().getBytes(StandardCharsets.UTF_8));
            } else {
                throw new IllegalArgumentException("unknown command type " + command.getClass());
            }
            return baos.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static KvCommand decode(byte[] data) {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(data))) {
            byte tag = in.readByte();
            switch (tag) {
                case TAG_PUT: {
                    Key key = new Key(readBytes(in));
                    Value value = new Value(readBytes(in));
                    String reqId = new String(readBytes(in), StandardCharsets.UTF_8);
                    return new PutCommand(key, value, reqId);
                }
                case TAG_DELETE: {
                    Key key = new Key(readBytes(in));
                    String reqId = new String(readBytes(in), StandardCharsets.UTF_8);
                    return new DeleteCommand(key, reqId);
                }
                default:
                    throw new IllegalArgumentException("unknown command tag " + tag);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void writeBytes(DataOutputStream out, byte[] data) throws IOException {
        out.writeInt(data.length);
        out.write(data);
    }

    private static byte[] readBytes(DataInputStream in) throws IOException {
        int len = in.readInt();
        if (len < 0 || len > 64 * 1024 * 1024) {
            throw new IOException("invalid length prefix " + len);
        }
        byte[] data = new byte[len];
        in.readFully(data);
        return data;
    }
}
