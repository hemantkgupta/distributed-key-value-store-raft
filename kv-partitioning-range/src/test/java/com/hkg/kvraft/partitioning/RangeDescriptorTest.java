package com.hkg.kvraft.partitioning;

import com.hkg.kvraft.common.Key;
import com.hkg.kvraft.common.NodeId;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RangeDescriptorTest {

    private final NodeId n1 = new NodeId("N1");
    private final NodeId n2 = new NodeId("N2");
    private final NodeId n3 = new NodeId("N3");

    @Test
    void containsRespectsHalfOpenSemantics() {
        RangeDescriptor r = bounded("b", "e");
        assertThat(r.contains(key("b"))).isTrue();   // start inclusive
        assertThat(r.contains(key("c"))).isTrue();
        assertThat(r.contains(key("e"))).isFalse();  // end exclusive
        assertThat(r.contains(key("a"))).isFalse();
        assertThat(r.contains(key("f"))).isFalse();
    }

    @Test
    void rightmostRangeHasNoUpperBound() {
        RangeDescriptor r = withStart("m");
        assertThat(r.contains(key("m"))).isTrue();
        assertThat(r.contains(key("zz"))).isTrue();
        assertThat(r.contains(key("l"))).isFalse();
    }

    @Test
    void leftmostRangeHasAbsentStartKey() {
        RangeDescriptor r = withEnd("c");
        assertThat(r.contains(key("a"))).isTrue();
        assertThat(r.contains(key("b"))).isTrue();
        assertThat(r.contains(key("c"))).isFalse();
    }

    @Test
    void unboundedRangeCoversAllKeys() {
        RangeDescriptor r = unbounded();
        assertThat(r.contains(key("a"))).isTrue();
        assertThat(r.contains(key("zzz"))).isTrue();
    }

    @Test
    void leaseholderMustBeInVoters() {
        assertThatThrownBy(() -> new RangeDescriptor(
                RangeId.of(1),
                Optional.of(key("a")),
                Optional.of(key("z")),
                1,
                List.of(n1, n2),
                n3))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("leaseholder must be in voters");
    }

    @Test
    void startKeyMustBeLessThanEndKey() {
        assertThatThrownBy(() -> bounded("z", "a"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("startKey must be < endKey");
    }

    @Test
    void byteCompareIsLexicographic() {
        assertThat(RangeDescriptor.compare(key("a"), key("b"))).isLessThan(0);
        assertThat(RangeDescriptor.compare(key("b"), key("a"))).isGreaterThan(0);
        assertThat(RangeDescriptor.compare(key("a"), key("a"))).isZero();
        // Shorter prefix sorts before its extension.
        assertThat(RangeDescriptor.compare(key("a"), key("ab"))).isLessThan(0);
    }

    private static Key key(String s) {
        return new Key(s.getBytes());
    }

    private RangeDescriptor bounded(String start, String end) {
        return new RangeDescriptor(
                RangeId.of(1),
                Optional.of(key(start)),
                Optional.of(key(end)),
                1,
                List.of(n1, n2, n3),
                n1);
    }

    private RangeDescriptor withStart(String start) {
        return new RangeDescriptor(
                RangeId.of(1),
                Optional.of(key(start)),
                Optional.empty(),
                1,
                List.of(n1, n2, n3),
                n1);
    }

    private RangeDescriptor withEnd(String end) {
        return new RangeDescriptor(
                RangeId.of(1),
                Optional.empty(),
                Optional.of(key(end)),
                1,
                List.of(n1, n2, n3),
                n1);
    }

    private RangeDescriptor unbounded() {
        return new RangeDescriptor(
                RangeId.of(1),
                Optional.empty(),
                Optional.empty(),
                1,
                List.of(n1, n2, n3),
                n1);
    }
}
