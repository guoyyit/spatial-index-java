package com.github.guoyyit.spatialindexjava.model;

import java.util.Arrays;

/**
 * Axis-aligned bounding box in 2 or 3 dimensions.
 * Internally normalized to 3 dimensions (2D boxes have z == [0,0]).
 */
public final class BoundingBox {
    private final double[] min;
    private final double[] max;

    public BoundingBox(double[] min, double[] max) {
        this.min = min;
        this.max = max;
    }

    public static BoundingBox of(double[] min, double[] max) {
        return new BoundingBox(min, max);
    }

    public int dims() {
        return min.length;
    }

    public double[] min() {
        return min;
    }

    public double[] max() {
        return max;
    }

    public boolean contains(BoundingBox other) {
        return false;
    }

    public boolean intersects(BoundingBox other) {
        return false;
    }

    public double minDistSquared(double[] point) {
        return 0.0;
    }

    public BoundingBox union(BoundingBox other) {
        return this;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof BoundingBox other)) return false;
        return Arrays.equals(min, other.min) && Arrays.equals(max, other.max);
    }

    @Override
    public int hashCode() {
        return 31 * Arrays.hashCode(min) + Arrays.hashCode(max);
    }

    @Override
    public String toString() {
        return "Box" + Arrays.toString(min) + "~" + Arrays.toString(max);
    }
}
