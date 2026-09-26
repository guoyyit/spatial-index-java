package com.github.guoyyit.spatialindexjava.rtree;

import com.github.guoyyit.spatialindexjava.model.BoundingBox;
import com.github.guoyyit.spatialindexjava.model.Entry;
import com.github.guoyyit.spatialindexjava.model.RangeMode;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Immutable R-tree bulk-loaded with the STR (sort-tile-recursive) method.
 * Used for on-disk segments and frozen memtables.
 */
public final class PackedRTree {

    private PackedRTree() {
    }

    /** Bulk-load a tree from the given entries. */
    public static PackedRTree build(List<Entry> entries) {
        return new PackedRTree();
    }

    public int size() {
        return 0;
    }

    /** Collect entries matching the query box into out. accept filters visibility. */
    public void range(BoundingBox query, RangeMode mode, Predicate<Entry> accept, List<Entry> out) {
    }

    /**
     * Best-first k nearest neighbors to point. accept filters visibility;
     * result is sorted by (distance, id) ascending.
     */
    public List<Entry> knn(double[] point, int k, Predicate<Entry> accept) {
        return new ArrayList<>();
    }
}
