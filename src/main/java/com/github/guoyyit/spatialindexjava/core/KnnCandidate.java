package com.github.guoyyit.spatialindexjava.core;

/**
 * A live spatial object together with its distance to a query point.
 */
public record KnnCandidate(SpatialObject object, double distance) {

    public String id() { return object.getId(); }
}
