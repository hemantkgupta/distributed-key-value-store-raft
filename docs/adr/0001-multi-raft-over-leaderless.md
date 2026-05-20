# ADR 0001: Primary Design Family — Multi-Raft over Leaderless


## Status

Accepted for initial implementation.

## Decision

Build a **leader-per-partition Multi-Raft** key-value store as the primary artifact of this repo, with **range partitioning + leader leases + MVCC** as the load-bearing supporting decisions.

## Context

The KV-store design space can be implemented as one of three families:

1. **Leaderless AP** (Dynamo, Cassandra, Riak, ScyllaDB) — token-ring partitioning, coordinator-fanout writes, tunable consistency, convergence via hints + read repair + Merkle anti-entropy.
2. **Leader-per-partition CP** (CockroachDB, TiKV, Spanner, FoundationDB) — range partitioning, Raft-replicated state machine per range, linearizable single-key by construction.
3. **Transactional KV with deterministic SSI** (FoundationDB) — sequencer + resolvers + log servers + storage servers; SSI through OCC with deterministic conflict detection.

These are different products, not configuration variants of one design. The AP family is implemented in the companion repo [`distributed-key-value-store`](https://github.com/hemantkgupta/distributed-key-value-store). This repo implements family 2.

The 2026 deep-research report at `raw/articles/distributed-key-value-store-deep-research-report.md` (in the CSE-Raw wiki repo) explicitly recommends family 2 as the production default for petabyte-scale linearizable KV. Family 3 is the transactional generalization but adds substantial operational complexity (five distinct server roles instead of one) and is reserved for workloads that genuinely need multi-key serializable transactions across arbitrary key ranges.

## Rationale

The argument for Multi-Raft over leaderless AP for this repo's purpose:

- **Linearizable semantics by construction.** Single-leader-per-partition serializes writes; readers under leader lease see the latest write. The leaderless repo achieves this only at `ALL` consistency, which sacrifices write availability.
- **Range partitioning supports range scans efficiently.** A range scan from `k1` to `k2` is served by the leader of the range containing `[k1, k2)`. With vnode hashing, the same scan requires fanout to every node that could own any key in the range.
- **Online split/merge enables elastic scaling.** Hot ranges split into smaller groups; cold adjacent ranges merge. This is genuinely impossible in pure vnode-hashed systems without rebalancing the entire ring.
- **Multi-key transactions (post-stop-point) become tractable.** Multi-Raft + 2PC across groups is the Spanner/CockroachDB pattern. Adding transactions to the leaderless repo would require lightweight transactions via Paxos (Cassandra's LWT path), at quorum-write latency.

The argument **against** family 2 — which we acknowledge but accept:

- **Write availability under partition is sacrificed.** A Raft group in the minority partition stops accepting writes for that range. The AP repo continues serving writes from any reachable replica. For workloads where "always writable" is paramount, this repo is the wrong choice.
- **Operational subtlety is higher.** Leader balance management, range hot-spotting mitigation, leader-lease tuning, joint consensus during membership changes — all are real production concerns Multi-Raft systems must manage. The AP repo trades these for repair-cadence and tombstone-GC management.
- **Cross-DC linearizability is expensive.** Single Multi-Raft groups spanning DCs incur cross-DC RTT on every write. Async cross-DC replication (the production compromise) gives up linearizability across DCs.

## Supporting Decisions

- **Range partitioning over vnode hashing.** Range partitioning enables online split/merge and single-owner range scans. Vnode hashing distributes load by hashing but cannot split a hot range.
- **Leader leases over quorum-confirmed reads.** Reads under lease are sub-millisecond local operations; quorum-confirmed reads are RTT-bounded. The tradeoff is a clock-skew assumption (default 500ms margin); we accept it and require NTP-disciplined clocks.
- **HLC over TrueTime.** TrueTime requires GPS + atomic clock hardware (GCP-only); HLC is the production default everywhere else. We achieve strong consistency (not external consistency) across replicas.
- **MVCC + single-key serializable in stop-point scope.** Multi-key transactions are an explicit post-stop-point goal. Single-key serializable via leader serialization + MVCC reads is sufficient for the educational target.
- **Joint consensus over single-server addition for membership change.** Single-server addition is simpler but doesn't support bulk membership changes (e.g., replacing an entire AZ atomically). Joint consensus is the cleaner protocol for the educational goal.

## Consequences

- The data path requires a consensus group per range. Throughput scales by sharding consensus.
- The storage layer is reused verbatim from the AP repo at SHA `0305211b` — it's genuinely orthogonal to replication topology. Only namespaces are rewritten to avoid classpath collision.
- The code-companion documentation (planned `docs/code-companion.md`) will be filled in as phases land, mapping each guide section to the implementing modules and tests.
- Multi-key transactions, follower reads, multi-DC topologies, and observability backend bindings are explicit non-goals at the Phase 5 stop point. Each is a separate effort.

## Related Documents

- [`implementation-plan.md`](../implementation-plan.md) — the 5-phase plan with per-CP deliverables.
- AP companion repo: [`distributed-key-value-store`](https://github.com/hemantkgupta/distributed-key-value-store).
- Centerpiece tradeoff page: `wiki/tradeoffs/leaderless-ap-vs-leader-cp-kv.md` in CSE-Raw.
- Deep-research report: `raw/articles/distributed-key-value-store-deep-research-report.md` in CSE-Raw.
