# Distributed Key-Value Store — Multi-Raft (CP)


A Java implementation companion for the CSE wiki and raw-blog series on distributed key-value stores. This is the **CP half** of the two-repo KV topic — built around Multi-Raft + range partitioning + serializable single-key reads via leader leases + MVCC. The leaderless-AP half lives at [`distributed-key-value-store`](https://github.com/hemantkgupta/distributed-key-value-store).

The architectural recommendation in the 2026 deep-research report (`raw/articles/distributed-key-value-store-deep-research-report.md` in the wiki repo) is **range-partitioned, single-leader-per-partition Multi-Raft**. This repo is the Java companion that builds that design incrementally.

## Forked From

- **Origin repo:** [`distributed-key-value-store`](https://github.com/hemantkgupta/distributed-key-value-store)
- **Origin SHA:** `0305211b19e0be1d4b65499fcb9d1c1685c67d70`
- **What was copied verbatim:** the storage modules (`kv-common`, `kv-storage-api`, `kv-storage-rocksdb`, `kv-storage-toy-lsm`). The storage engine is genuinely orthogonal to replication topology — there is no reason to re-implement RocksDB-backed durable storage for the CP variant. Package namespaces were rewritten from `com.hkg.kv.*` to `com.hkg.kvraft.*` so the two repos can coexist on one classpath without collision.
- **What was rewritten:** everything else (replication, partitioning, membership, node runtime, client). Multi-Raft is structurally different from leaderless coordinator-fanout — vnode hashing → range partitioning; tunable consistency → linearizable via leader leases; Merkle anti-entropy → Raft-log snapshot install; coordinator computing replica plans → routing to range leaders via a range registry.

## Phase Plan

Five phases, ~16 checkpoints total. The stop point is firm — anything beyond is a separate effort.

| Phase | Checkpoints | Target |
|---|---|---|
| **Phase 1: Raft core** | CP 1–4 | Single Raft group: log, leader election (randomized timeout), AppendEntries, single-group state machine over a `Command` interface |
| **Phase 2: KV state machine on Raft** | CP 5–7 | `RaftKvStateMachine` applying `(PUT, GET, DELETE)` commands; RocksDB-backed persistence; client retry-on-not-leader; idempotent client request deduplication via client request ID |
| **Phase 3: Multi-Raft + range partitioning** | CP 8–11 | Range descriptors registry; one Raft group per range; range split protocol (no quorum loss during split); range scans served from a single leader |
| **Phase 4: Serializable single-key + leader leases + MVCC** | CP 12–14 | Time-based leader leases with clock-skew margin; local linearizable reads under lease; MVCC version layout for snapshot isolation reads; HLC-driven commit timestamps |
| **Phase 5: Joint consensus + log compaction + snapshot install** | CP 15–16 | Joint consensus for atomic multi-node membership change; periodic log compaction; `InstallSnapshot` RPC for catching up slow followers |

**Stop point:** After Phase 5. **Explicit non-goals at the stop point:** multi-key serializable transactions, follower reads, multi-DC topologies, observability backend bindings (Micrometer/Prometheus), Docker Compose / GKE packaging. Each of those is a substantial effort in its own right and deserves its own attention.

## Current Implementation Status

The implemented source currently covers the load-bearing path through Phase 3:

- Single-group Raft election, AppendEntries replication, commit advancement, and state-machine apply.
- Raft-backed KV writes with deterministic command encoding and client request idempotency in the state machine.
- Range descriptors, non-overlapping in-memory range registry, and a small Multi-Raft router that maps `key -> range -> Raft group`.
- RocksDB-backed storage inherited from the AP companion.

The later modules (`kv-leader-lease`, `kv-mvcc`, `kv-membership`, `kv-node`, `kv-client`, `kv-bench`, `kv-simulator`, `kv-admin`) are still planned/no-source modules unless a future checkpoint fills them in. They are kept in Gradle to preserve the intended architecture, but they should not be read as production-ready implementations.

## Companion Repo

- **AP companion (leaderless / Dynamo-Cassandra lineage):** [`distributed-key-value-store`](https://github.com/hemantkgupta/distributed-key-value-store) at SHA `0305211b` (checkpoint 21).
- **The architectural axis:** see `wiki/tradeoffs/leaderless-ap-vs-leader-cp-kv.md` in the CSE-Raw repo. The two implementations together span the full AP-vs-CP design space.

The honest framing: the AP repo was built first because Dynamo-style leaderless exposes the most mechanisms (token rings, vnodes, tunable consistency, sloppy quorum, hinted handoff, digest reads, read repair, Merkle anti-entropy, tombstones). The CP repo is built second because it's what the 2026 report recommends as the *production default* for petabyte-scale linearizable KV. Reading both and running both is the only way to internalize which family fits which problem.

## Module Map

| Module | Status | Purpose |
|---|---|---|
| `kv-common` | Implemented | Immutable key/value wrappers and `NodeId` |
| `kv-storage-api` | **Forked verbatim** | StorageEngine contract, StoredRecord |
| `kv-storage-rocksdb` | **Forked verbatim** | RocksDB-backed durable storage |
| `kv-storage-toy-lsm` | Package stub | Reserved for an educational toy LSM |
| `kv-raft-core` | Implemented (Phase 1) | Raft consensus primitive: log, leader election, AppendEntries, commit/apply |
| `kv-raft-kv` | Implemented (Phase 2) | KV state machine and thin server wrapper running on top of `kv-raft-core` |
| `kv-partitioning-range` | Implemented (Phase 3) | Range descriptors and non-overlapping in-memory range registry |
| `kv-multi-raft` | Implemented skeleton (Phase 3) | Key-to-range-to-Raft-group routing; split coordination is not implemented |
| `kv-leader-lease` | Planned (Phase 4) | Time-based leader leases with bounded clock skew |
| `kv-mvcc` | Planned (Phase 4) | MVCC versioned storage layout + snapshot read |
| `kv-membership` | Planned (Phase 5) | Joint consensus per Raft group + cluster-level membership |
| `kv-node` | Planned | Node runtime + HTTP transport (Multi-Raft-shaped) |
| `kv-client` | Planned (Phase 2+) | Leader-aware client with retry-on-not-leader |
| `kv-bench` | Placeholder | Future benchmarks |
| `kv-simulator` | Placeholder | Future deterministic simulation |
| `kv-admin` | Placeholder | Future admin CLI |

## Build

Standard Gradle multi-module build:

```bash
gradle build --console=plain
```

Java 17 (`jenv local 17` if jenv is in use). System Gradle ≥ 8.0. No Gradle wrapper is checked in (matching the AP companion's convention).

## License & Authorship

Same as the AP companion. Co-authored with Claude Opus.
