package com.github.guoyyit.spatialindexjava.core;

import java.util.List;

/**
 * An immutable readable source of indexed records (a frozen mem-table or a
 * disk segment). All implementations are safe for concurrent reads.
 */
public interface SpatialSource extends AutoCloseable {

    long maxSequence();

    /** All records (including tombstones) whose envelope intersects the query envelope. */
    List<IndexRecord> rangeQuery(Envelope query);

    /**
     * Bounded kNN search. Returns up to {@code bound} live (non-tombstone) candidates,
     * and reports whether the search could have missed entries at the cutoff distance.
     */
    KnnSearchResult knnSearch(Envelope point, int bound);

    @Override
    default void close() { }
}
