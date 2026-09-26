package com.github.guoyyit.spatialindexjava.model;

/**
 * Internal versioned record. A tombstone entry marks a deletion.
 * Visibility is decided by comparing seq numbers via the version index.
 */
public final class Entry {
    private final long seq;
    private final boolean tombstone;
    private final SpatialObject object;

    private Entry(long seq, boolean tombstone, SpatialObject object) {
        this.seq = seq;
        this.tombstone = tombstone;
        this.object = object;
    }

    public static Entry put(long seq, SpatialObject object) {
        return new Entry(seq, false, object);
    }

    public static Entry delete(long seq, String id) {
        return new Entry(seq, true, new SpatialObject(id, null, null));
    }

    public long seq() {
        return seq;
    }

    public boolean tombstone() {
        return tombstone;
    }

    public SpatialObject object() {
        return object;
    }

    public String id() {
        return object.id();
    }
}
