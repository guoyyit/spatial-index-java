package com.github.guoyyit.spatialindexjava.core;

import com.github.guoyyit.spatialindexjava.model.BoundingBox;
import com.github.guoyyit.spatialindexjava.model.Entry;
import com.github.guoyyit.spatialindexjava.model.RangeMode;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * Bounded in-memory write buffer holding the newest entry per id
 * (tombstones included). Id lookups are O(1). Range/knn scan the buffer,
 * which is safe because its size is capped by configuration and it is
 * flushed to an R-tree indexed segment once full — scans never grow
 * into full-data scans.
 */
public final class Memtable {

    private final ConcurrentHashMap<String, Entry> map = new ConcurrentHashMap<>();

    /** Apply an entry; if a racing apply happens, the higher seq wins. */
    public void apply(Entry e) {
        map.merge(e.id(), e, (old, neu) -> neu.seq() >= old.seq() ? neu : old);
    }

    public Entry get(String id) {
        return map.get(id);
    }

    public int size() {
        return map.size();
    }

    /** Snapshot of current entries (used by flush). */
    public Collection<Entry> entries() {
        return List.copyOf(map.values());
    }

    public void range(BoundingBox query, RangeMode mode, Predicate<Entry> accept, List<Entry> out) {
        for (Entry e : map.values()) {
            if (e.tombstone()) {
                continue;
            }
            BoundingBox box = e.object().box();
            boolean match = mode == RangeMode.CONTAINS
                    ? query.contains(box)
                    : query.intersects(box);
            if (match && accept.test(e)) {
                out.add(e);
            }
        }
    }

    public List<Entry> knn(double[] point, int k, Predicate<Entry> accept) {
        List<Entry> accepted = new ArrayList<>();
        for (Entry e : map.values()) {
            if (!e.tombstone() && accept.test(e)) {
                accepted.add(e);
            }
        }
        accepted.sort(Comparator
                .comparingDouble((Entry e) -> e.object().box().minDistSquared(point))
                .thenComparing(Entry::id));
        return accepted.size() <= k ? accepted : new ArrayList<>(accepted.subList(0, k));
    }
}
