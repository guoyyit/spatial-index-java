package com.github.guoyyit.spatialindexjava.model;

import java.util.Arrays;

/**
 * Axis-aligned bounding box in 2 or 3 dimensions.
 * All comparisons are inclusive so boundary points behave deterministically.
 * When boxes of different dimensions are compared, the missing axes are
 * treated as the point 0 (a 2D box lies on the z=0 plane).
 */
public final class BoundingBox {
    private final double[] min;
    private final double[] max;

    public BoundingBox(double[] min, double[] max) {
        if (min == null || max == null || min.length != max.length
                || (min.length != 2 && min.length != 3)) {
            throw new IllegalArgumentException("box must be 2D or 3D with matching min/max");
        }
        for (int i = 0; i < min.length; i++) {
            if (Double.isNaN(min[i]) || Double.isNaN(max[i])) {
                throw new IllegalArgumentException("NaN coordinates are not allowed");
            }
            if (min[i] > max[i]) {
                throw new IllegalArgumentException("min must be <= max on every axis");
            }
        }
        this.min = min.clone();
        this.max = max.clone();
    }

    /** Point box at the given coordinate. */
    public static BoundingBox point(double[] coords) {
        return new BoundingBox(coords, coords);
    }

    public int dims() {
        return min.length;
    }

    public double[] min() {
        return min.clone();
    }

    public double[] max() {
        return max.clone();
    }

    /** Min on axis; axes beyond this box's dimension read as 0. */
    public double min(int axis) {
        return axis < min.length ? min[axis] : 0.0;
    }

    /** Max on axis; axes beyond this box's dimension read as 0. */
    public double max(int axis) {
        return axis < max.length ? max[axis] : 0.0;
    }

    /** Inclusive containment: boxes touching the boundary are contained. */
    public boolean contains(BoundingBox other) {
        int dims = Math.max(dims(), other.dims());
        for (int i = 0; i < dims; i++) {
            if (other.min(i) < min(i) || other.max(i) > max(i)) {
                return false;
            }
        }
        return true;
    }

    /** Inclusive intersection: touching boxes intersect. */
    public boolean intersects(BoundingBox other) {
        int dims = Math.max(dims(), other.dims());
        for (int i = 0; i < dims; i++) {
            if (other.min(i) > max(i) || other.max(i) < min(i)) {
                return false;
            }
        }
        return true;
    }

    /** Squared euclidean distance from point to this box (0 when inside). */
    public double minDistSquared(double[] point) {
        double sum = 0.0;
        for (int i = 0; i < point.length; i++) {
            double d = 0.0;
            if (point[i] < min(i)) {
                d = min(i) - point[i];
            } else if (point[i] > max(i)) {
                d = point[i] - max(i);
            }
            sum += d * d;
        }
        return sum;
    }

    public BoundingBox union(BoundingBox other) {
        int dims = Math.max(dims(), other.dims());
        double[] newMin = new double[dims];
        double[] newMax = new double[dims];
        for (int i = 0; i < dims; i++) {
            newMin[i] = Math.min(min(i), other.min(i));
            newMax[i] = Math.max(max(i), other.max(i));
        }
        return new BoundingBox(newMin, newMax);
    }

    /** Squared distance between two points of matching dimension. */
    public static double distSquared(double[] a, double[] b) {
        double sum = 0.0;
        for (int i = 0; i < a.length; i++) {
            double d = a[i] - b[i];
            sum += d * d;
        }
        return sum;
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
