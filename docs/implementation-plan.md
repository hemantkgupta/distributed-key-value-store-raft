# Implementation Plan — distributed-key-value-store-raft


Five phases, sixteen checkpoints, firm stop point. Below: per-phase scope, per-CP deliverable, build invariant.

---

## Phase 1: Raft Core — CP 1 to CP 4

Single Raft group, no KV semantics, no multi-tenancy. Goal: an `interface ReplicatedStateMachine { void apply(Command command); }` substrate that you could implement against to get any RSM-style service, of which a KV store is one instance.

### CP 1: Raft log + persistence
- `RaftLog` interface: `append`, `entriesFrom(index)`, `truncateAfter(index)`, `lastIndex`, `lastTerm`.
- `InMemoryRaftLog` for tests; `FileRaftLog` for durability.
- `LogEntry` record: `term`, `index`, `command`, `clientRequestId` (UUID).
- Tests: append + read, truncate, persistence across restart.

### CP 2: Leader election
- `RaftServer` with three states: `FOLLOWER`, `CANDIDATE`, `LEADER`.
- Randomized election timeout (150-300ms).
- `RequestVote` RPC + handling.
- Term progression, vote denial on stale-log candidates (Raft §5.4.1: "election restriction").
- Tests: single-node election, three-node election with one node down, split vote retry.

### CP 3: AppendEntries
- `AppendEntries` RPC + handling.
- Heartbeats from leader to followers (~50ms interval).
- Follower-side log consistency check + leader's nextIndex/matchIndex.
- Commit index advancement on majority replication.
- Tests: replication to all followers, follower catch-up after lag, follower-conflict log truncation.

### CP 4: Single-group state machine boundary
- `ReplicatedStateMachine` interface.
- `RaftServer.submit(Command)` returns a future that completes when the entry is applied at the local state machine.
- Linearizable single-leader semantics by construction.
- Tests: submit + apply round-trip; failed submit when not leader; redirect hint.

**Phase 1 build invariant:** `./gradlew :kv-raft-core:test` passes with all leader-election, log-replication, and submit/apply tests.

---

## Phase 2: KV State Machine on Raft — CP 5 to CP 7

### CP 5: RaftKvStateMachine
- `RaftKvStateMachine implements ReplicatedStateMachine`.
- Apply `PUT`, `GET`, `DELETE` commands to a RocksDB-backed storage engine (forked module).
- Reads served from the leader's local state under future lease (Phase 4); meanwhile served via Raft `ReadIndex`.
- Tests: PUT-then-GET sees the write; DELETE then GET returns absent; restart preserves state.

### CP 6: Client request ID + idempotent retry
- Every client command carries a `clientRequestId` UUID.
- `RaftKvStateMachine` maintains a deduplication table: `(clientId, requestId) -> response`.
- Replay of the same `clientRequestId` returns the cached response without re-applying.
- Tests: idempotent retry of PUT after a successful application returns the same response.

### CP 7: Leader-aware client (initial)
- `KvClient` connects to a known leader endpoint; on `NOT_LEADER` redirect, retries against the leader returned in the response.
- `RequestBudget` caps total client-side retry time.
- Tests: client retries on leader change; gives up after budget exhaustion.

**Phase 2 build invariant:** `./gradlew :kv-raft-kv:test :kv-client:test` passes with all KV-semantics + idempotency + leader-redirect tests.

---

## Phase 3: Multi-Raft + Range Partitioning — CP 8 to CP 11

### CP 8: Range descriptors
- `RangeDescriptor` record: `rangeId`, `startKey`, `endKey`, `generation`, `voters`, `learners`, `leaseholder`, `leaseExpiresAt`.
- `RangeRegistry` interface: `lookup(Key)` returns the range owning that key.
- `InMemoryRangeRegistry` for tests.
- Tests: range lookup by key; non-overlapping range invariant.

### CP 9: Multi-Raft cluster glue
- `MultiRaftCluster`: maintains one `RaftServer` per range, mapped by `rangeId`.
- Each `RaftServer` has its own log, election timers, AppendEntries.
- Cluster-level membership (which nodes exist) decoupled from per-range membership (which nodes vote in each group).
- Tests: 3 ranges × 3 replicas each; independent leader elections per range.

### CP 10: Range scans
- A range scan from `startKey` to `endKey` routes to the leader of the range covering that span.
- If the scan crosses multiple ranges, the client decomposes it into per-range scans served by different leaders.
- Tests: single-range scan, multi-range scan, scan over an empty range.

### CP 11: Range split protocol
- Split-point selection (median key or size-based threshold).
- Two-phase split: (a) propose split via Raft entry on the old range, (b) once committed, the old range stops accepting writes for keys > splitPoint, and a new range is created with its own Raft group.
- No quorum loss during split — both groups maintain quorum throughout.
- Tests: split a range with active writes; both halves remain reachable; merge half-empty ranges (reverse).

**Phase 3 build invariant:** `./gradlew :kv-partitioning-range:test :kv-multi-raft:test` passes with range, scan, and split tests.

---

## Phase 4: Serializable Single-Key + Leader Leases + MVCC — CP 12 to CP 14

### CP 12: Leader leases
- `LeaderLease`: `(leader, startTime, duration, fencingToken)`.
- Followers' AppendEntries acknowledgments implicitly grant the lease.
- Leader serves local reads when `now < leaseStart + leaseDuration - skewMargin`.
- Configurable lease duration (default 5s) and skew margin (default 500ms).
- Tests: lease-held local read; expired-lease fallback to ReadIndex; lease loss on leader change.

### CP 13: MVCC versioned storage
- `MvccStorageEngine`: stores `(key, commit_ts) -> value`.
- Reads at `read_ts` return the latest version where `commit_ts <= read_ts`.
- GC threshold: discard versions older than `gc_threshold_ts`.
- Tests: multiple versions per key, point-in-time read, GC of old versions.

### CP 14: Serializable single-key
- Write path: leader assigns HLC commit timestamp; replicates via Raft.
- Read path: under lease, read at the leader's current `lease_ts`; serializable by single-leader serialization.
- HLC implementation: `(wall_clock_ms, logical_counter)` with monotonic merge.
- Tests: read-your-own-write, serializable concurrent writes, HLC monotonicity across leader change.

**Phase 4 build invariant:** `./gradlew :kv-leader-lease:test :kv-mvcc:test` passes with lease, MVCC, and serializability tests.

---

## Phase 5: Joint Consensus + Log Compaction + Snapshot Install — CP 15 to CP 16

### CP 15: Joint consensus
- `ConfigurationChange` entry type in the Raft log.
- Transition: `C_old → C_old,new → C_new`.
- During joint phase, every commit requires quorum from both `C_old` and `C_new`.
- Tests: add 2 nodes atomically; remove 2 nodes atomically; failure during joint phase recovers correctly.

### CP 16: Log compaction + snapshot install
- Periodic log compaction (configurable threshold).
- `Snapshot` captures the state machine's state at the truncation index.
- `InstallSnapshot` RPC for followers that fall behind the leader's log truncation point.
- Tests: log compaction reduces disk usage; lagging follower receives snapshot + resumes from snapshot index.

**Phase 5 build invariant:** `./gradlew :kv-membership:test` plus full suite passes with joint consensus, log compaction, and snapshot install tests.

**Stop point:** Phase 5 complete. Anything beyond — multi-key transactions, follower reads, multi-DC, observability binding, runtime packaging — is its own project.

---

## Per-Phase Commit Convention

Each phase boundary produces one commit with subject of the form:

```
distributed-key-value-store-raft: Phase N — <phase name>, N CPs

<one-paragraph summary of what landed>
<bullet list of new modules / features>
<cumulative test count>

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
```

Push at every phase boundary. Build must be green before push.
