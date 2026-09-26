package com.github.guoyyit.spatialindexjava.core;

import com.github.guoyyit.spatialindexjava.model.BoundingBox;
import com.github.guoyyit.spatialindexjava.model.Entry;
import com.github.guoyyit.spatialindexjava.model.RangeMode;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Predicate;

/**
 * Bounded in-memory write buffer. Keeps the newest entry per id.
 * Lookups are O(1); range/knn scan the bounded buffer (size is capped
 * by configuration, so scans never degenerate into full-data scans).
 */
public final class Memtable {

    public void apply(Entry e) {
    }

    public Entry get(String id) {
        return null;
    }

    public int size() {
        return 0;
    }

    /** Immutable snapshot of current entries (for flush). */
    public Collection<Entry> entries() {
        return new ArrayList<>();
    }

    public void range(BoundingBox query, RangeMode mode, Predicate<Entry> accept, List<Entry> out) {
    }

    public List<Entry> knn(double[] point, int k, Predicate<Entry> accept) {
        return new ArrayList<>();
    }
}
