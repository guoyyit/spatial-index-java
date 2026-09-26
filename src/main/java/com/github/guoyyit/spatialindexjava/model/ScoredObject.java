package com.github.guoyyit.spatialindexjava.model;

/** A spatial object paired with its distance to the query point. */
public record ScoredObject(SpatialObject object, double distance) {
}
