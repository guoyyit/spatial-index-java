package com.github.guoyyit.spatialindexjava.model;

/** Range query semantics. Boundary comparison is inclusive in both modes. */
public enum RangeMode {
    /** Object box must be fully contained in the query box (inclusive). */
    CONTAINS,
    /** Object box must intersect the query box (inclusive). */
    INTERSECTS
}
