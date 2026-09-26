package com.github.guoyyit.spatialindexjava.web.dto;

/**
 * Nearest-neighbour query around a point.
 */
public final class KnnQueryRequest {
    public Integer dim;
    public Double x, y, z;
    public Integer k;
}
