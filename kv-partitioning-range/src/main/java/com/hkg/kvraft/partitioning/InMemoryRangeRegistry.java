package com.hkg.kvraft.partitioning;

import com.hkg.kvraft.common.Key;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Non-durable in-memory {@link RangeRegistry}. Suitable for tests and
 * single-process deployments. Thread-safe via internal synchronization;
 * lookup is read-heavy so the implementation copies on write.
 */
public final class InMemoryRangeRegistry implements RangeRegistry {

    private final Object lock = new Object();
    // Indexed by rangeId for fast put/remove; sorted snapshot for lookup.
    private final Map<RangeId, RangeDescriptor> byId = new HashMap<>();
    private volatile List<RangeDescriptor> sortedSnapshot = List.of();

    @Override
    public Optional<RangeDescriptor> lookup(Key key) {
        // Linear scan over the sorted snapshot. For tests and small clusters,
        // this is fine. Production would use a B-tree or skip list keyed on
        // startKey for O(log N) lookup.
        List<RangeDescriptor> ranges = sortedSnapshot;
        for (RangeDescriptor descriptor : ranges) {
            if (descriptor.contains(key)) {
                return Optional.of(descriptor);
            }
        }
        return Optional.empty();
    }

    @Override
    public List<RangeDescriptor> allRanges() {
        return sortedSnapshot;
    }

    @Override
    public void put(RangeDescriptor descriptor) {
        Objects.requireNonNull(descriptor, "descriptor");
        synchronized (lock) {
            byId.put(descriptor.rangeId(), descriptor);
            rebuildSnapshot();
        }
    }

    @Override
    public void remove(RangeId rangeId) {
        Objects.requireNonNull(rangeId, "rangeId");
        synchronized (lock) {
            byId.remove(rangeId);
            rebuildSnapshot();
        }
    }

    private void rebuildSnapshot() {
        List<RangeDescriptor> copy = new ArrayList<>(byId.values());
        // Sort: ranges with absent startKey go first (they're leftmost);
        // among present startKeys, lexicographic order.
        copy.sort(Comparator
                .comparing((RangeDescriptor d) -> d.startKey().isEmpty() ? 0 : 1)
                .thenComparing(
                        d -> d.startKey().orElseGet(() -> new Key(new byte[]{0})),
                        RangeDescriptor::compare));
        sortedSnapshot = List.copyOf(copy);
    }
}
