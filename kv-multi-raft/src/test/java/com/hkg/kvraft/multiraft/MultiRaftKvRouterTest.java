package com.hkg.kvraft.multiraft;

import com.hkg.kvraft.common.Key;
import com.hkg.kvraft.common.NodeId;
import com.hkg.kvraft.common.Value;
import com.hkg.kvraft.kv.KvServer;
import com.hkg.kvraft.kv.RaftKvStateMachine;
import com.hkg.kvraft.partitioning.InMemoryRangeRegistry;
import com.hkg.kvraft.partitioning.RangeDescriptor;
import com.hkg.kvraft.partitioning.RangeId;
import com.hkg.kvraft.raft.AppendEntriesArgs;
import com.hkg.kvraft.raft.AppendEntriesResult;
import com.hkg.kvraft.raft.InMemoryRaftLog;
import com.hkg.kvraft.raft.RaftClock;
import com.hkg.kvraft.raft.RaftConfig;
import com.hkg.kvraft.raft.RaftServer;
import com.hkg.kvraft.raft.RaftTransport;
import com.hkg.kvraft.raft.RequestVoteArgs;
import com.hkg.kvraft.raft.RequestVoteResult;
import com.hkg.kvraft.storage.MutationRecord;
import com.hkg.kvraft.storage.StorageEngine;
import com.hkg.kvraft.storage.StoredRecord;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MultiRaftKvRouterTest {

    private final NodeId n1 = new NodeId("N1");
    private final NodeId n2 = new NodeId("N2");
    private final NodeId n3 = new NodeId("N3");

    @Test
    void routesWritesAndReadsToOwningRangeGroup() {
        TestGroup left = TestGroup.leader("left");
        TestGroup right = TestGroup.leader("right");
        MultiRaftKvRouter router = router(
                Map.of(RangeId.of(1), left.server(), RangeId.of(2), right.server()));

        KvServer.KvResult leftPut = router.put(key("apple"), value("red"), "req-left");
        KvServer.KvResult rightPut = router.put(key("zebra"), value("black-white"), "req-right");

        assertThat(leftPut.accepted()).isTrue();
        assertThat(rightPut.accepted()).isTrue();
        assertThat(router.get(key("apple")).response().value()).contains(value("red"));
        assertThat(router.get(key("zebra")).response().value()).contains(value("black-white"));
        assertThat(left.storage().records).containsOnlyKeys(key("apple"));
        assertThat(right.storage().records).containsOnlyKeys(key("zebra"));
    }

    @Test
    void deleteIsRoutedToOwningRangeGroup() {
        TestGroup left = TestGroup.leader("left");
        TestGroup right = TestGroup.leader("right");
        MultiRaftKvRouter router = router(
                Map.of(RangeId.of(1), left.server(), RangeId.of(2), right.server()));

        router.put(key("zebra"), value("black-white"), "req-put");
        KvServer.KvResult deleted = router.delete(key("zebra"), "req-delete");

        assertThat(deleted.accepted()).isTrue();
        assertThat(router.get(key("zebra")).response().value()).isEmpty();
        assertThat(left.storage().records).isEmpty();
        assertThat(right.storage().records.get(key("zebra")).tombstone()).isTrue();
    }

    @Test
    void readFromNonLeaderRangeReturnsNotLeader() {
        TestGroup followerOnly = TestGroup.follower("left");
        MultiRaftKvRouter router = router(Map.of(RangeId.of(1), followerOnly.server()));

        KvServer.KvResult result = router.get(key("apple"));

        assertThat(result.accepted()).isFalse();
        assertThat(result.knownLeader()).isNull();
    }

    @Test
    void missingRangeGroupIsAnInvariantViolation() {
        MultiRaftKvRouter router = router(Map.of());

        assertThatThrownBy(() -> router.put(key("apple"), value("red"), "req"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no local Raft group is registered");
    }

    @Test
    void lookupRangeExposesRegistryDecision() {
        MultiRaftKvRouter router = router(Map.of(RangeId.of(1), TestGroup.leader("left").server()));

        assertThat(router.lookupRange(key("apple")))
                .map(d -> d.rangeId())
                .contains(RangeId.of(1));
    }

    private MultiRaftKvRouter router(Map<RangeId, KvServer> groups) {
        InMemoryRangeRegistry registry = new InMemoryRangeRegistry();
        registry.put(new RangeDescriptor(
                RangeId.of(1),
                Optional.empty(),
                Optional.of(key("m")),
                1,
                List.of(n1, n2, n3),
                n1));
        registry.put(new RangeDescriptor(
                RangeId.of(2),
                Optional.of(key("m")),
                Optional.empty(),
                1,
                List.of(n1, n2, n3),
                n2));
        return new MultiRaftKvRouter(registry, groups);
    }

    private static Key key(String key) {
        return Key.utf8(key);
    }

    private static Value value(String value) {
        return new Value(value.getBytes());
    }

    private record TestGroup(KvServer server, FakeStorage storage) {
        static TestGroup leader(String id) {
            TestGroup group = follower(id);
            group.server.raft().tick(200);
            return group;
        }

        static TestGroup follower(String id) {
            FakeStorage storage = new FakeStorage();
            RaftKvStateMachine stateMachine = new RaftKvStateMachine(storage);
            RaftServer raft = new RaftServer(
                    new RaftConfig(
                            id,
                            List.of(),
                            Duration.ofMillis(100),
                            Duration.ofMillis(200),
                            Duration.ofMillis(30)),
                    new InMemoryRaftLog(),
                    stateMachine,
                    new NoopTransport(),
                    new FixedClock());
            return new TestGroup(new KvServer(raft, stateMachine), storage);
        }
    }

    private static final class FixedClock implements RaftClock {
        @Override
        public long now() {
            return 0;
        }

        @Override
        public Duration randomElectionTimeout() {
            return Duration.ofMillis(100);
        }
    }

    private static final class NoopTransport implements RaftTransport {
        @Override
        public RequestVoteResult requestVote(String peerId, RequestVoteArgs args) {
            return new RequestVoteResult(args.term(), false);
        }

        @Override
        public AppendEntriesResult appendEntries(String peerId, AppendEntriesArgs args) {
            return new AppendEntriesResult(args.term(), false, 0);
        }
    }

    private static final class FakeStorage implements StorageEngine {
        private final Map<Key, StoredRecord> records = new HashMap<>();

        @Override
        public void apply(MutationRecord mutation) {
            records.put(mutation.key(), StoredRecord.from(mutation));
        }

        @Override
        public Optional<StoredRecord> get(Key key) {
            return Optional.ofNullable(records.get(key));
        }

        @Override
        public List<StoredRecord> scanAll() {
            return List.copyOf(records.values());
        }

        @Override
        public byte[] digest(Key key) {
            return new byte[0];
        }

        @Override
        public void close() {
        }
    }
}
