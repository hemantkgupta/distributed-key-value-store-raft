package com.hkg.kvraft.raft;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * A single Raft server. Holds the persistent state (current term, voted-for,
 * log) and volatile state (commit index, last applied, leader's
 * per-follower next/match indices). Drives state transitions via
 * {@link #tick(long)} — tests call this directly; production wraps it in
 * a scheduled executor that ticks every few milliseconds.
 *
 * <p>This implementation is single-threaded internal: callers must
 * serialize all method calls. The intended use is one thread per server
 * driving {@code tick} plus {@code handleRequestVote} / {@code handleAppendEntries}
 * from the transport layer's RPC handlers.
 */
public final class RaftServer {

    private final RaftConfig config;
    private final RaftLog log;
    private final ReplicatedStateMachine stateMachine;
    private final RaftTransport transport;
    private final RaftClock clock;

    // Persistent state (would be on disk in production; in memory here for tests).
    private long currentTerm = 0;
    private String votedFor = null;

    // Volatile state.
    private RaftState state = RaftState.FOLLOWER;
    private long commitIndex = 0;
    private long lastApplied = 0;
    private String currentLeader = null;

    // Leader-only volatile state.
    private final Map<String, Long> nextIndex = new HashMap<>();
    private final Map<String, Long> matchIndex = new HashMap<>();

    // Timer state.
    private long electionDeadline;
    private long nextHeartbeatAt;

    public RaftServer(
            RaftConfig config,
            RaftLog log,
            ReplicatedStateMachine stateMachine,
            RaftTransport transport,
            RaftClock clock) {
        this.config = Objects.requireNonNull(config);
        this.log = Objects.requireNonNull(log);
        this.stateMachine = Objects.requireNonNull(stateMachine);
        this.transport = Objects.requireNonNull(transport);
        this.clock = Objects.requireNonNull(clock);
        resetElectionDeadline();
    }

    // ----- Accessors (for tests and external inspection) -----

    public String selfId() {
        return config.selfId();
    }

    public RaftState state() {
        return state;
    }

    public long currentTerm() {
        return currentTerm;
    }

    public Optional<String> votedFor() {
        return Optional.ofNullable(votedFor);
    }

    public long commitIndex() {
        return commitIndex;
    }

    public long lastApplied() {
        return lastApplied;
    }

    public Optional<String> currentLeader() {
        return Optional.ofNullable(currentLeader);
    }

    public RaftLog log() {
        return log;
    }

    // ----- Tick: driven externally; advances election / heartbeat timers -----

    /**
     * Drive the server forward by one time step. Called periodically by
     * a scheduler. Times are logical milliseconds — pass {@code clock.now()}
     * normally; tests pass synthetic times for determinism.
     */
    public void tick(long nowMs) {
        // Apply newly-committed entries before doing election/replication work,
        // so the state machine reflects everything the cluster has agreed on.
        applyCommitted();

        if (state == RaftState.LEADER) {
            if (nowMs >= nextHeartbeatAt) {
                sendAppendEntriesToAll();
                nextHeartbeatAt = nowMs + config.heartbeatInterval().toMillis();
            }
        } else {
            if (nowMs >= electionDeadline) {
                startElection();
            }
        }
    }

    // ----- Client command submission -----

    /**
     * Submit a client command to the leader. If this server is not the
     * leader, returns {@link SubmitResult#notLeader()} so the client can
     * retry against the known leader (if any).
     */
    public SubmitResult submit(byte[] command, String clientRequestId) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(clientRequestId, "clientRequestId");
        if (state != RaftState.LEADER) {
            return SubmitResult.notLeader(currentLeader);
        }
        long index = log.lastIndex() + 1;
        LogEntry entry = new LogEntry(currentTerm, index, command, clientRequestId);
        log.append(entry);
        matchIndex.put(config.selfId(), index);
        // Eagerly try to replicate; followers will get this in the next AppendEntries.
        sendAppendEntriesToAll();
        return SubmitResult.accepted(index, currentTerm);
    }

    // ----- RPC handlers (called by the transport's RPC dispatch) -----

    public RequestVoteResult handleRequestVote(RequestVoteArgs args) {
        // Step down if a higher term is observed.
        maybeStepDown(args.term());

        if (args.term() < currentTerm) {
            return new RequestVoteResult(currentTerm, false);
        }
        boolean canVote = (votedFor == null) || votedFor.equals(args.candidateId());
        boolean logUpToDate = candidateLogIsUpToDate(args.lastLogIndex(), args.lastLogTerm());
        if (canVote && logUpToDate) {
            votedFor = args.candidateId();
            resetElectionDeadline();
            return new RequestVoteResult(currentTerm, true);
        }
        return new RequestVoteResult(currentTerm, false);
    }

    public AppendEntriesResult handleAppendEntries(AppendEntriesArgs args) {
        // Step down if a higher term is observed.
        maybeStepDown(args.term());

        if (args.term() < currentTerm) {
            return new AppendEntriesResult(currentTerm, false, 0);
        }
        // Recognize the leader for this term.
        state = RaftState.FOLLOWER;
        currentLeader = args.leaderId();
        resetElectionDeadline();

        // Consistency check: our log at prevLogIndex must have prevLogTerm.
        if (args.prevLogIndex() > 0) {
            Optional<LogEntry> prev = log.get(args.prevLogIndex());
            if (prev.isEmpty() || prev.get().term() != args.prevLogTerm()) {
                return new AppendEntriesResult(currentTerm, false, 0);
            }
        }

        // Append entries, truncating any conflicting suffix.
        long nextExpectedIndex = args.prevLogIndex() + 1;
        for (LogEntry entry : args.entries()) {
            Optional<LogEntry> existing = log.get(entry.index());
            if (existing.isPresent() && existing.get().term() != entry.term()) {
                // Conflict — truncate from this entry onward, then append.
                log.truncateAfter(entry.index() - 1);
            }
            if (log.lastIndex() < entry.index()) {
                log.append(entry);
            }
            nextExpectedIndex = entry.index() + 1;
        }

        // Advance commit index: min(leaderCommit, last new entry index).
        long lastNewIndex = nextExpectedIndex - 1;
        if (args.leaderCommit() > commitIndex) {
            commitIndex = Math.min(args.leaderCommit(), Math.max(lastNewIndex, log.lastIndex()));
        }

        applyCommitted();
        return new AppendEntriesResult(currentTerm, true, log.lastIndex());
    }

    // ----- Internal: state transitions -----

    private void startElection() {
        state = RaftState.CANDIDATE;
        currentTerm++;
        votedFor = config.selfId();
        currentLeader = null;
        resetElectionDeadline();

        int votes = 1; // vote for self
        RequestVoteArgs args = new RequestVoteArgs(
                currentTerm,
                config.selfId(),
                log.lastIndex(),
                log.lastTerm());

        for (String peer : config.peers()) {
            try {
                RequestVoteResult result = transport.requestVote(peer, args);
                if (result.term() > currentTerm) {
                    stepDownTo(result.term());
                    return;
                }
                if (result.voteGranted() && state == RaftState.CANDIDATE) {
                    votes++;
                }
            } catch (RaftTransportException e) {
                // Unreachable peer: treat as no vote. Next election will retry.
            }
        }

        if (state == RaftState.CANDIDATE && votes >= config.majoritySize()) {
            becomeLeader();
        }
    }

    private void becomeLeader() {
        state = RaftState.LEADER;
        currentLeader = config.selfId();
        // Initialize leader state: nextIndex = lastIndex+1, matchIndex = 0.
        long nextIdx = log.lastIndex() + 1;
        nextIndex.clear();
        matchIndex.clear();
        for (String peer : config.peers()) {
            nextIndex.put(peer, nextIdx);
            matchIndex.put(peer, 0L);
        }
        matchIndex.put(config.selfId(), log.lastIndex());
        nextHeartbeatAt = clock.now();
        sendAppendEntriesToAll();
    }

    private void sendAppendEntriesToAll() {
        for (String peer : config.peers()) {
            sendAppendEntries(peer);
        }
        // After replication round, see if we can advance commit.
        maybeAdvanceCommitIndex();
    }

    private void sendAppendEntries(String peer) {
        long next = nextIndex.getOrDefault(peer, log.lastIndex() + 1);
        long prevIdx = next - 1;
        long prevTerm = prevIdx > 0
                ? log.get(prevIdx).map(LogEntry::term).orElse(0L)
                : 0;
        List<LogEntry> entries = log.entriesFrom(next);
        AppendEntriesArgs args = new AppendEntriesArgs(
                currentTerm,
                config.selfId(),
                prevIdx,
                prevTerm,
                entries,
                commitIndex);
        try {
            AppendEntriesResult result = transport.appendEntries(peer, args);
            if (result.term() > currentTerm) {
                stepDownTo(result.term());
                return;
            }
            if (result.success()) {
                matchIndex.put(peer, result.matchIndex());
                nextIndex.put(peer, result.matchIndex() + 1);
            } else if (state == RaftState.LEADER) {
                // Back off and retry with an earlier prevIndex next round.
                nextIndex.put(peer, Math.max(1, next - 1));
            }
        } catch (RaftTransportException e) {
            // Unreachable peer: leave nextIndex unchanged; next heartbeat will retry.
        }
    }

    private void maybeAdvanceCommitIndex() {
        if (state != RaftState.LEADER) return;
        // For each index N > commitIndex, if a majority of matchIndex[i] >= N
        // AND log[N].term == currentTerm (Raft §5.4.2: never commit prior-term entries
        // by counting replicas), advance commitIndex to N.
        long latest = log.lastIndex();
        for (long n = latest; n > commitIndex; n--) {
            Optional<LogEntry> entry = log.get(n);
            if (entry.isEmpty() || entry.get().term() != currentTerm) continue;
            int count = 0;
            for (String node : matchIndex.keySet()) {
                if (matchIndex.get(node) >= n) count++;
            }
            if (count >= config.majoritySize()) {
                commitIndex = n;
                break;
            }
        }
        applyCommitted();
    }

    private void applyCommitted() {
        while (lastApplied < commitIndex) {
            lastApplied++;
            Optional<LogEntry> entry = log.get(lastApplied);
            entry.ifPresent(stateMachine::apply);
        }
    }

    private void maybeStepDown(long observedTerm) {
        if (observedTerm > currentTerm) {
            stepDownTo(observedTerm);
        }
    }

    private void stepDownTo(long newTerm) {
        currentTerm = newTerm;
        state = RaftState.FOLLOWER;
        votedFor = null;
        currentLeader = null;
        resetElectionDeadline();
    }

    private boolean candidateLogIsUpToDate(long candidateLastIndex, long candidateLastTerm) {
        long ourLastTerm = log.lastTerm();
        if (candidateLastTerm != ourLastTerm) {
            return candidateLastTerm > ourLastTerm;
        }
        return candidateLastIndex >= log.lastIndex();
    }

    private void resetElectionDeadline() {
        Duration timeout = clock.randomElectionTimeout();
        electionDeadline = clock.now() + timeout.toMillis();
    }

    // ----- Submit result type -----

    public record SubmitResult(boolean accepted, long index, long term, String knownLeader) {

        public static SubmitResult accepted(long index, long term) {
            return new SubmitResult(true, index, term, null);
        }

        public static SubmitResult notLeader(String knownLeader) {
            return new SubmitResult(false, 0, 0, knownLeader);
        }
    }
}
