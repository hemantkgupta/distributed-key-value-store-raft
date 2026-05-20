package com.hkg.kvraft.common;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public final class Key {
    private final byte[] bytes;

    public Key(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            throw new IllegalArgumentException("key must not be empty");
        }
        this.bytes = bytes.clone();
    }

    public static Key utf8(String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("key must not be blank");
        }
        return new Key(key.getBytes(StandardCharsets.UTF_8));
    }

    public byte[] bytes() {
        return bytes.clone();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Key key && Arrays.equals(bytes, key.bytes);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(bytes);
    }

    @Override
    public String toString() {
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
