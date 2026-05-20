package com.hkg.kvraft.raft;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RaftServerTest {

    @Test
    void singleNodeBecomesLeaderOnElectionTimeout() {
        TestCluster cluster = TestCluster.of("N1");
        RaftServer n1 = cluster.server("N1");

        cluster.advance(200);

        assertThat(n1.state()).isEqualTo(RaftState.LEADER);
        assertThat(n1.currentTerm()).isEqualTo(1);
    }

    @Test
    void threeNodeClusterElectsOneLeader() {
        TestCluster cluster = TestCluster.of("N1", "N2", "N3");

        cluster.advance(500);

        long leaders = cluster.servers().stream()
                .filter(s -> s.state() == RaftState.LEADER)
                .count();
        assertThat(leaders).isEqualTo(1);
    }

    @Test
    void leaderHeartbeatsKeepFollowersInFollower() {
        TestCluster cluster = TestCluster.of("N1", "N2", "N3");
        cluster.advance(500);

        // Pick the elected leader, then advance many ticks. Followers
        // should stay followers (heartbeats reset their election deadline).
        RaftServer leader = cluster.leaderOrFail();
        cluster.advance(2000);

        assertThat(leader.state()).isEqualTo(RaftState.LEADER);
        long followerCount = cluster.servers().stream()
                .filter(s -> s.state() == RaftState.FOLLOWER)
                .count();
        assertThat(followerCount).isEqualTo(2);
    }

    @Test
    void submittedCommandIsReplicatedAndApplied() {
        TestCluster cluster = TestCluster.of("N1", "N2", "N3");
        cluster.advance(500);
        RaftServer leader = cluster.leaderOrFail();

        RaftServer.SubmitResult r = leader.submit("hello".getBytes(), "req-1");
        cluster.advance(500); // let replication + commit happen

        assertThat(r.accepted()).isTrue();
        assertThat(r.index()).isEqualTo(1);

        // All three state machines should have applied the entry.
        for (RaftServer s : cluster.servers()) {
            List<String> applied = cluster.appliedCommands(s.selfId());
            assertThat(applied).containsExactly("hello");
        }
    }

    @Test
    void submitToNonLeaderReturnsNotLeader() {
        TestCluster cluster = TestCluster.of("N1", "N2", "N3");
        cluster.advance(500);
        RaftServer leader = cluster.leaderOrFail();
        RaftServer follower = cluster.servers().stream()
                .filter(s -> s != leader)
                .findFirst().orElseThrow();

        RaftServer.SubmitResult r = follower.submit("x".getBytes(), "req-x");

        assertThat(r.accepted()).isFalse();
        assertThat(r.knownLeader()).isEqualTo(leader.selfId());
    }

    @Test
    void multipleCommittedEntriesPreserveOrder() {
        TestCluster cluster = TestCluster.of("N1", "N2", "N3");
        cluster.advance(500);
        RaftServer leader = cluster.leaderOrFail();

        leader.submit("a".getBytes(), "req-a");
        leader.submit("b".getBytes(), "req-b");
        leader.submit("c".getBytes(), "req-c");
        cluster.advance(500);

        for (RaftServer s : cluster.servers()) {
            assertThat(cluster.appliedCommands(s.selfId()))
                    .containsExactly("a", "b", "c");
        }
    }

    @Test
    void higherTermDuringElectionStepsCandidateDown() {
        TestCluster cluster = TestCluster.of("N1", "N2", "N3");
        cluster.advance(500);
        RaftServer leader = cluster.leaderOrFail();

        // Simulate a peer at a higher term sending a vote response.
        long initialTerm = leader.currentTerm();
        RequestVoteResult fakeHigherTerm =
                new RequestVoteResult(initialTerm + 10, false);
        cluster.injectRequestVoteResponseToCandidate(leader.selfId(), fakeHigherTerm);

        // The leader hasn't observed it yet (responses arrive on RPC return).
        // Instead: directly call handleRequestVote with a higher term — that's
        // the canonical step-down path.
        RequestVoteArgs args = new RequestVoteArgs(
                initialTerm + 10,
                "outsider",
                leader.log().lastIndex(),
                leader.log().lastTerm());
        RequestVoteResult result = leader.handleRequestVote(args);

        assertThat(leader.currentTerm()).isEqualTo(initialTerm + 10);
        assertThat(leader.state()).isEqualTo(RaftState.FOLLOWER);
        // Vote granted because we stepped down with empty votedFor and
        // outsider's log isn't behind ours (same length, same last term).
        assertThat(result.voteGranted()).isTrue();
    }

    @Test
    void candidateRejectsVoteIfItsLogIsBehind() {
        TestCluster cluster = TestCluster.of("N1", "N2", "N3");
        cluster.advance(500);
        RaftServer leader = cluster.leaderOrFail();

        leader.submit("a".getBytes(), "req-a");
        leader.submit("b".getBytes(), "req-b");
        cluster.advance(500);

        // Now request a vote from leader at a higher term but with stale log.
        long term = leader.currentTerm() + 5;
        RequestVoteArgs stale = new RequestVoteArgs(term, "stale", 0, 0);
        RequestVoteResult result = leader.handleRequestVote(stale);

        // term advanced (step-down happens) but vote denied due to log.
        assertThat(leader.currentTerm()).isEqualTo(term);
        assertThat(result.voteGranted()).isFalse();
    }

    @Test
    void appendEntriesWithStaleTermIsRejected() {
        TestCluster cluster = TestCluster.of("N1", "N2", "N3");
        cluster.advance(500);
        RaftServer follower = cluster.servers().stream()
                .filter(s -> s.state() == RaftState.FOLLOWER)
                .findFirst().orElseThrow();

        long currentTerm = follower.currentTerm();
        AppendEntriesArgs stale = new AppendEntriesArgs(
                currentTerm - 1, "fake-leader", 0, 0, List.of(), 0);
        AppendEntriesResult result = follower.handleAppendEntries(stale);

        assertThat(result.success()).isFalse();
        assertThat(result.term()).isEqualTo(currentTerm);
    }

    // ----- Test helper: a deterministic in-process cluster -----

    private static final class TestCluster {
        private final Map<String, RaftServer> servers = new HashMap<>();
        private final Map<String, RecordingStateMachine> stateMachines = new HashMap<>();
        private final FakeClock clock = new FakeClock();
        private final InProcessTransport transport;

        private TestCluster(List<String> nodeIds) {
            this.transport = new InProcessTransport(servers);
            for (String id : nodeIds) {
                RecordingStateMachine sm = new RecordingStateMachine();
                stateMachines.put(id, sm);
                List<String> peers = new ArrayList<>(nodeIds);
                peers.remove(id);
                RaftConfig config = new RaftConfig(
                        id,
                        peers,
                        Duration.ofMillis(100),
                        Duration.ofMillis(200),
                        Duration.ofMillis(30));
                RaftServer server = new RaftServer(
                        config,
                        new InMemoryRaftLog(),
                        sm,
                        transport,
                        clock);
                servers.put(id, server);
            }
        }

        static TestCluster of(String... ids) {
            return new TestCluster(List.of(ids));
        }

        RaftServer server(String id) {
            return servers.get(id);
        }

        List<RaftServer> servers() {
            return new ArrayList<>(servers.values());
        }

        List<String> appliedCommands(String nodeId) {
            return stateMachines.get(nodeId).appliedCommands();
        }

        RaftServer leaderOrFail() {
            return servers.values().stream()
                    .filter(s -> s.state() == RaftState.LEADER)
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("no leader elected"));
        }

        /** Advance the cluster by the given number of logical milliseconds. */
        void advance(long ms) {
            long end = clock.now() + ms;
            while (clock.now() < end) {
                clock.advance(10);
                for (RaftServer s : servers.values()) {
                    s.tick(clock.now());
                }
            }
        }

        /** No-op placeholder kept for symmetry with future fault-injection helpers. */
        void injectRequestVoteResponseToCandidate(String selfId, RequestVoteResult result) {
            // Intentionally empty; the test uses handleRequestVote directly.
        }
    }

    /** Deterministic clock with seeded random election timeout. */
    private static final class FakeClock implements RaftClock {
        private long now = 0;
        private final java.util.Random random = new java.util.Random(42);

        @Override
        public long now() {
            return now;
        }

        @Override
        public Duration randomElectionTimeout() {
            // Match RaftConfig's 100-200ms range.
            return Duration.ofMillis(100 + random.nextInt(101));
        }

        void advance(long ms) {
            now += ms;
        }
    }

    /** State machine that records applied commands as strings. */
    private static final class RecordingStateMachine implements ReplicatedStateMachine {
        private final List<String> applied = new ArrayList<>();

        @Override
        public void apply(LogEntry entry) {
            applied.add(new String(entry.command()));
        }

        List<String> appliedCommands() {
            return new ArrayList<>(applied);
        }
    }

    /** Direct in-process RPC dispatch. No network, no scheduling. */
    private static final class InProcessTransport implements RaftTransport {
        private final Map<String, RaftServer> servers;

        InProcessTransport(Map<String, RaftServer> servers) {
            this.servers = servers;
        }

        @Override
        public RequestVoteResult requestVote(String peerId, RequestVoteArgs args) {
            RaftServer peer = servers.get(peerId);
            if (peer == null) {
                return new RequestVoteResult(0, false);
            }
            return peer.handleRequestVote(args);
        }

        @Override
        public AppendEntriesResult appendEntries(String peerId, AppendEntriesArgs args) {
            RaftServer peer = servers.get(peerId);
            if (peer == null) {
                return new AppendEntriesResult(0, false, 0);
            }
            return peer.handleAppendEntries(args);
        }
    }
}
