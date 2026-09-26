package com.github.guoyyit.spatialindexjava.model;

import java.util.Arrays;

/**
 * A spatial object: unique id, 2D/3D bounding box (point when min == max),
 * optional opaque payload.
 */
public final class SpatialObject {
    private final String id;
    private final BoundingBox box;
    private final byte[] payload;

    public SpatialObject(String id, BoundingBox box, byte[] payload) {
        this.id = id;
        this.box = box;
        this.payload = payload;
    }

    public String id() {
        return id;
    }

    public BoundingBox box() {
        return box;
    }

    /** Optional payload, may be null. */
    public byte[] payload() {
        return payload;
    }

    @Override
    public String toString() {
        return "Obj{" + id + "," + box + ",payload=" +
                (payload == null ? "null" : payload.length + "B") + "}";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SpatialObject other)) return false;
        return id.equals(other.id) && box.equals(other.box)
                && Arrays.equals(payload, other.payload);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }
}
