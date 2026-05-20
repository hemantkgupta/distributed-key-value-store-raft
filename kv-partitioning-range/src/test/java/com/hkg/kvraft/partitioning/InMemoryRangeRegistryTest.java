package com.hkg.kvraft.partitioning;

import com.hkg.kvraft.common.Key;
import com.hkg.kvraft.common.NodeId;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryRangeRegistryTest {

    private final NodeId n1 = new NodeId("N1");
    private final NodeId n2 = new NodeId("N2");
    private final NodeId n3 = new NodeId("N3");

    @Test
    void emptyRegistryHasNoLookups() {
        RangeRegistry registry = new InMemoryRangeRegistry();
        assertThat(registry.lookup(key("k"))).isEmpty();
        assertThat(registry.allRanges()).isEmpty();
    }

    @Test
    void singleRangeOwnsAllKeys() {
        RangeRegistry registry = new InMemoryRangeRegistry();
        registry.put(unbounded(1, n1, 1));

        assertThat(registry.lookup(key("a")))
                .map(d -> d.rangeId().id())
                .contains(1L);
        assertThat(registry.lookup(key("zzz")))
                .map(d -> d.rangeId().id())
                .contains(1L);
    }

    @Test
    void multipleRangesRouteKeysCorrectly() {
        RangeRegistry registry = new InMemoryRangeRegistry();
        registry.put(rangeOf(1, Optional.empty(), Optional.of(key("m"))));
        registry.put(rangeOf(2, Optional.of(key("m")), Optional.empty()));

        assertThat(registry.lookup(key("a")))
                .map(d -> d.rangeId().id()).contains(1L);
        assertThat(registry.lookup(key("m")))
                .map(d -> d.rangeId().id()).contains(2L);
        assertThat(registry.lookup(key("z")))
                .map(d -> d.rangeId().id()).contains(2L);
    }

    @Test
    void allRangesAreSortedByStartKey() {
        RangeRegistry registry = new InMemoryRangeRegistry();
        // Insert out of order.
        registry.put(rangeOf(3, Optional.of(key("p")), Optional.empty()));
        registry.put(rangeOf(1, Optional.empty(), Optional.of(key("d"))));
        registry.put(rangeOf(2, Optional.of(key("d")), Optional.of(key("p"))));

        List<RangeDescriptor> all = registry.allRanges();
        assertThat(all).hasSize(3);
        assertThat(all.get(0).rangeId().id()).isEqualTo(1L);
        assertThat(all.get(1).rangeId().id()).isEqualTo(2L);
        assertThat(all.get(2).rangeId().id()).isEqualTo(3L);
    }

    @Test
    void removeDropsRange() {
        RangeRegistry registry = new InMemoryRangeRegistry();
        registry.put(unbounded(1, n1, 1));
        registry.remove(RangeId.of(1));

        assertThat(registry.allRanges()).isEmpty();
        assertThat(registry.lookup(key("k"))).isEmpty();
    }

    @Test
    void putReplacesExistingRangeWithSameId() {
        RangeRegistry registry = new InMemoryRangeRegistry();
        registry.put(unbounded(1, n1, 1));
        registry.put(unbounded(1, n2, 2));

        RangeDescriptor current = registry.allRanges().get(0);
        assertThat(current.leaseholder()).isEqualTo(n2);
        assertThat(current.generation()).isEqualTo(2L);
    }

    private static Key key(String s) {
        return new Key(s.getBytes());
    }

    private RangeDescriptor unbounded(long id, NodeId leader, long gen) {
        return new RangeDescriptor(
                RangeId.of(id),
                Optional.empty(),
                Optional.empty(),
                gen,
                List.of(n1, n2, n3),
                leader);
    }

    private RangeDescriptor rangeOf(long id, Optional<Key> start, Optional<Key> end) {
        return new RangeDescriptor(
                RangeId.of(id),
                start,
                end,
                1,
                List.of(n1, n2, n3),
                n1);
    }
}
