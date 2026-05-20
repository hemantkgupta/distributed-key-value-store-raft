package com.hkg.kvraft.raft;

/**
 * The three states a Raft server occupies. Transitions:
 *
 * <pre>
 *           election timeout
 *  FOLLOWER ─────────────────► CANDIDATE
 *      ▲                       │      │
 *      │ higher term observed  │ wins │ loses (split vote)
 *      │ OR new leader heard   │      │
 *      │                       ▼      ▼
 *      ◄─── higher term ─── LEADER ── (back to FOLLOWER on timeout)
 *                              │
 *                              │ heartbeats
 *                              ▼ to followers
 * </pre>
 */
public enum RaftState {
    FOLLOWER,
    CANDIDATE,
    LEADER
}
