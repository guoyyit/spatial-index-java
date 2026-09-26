package com.github.guoyyit.spatialindexjava.core;

import com.github.guoyyit.spatialindexjava.model.BoundingBox;
import com.github.guoyyit.spatialindexjava.model.Entry;
import com.github.guoyyit.spatialindexjava.model.RangeMode;

import java.io.Closeable;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Immutable on-disk sorted run of entries with an STR-packed R-tree
 * resident in memory while open.
 */
public final class Segment implements Closeable {

    private Segment() {
    }

    public static Segment open(Path file) {
        return new Segment();
    }

    public String name() {
        return "";
    }

    public Path path() {
        return null;
    }

    public int size() {
        return 0;
    }

    public void range(BoundingBox query, RangeMode mode, Predicate<Entry> accept, List<Entry> out) {
    }

    public List<Entry> knn(double[] point, int k, Predicate<Entry> accept) {
        return new ArrayList<>();
    }

    /** All entries in storage order (used by merge). */
    public List<Entry> entries() {
        return new ArrayList<>();
    }

    @Override
    public void close() {
    }
}
