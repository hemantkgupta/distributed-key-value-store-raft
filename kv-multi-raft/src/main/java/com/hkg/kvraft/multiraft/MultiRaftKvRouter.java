package com.hkg.kvraft.multiraft;

import com.hkg.kvraft.common.Key;
import com.hkg.kvraft.common.Value;
import com.hkg.kvraft.kv.KvServer;
import com.hkg.kvraft.partitioning.RangeDescriptor;
import com.hkg.kvraft.partitioning.RangeId;
import com.hkg.kvraft.partitioning.RangeRegistry;
import com.hkg.kvraft.raft.RaftState;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Routes client key operations to the Raft group that owns the key's range.
 *
 * <p>This is intentionally small: split orchestration, membership changes,
 * and remote RPC fan-out belong in later modules. The invariant this class
 * enforces is the core Multi-Raft shape: key -> range descriptor -> Raft group.
 */
public final class MultiRaftKvRouter {

    private final RangeRegistry rangeRegistry;
    private final Map<RangeId, KvServer> groupsByRange;

    public MultiRaftKvRouter(RangeRegistry rangeRegistry, Map<RangeId, KvServer> groupsByRange) {
        this.rangeRegistry = Objects.requireNonNull(rangeRegistry, "rangeRegistry");
        this.groupsByRange = Map.copyOf(Objects.requireNonNull(groupsByRange, "groupsByRange"));
    }

    public KvServer.KvResult put(Key key, Value value, String clientRequestId) {
        return serverFor(key).put(key, value, clientRequestId);
    }

    public KvServer.KvResult delete(Key key, String clientRequestId) {
        return serverFor(key).delete(key, clientRequestId);
    }

    /**
     * Serve local reads only from the current leader for the range. Later leader
     * leases can strengthen this from "leader-local" to linearizable.
     */
    public KvServer.KvResult get(Key key) {
        KvServer server = serverFor(key);
        if (server.raft().state() != RaftState.LEADER) {
            return KvServer.KvResult.notLeader(server.raft().currentLeader().orElse(null));
        }
        return server.get(key);
    }

    public Optional<RangeDescriptor> lookupRange(Key key) {
        Objects.requireNonNull(key, "key");
        return rangeRegistry.lookup(key);
    }

    private KvServer serverFor(Key key) {
        RangeDescriptor descriptor = rangeRegistry.lookup(Objects.requireNonNull(key, "key"))
                .orElseThrow(() -> new IllegalArgumentException("no range owns key " + key));
        KvServer server = groupsByRange.get(descriptor.rangeId());
        if (server == null) {
            throw new IllegalStateException(
                    "no local Raft group is registered for range " + descriptor.rangeId().id());
        }
        return server;
    }
}
