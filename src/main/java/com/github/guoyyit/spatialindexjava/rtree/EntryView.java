package com.github.guoyyit.spatialindexjava.rtree;

import com.github.guoyyit.spatialindexjava.core.Envelope;
import com.github.guoyyit.spatialindexjava.core.SpatialObject;

/**
 * Geometry/identity accessor for generic R-tree entries.
 *
 * @param <E> entry type (heap record or mmap-backed value)
 */
public interface EntryView<E> {

    Envelope envelope(E entry);

    String id(E entry);

    /** Tombstone entries participate in pruning but are never returned. */
    boolean tombstone(E entry);

    /** Reconstruct the user object. Never called for tombstones. */
    SpatialObject toObject(E entry);
}
