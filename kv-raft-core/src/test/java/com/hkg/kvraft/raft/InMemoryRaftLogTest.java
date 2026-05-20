package com.hkg.kvraft.raft;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InMemoryRaftLogTest {

    @Test
    void emptyLogHasZeroIndexAndTerm() {
        RaftLog log = new InMemoryRaftLog();
        assertThat(log.lastIndex()).isZero();
        assertThat(log.lastTerm()).isZero();
        assertThat(log.entriesFrom(1)).isEmpty();
        assertThat(log.get(1)).isEmpty();
    }

    @Test
    void appendIncrementsIndexAndUpdatesLastTerm() {
        RaftLog log = new InMemoryRaftLog();
        log.append(entry(1, 1, "a"));
        log.append(entry(1, 2, "b"));
        log.append(entry(2, 3, "c"));

        assertThat(log.lastIndex()).isEqualTo(3);
        assertThat(log.lastTerm()).isEqualTo(2);
    }

    @Test
    void appendRejectsOutOfOrderIndex() {
        RaftLog log = new InMemoryRaftLog();
        log.append(entry(1, 1, "a"));
        assertThatThrownBy(() -> log.append(entry(1, 3, "c")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("expected entry index 2");
    }

    @Test
    void entriesFromReturnsContiguousSlice() {
        RaftLog log = new InMemoryRaftLog();
        log.append(entry(1, 1, "a"));
        log.append(entry(1, 2, "b"));
        log.append(entry(2, 3, "c"));

        List<LogEntry> tail = log.entriesFrom(2);
        assertThat(tail).hasSize(2);
        assertThat(tail.get(0).index()).isEqualTo(2);
        assertThat(tail.get(1).index()).isEqualTo(3);
    }

    @Test
    void truncateAfterDropsTrailingEntries() {
        RaftLog log = new InMemoryRaftLog();
        log.append(entry(1, 1, "a"));
        log.append(entry(1, 2, "b"));
        log.append(entry(2, 3, "c"));

        log.truncateAfter(1);
        assertThat(log.lastIndex()).isEqualTo(1);
        assertThat(log.get(2)).isEmpty();
        assertThat(log.lastTerm()).isEqualTo(1);
    }

    @Test
    void truncateAfterZeroEmptiesLog() {
        RaftLog log = new InMemoryRaftLog();
        log.append(entry(1, 1, "a"));
        log.append(entry(1, 2, "b"));

        log.truncateAfter(0);
        assertThat(log.lastIndex()).isZero();
        assertThat(log.lastTerm()).isZero();
    }

    @Test
    void truncateAfterAlreadyLastIsNoop() {
        RaftLog log = new InMemoryRaftLog();
        log.append(entry(1, 1, "a"));
        log.append(entry(1, 2, "b"));

        log.truncateAfter(5);
        assertThat(log.lastIndex()).isEqualTo(2);
    }

    @Test
    void getByIndexReturnsCorrectEntry() {
        RaftLog log = new InMemoryRaftLog();
        log.append(entry(1, 1, "a"));
        log.append(entry(1, 2, "b"));

        Optional<LogEntry> e = log.get(2);
        assertThat(e).isPresent();
        assertThat(e.get().term()).isEqualTo(1);
        assertThat(new String(e.get().command())).isEqualTo("b");
    }

    @Test
    void logEntryCommandIsDefensivelyCopied() {
        byte[] command = "hello".getBytes();
        LogEntry e = new LogEntry(1, 1, command, "req-1");
        command[0] = 'X'; // mutate caller's array
        assertThat(new String(e.command())).isEqualTo("hello");
    }

    private static LogEntry entry(long term, long index, String command) {
        return new LogEntry(term, index, command.getBytes(), "req-" + index);
    }
}
