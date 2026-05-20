package com.hkg.kvraft.common;

import java.util.Arrays;

public final class Value {
    private final byte[] bytes;

    public Value(byte[] bytes) {
        if (bytes == null) {
            throw new IllegalArgumentException("value bytes must not be null");
        }
        this.bytes = bytes.clone();
    }

    public byte[] bytes() {
        return bytes.clone();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Value value && Arrays.equals(bytes, value.bytes);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(bytes);
    }
}
