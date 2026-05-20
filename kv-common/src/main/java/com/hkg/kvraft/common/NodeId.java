package com.hkg.kvraft.common;

public record NodeId(String value) {
    public NodeId {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("node id must not be blank");
        }
    }
}
