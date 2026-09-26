package com.github.guoyyit.spatialindexjava.web.dto;

/**
 * Inclusive rectangular range query.
 */
public final class RangeQueryRequest {
    public Integer dim;
    public Double minX, minY, minZ;
    public Double maxX, maxY, maxZ;
}
