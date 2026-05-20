package com.hkg.kvraft.raft;

/**
 * Thrown by {@link RaftTransport} when a peer is unreachable, a network
 * I/O error occurred, or the RPC otherwise failed to complete.
 */
public class RaftTransportException extends Exception {

    public RaftTransportException(String message) {
        super(message);
    }

    public RaftTransportException(String message, Throwable cause) {
        super(message, cause);
    }
}
