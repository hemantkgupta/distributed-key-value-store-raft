# Research Checkpoint — distributed-key-value-store-raft


The theoretical backbone the implementation rests on. Mirrors the AP repo's research-checkpoint shape (Foundation / Going Deeper / At Scale / Recommended Defaults) but rebased on Multi-Raft + range partitioning.

## Direction

Build the flagship CP artifact as a Multi-Raft / range-partitioned KV store:
- Leader-per-partition replication via Raft consensus.
- Range partitioning with online split/merge.
- Linearizable single-key reads via leader leases + MVCC.
- Serializable single-key writes by single-leader serialization.
- HLC for write ordering across replicas.
- Joint consensus for atomic membership change.

The leaderless AP and transactional FoundationDB-style families remain explicit contrast points and live in companion / future repos.

## Foundation

- **Raft protocol**: leader election (randomized timeouts), AppendEntries log replication, commit invariant via majority acknowledgment, Leader Completeness safety property.
- **Range descriptor**: `(rangeId, startKey, endKey, generation, voters, leaseholder, leaseExpiresAt)` as the unit of partition ownership.
- **Replication factor**: typically 3 voters per range, with optional learners catching up.
- **Storage substrate**: RocksDB-backed LSM (forked verbatim from AP companion) provides durability, compaction, WAL.
- **Client routing**: leader-aware client retries on `NotLeader` redirect; eventually consistent client-side view of the range registry.

## Going Deeper

- **Multi-Raft scales consensus** by sharding it across ranges. Per-range throughput is bounded by Raft's single-leader serialization; total cluster throughput is `(ranges) × (per-range throughput)`. Hot keys remain a problem (a single range can't be parallelized further without splitting).
- **Range splits without quorum loss** require a careful two-phase protocol: propose split via Raft entry on the old range; once committed, the new range is created with its own Raft group; the old range stops serving keys above the split point. Both halves maintain quorum throughout.
- **Leader leases** trade clock-skew assumption for read latency. A lease grant gives the leader exclusive right to serve linearizable local reads for the lease duration minus skew margin. Production systems run with leases of 5-9 seconds; shorter gives faster failover but higher renewal overhead.
- **MVCC for snapshot reads** stores `(key, commit_ts) -> value`. Reads at `read_ts` return the latest version where `commit_ts <= read_ts`. Garbage collection drops versions below a configurable threshold; the threshold must be > max snapshot-read age.
- **HLC for ordering** combines wall-clock and a Lamport-style counter. Causally correct under bounded clock skew. Practical alternative to TrueTime everywhere except GCP.
- **Joint consensus for membership change** transitions through a state `C_old,new` requiring quorum from both configurations. Safe under crash; intermediate state cannot produce two disjoint leaders.
- **Log compaction + snapshot install** prevent the Raft log from growing unboundedly. Periodically take a snapshot, truncate the log up to the snapshot index. Slow followers receive `InstallSnapshot` instead of replaying truncated entries.
- **Hedged reads** cut tail latency by sending a second-replica read after p95 timeout. 2-5% extra load for 5-10× p99 improvement.

## At Scale

- **Range count is the throughput-vs-overhead tradeoff.** Too few ranges → hot-range bottleneck; too many → per-range Raft overhead dominates. Production CockroachDB defaults to 256-512 MB per range; TiKV defaults to ~96 MB.
- **Leader-balance management** matters because every range's writes go through its leader. If leaders are unbalanced across nodes, some nodes are CPU-saturated while others idle. CockroachDB's "leaseholder rebalancing" moves leases (not data) to balance load.
- **Range hot-spotting** is the dominant production failure mode. A single hot range cannot be parallelized — the only fix is to split it, which may not help if all the heat is on one key. Application-level sharding (key namespacing) is the last resort.
- **Clock-skew bounds are load-bearing.** Lease-held reads are unsafe if real clock skew exceeds the configured bound. Monitor actual NTP drift; refuse to serve lease-held reads when drift exceeds the bound. Fall back to ReadIndex (quorum-confirmed) when uncertain.
- **Multi-DC is hard.** Single Multi-Raft groups spanning DCs incur cross-DC RTT on every write (50-200ms within a continent, 100-300ms across). Per-DC groups with async cross-DC replication is the common production compromise — gives up cross-DC linearizability.
- **Per-tenant noisy-neighbor isolation** is the production cost the textbook Raft paper does not address. Request budgets, per-tenant rate limits, and compaction throttling are operational requirements that must be added on top of the core consensus protocol.

## Recommended Defaults

- **Replication factor**: 3 voters per range. Adds redundancy without dramatically increasing cross-replica latency.
- **Range size**: 64-256 MB per range. Hot ranges split automatically when they exceed the upper threshold.
- **Lease duration**: 5 seconds. Long enough to amortize renewal overhead; short enough that failover takes < 10 seconds.
- **Clock-skew margin**: 500 ms. Generous; tightens if NTP drift monitoring shows lower skew is reliable.
- **Election timeout**: 150-300 ms randomized. Standard Raft defaults.
- **Heartbeat interval**: 50 ms. ~10 heartbeats per election timeout for liveness.
- **Log compaction threshold**: 64 MB or 10⁵ entries, whichever comes first.
- **Snapshot retention**: 2 snapshots (current + previous, for safety).
- **HLC bound**: 500 ms (matches lease skew margin).
- **Multi-key transactions**: out-of-scope at the Phase 5 stop point. If needed later, add via parallel commits across Raft groups (CockroachDB style).
