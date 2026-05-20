# Code Companion — distributed-key-value-store-raft


This file maps the future Complete Engineering Guide sections to code locations. It is a *living* document, updated as each phase lands.

| Guide Section | Code Location | Status |
|---|---|---|
| Foundation: API and data model | `kv-common`, `kv-storage-api` | Planned (Phase 1 / forked) |
| Foundation: durable single-node storage | `kv-storage-rocksdb` | **Forked verbatim** from AP repo SHA 0305211b (namespace rewritten to `com.hkg.kvraft.storage.rocksdb`) |
| Foundation: Raft log + persistence | `kv-raft-core` | Planned (Phase 1, CP 1) |
| Foundation: Leader election | `kv-raft-core` | Planned (Phase 1, CP 2) |
| Foundation: AppendEntries + commit invariant | `kv-raft-core` | Planned (Phase 1, CP 3) |
| Foundation: Replicated state machine boundary | `kv-raft-core` | Planned (Phase 1, CP 4) |
| Going Deeper: KV state machine on Raft | `kv-raft-kv` | Planned (Phase 2, CP 5) |
| Going Deeper: Client request idempotency | `kv-raft-kv` | Planned (Phase 2, CP 6) |
| Going Deeper: Leader-aware client | `kv-client` | Planned (Phase 2, CP 7) |
| Going Deeper: Range descriptors and registry | `kv-partitioning-range` | Planned (Phase 3, CP 8) |
| Going Deeper: Multi-Raft cluster glue | `kv-multi-raft` | Planned (Phase 3, CP 9) |
| Going Deeper: Range scans | `kv-multi-raft`, `kv-partitioning-range` | Planned (Phase 3, CP 10) |
| Going Deeper: Range split protocol | `kv-multi-raft`, `kv-partitioning-range` | Planned (Phase 3, CP 11) |
| Going Deeper: Leader leases | `kv-leader-lease` | Planned (Phase 4, CP 12) |
| Going Deeper: MVCC versioned storage | `kv-mvcc` | Planned (Phase 4, CP 13) |
| Going Deeper: Serializable single-key + HLC | `kv-mvcc`, `kv-common` | Planned (Phase 4, CP 14) |
| At Scale: Joint consensus | `kv-membership` | Planned (Phase 5, CP 15) |
| At Scale: Log compaction + snapshot install | `kv-raft-core`, `kv-membership` | Planned (Phase 5, CP 16) |
| At Scale: deterministic simulation | `kv-simulator` | Out of scope (post-stop-point) |
| At Scale: Local Compose runtime | `deploy/compose` | Out of scope (post-stop-point) |
| At Scale: GCP/GKE deployment | `deploy/gke` | Out of scope (post-stop-point) |

## Sync Rule

When the future guide claims a mechanism exists, this companion must point to the file or test that implements it. If the code only simulates a production behavior locally, say so here and in the guide.

## Per-Phase Code Map

Filled in as each phase lands. After Phase 1: list each Java file in `kv-raft-core/` and what it implements. Mirror the AP companion's pattern.

---

This document is the bridge between blog text and Java code. Updates land in the same commit as the code they document.
