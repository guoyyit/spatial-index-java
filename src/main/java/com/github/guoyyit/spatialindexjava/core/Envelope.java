package com.github.guoyyit.spatialindexjava.core;

/**
 * Immutable axis-aligned bounding box in 2D or 3D space.
 * 2D envelopes carry zero z extent. Boundary semantics are inclusive:
 * touching faces/edges count as both intersection and containment.
 */
public final class Envelope {

    private final int dim;
    private final double minX, minY, minZ;
    private final double maxX, maxY, maxZ;

    public Envelope(int dim,
                    double minX, double minY, double minZ,
                    double maxX, double maxY, double maxZ) {
        if (dim != 2 && dim != 3) {
            throw new IllegalArgumentException("dimension must be 2 or 3, got " + dim);
        }
        if (!finite(minX) || !finite(minY) || !finite(maxX) || !finite(maxY)) {
            throw new IllegalArgumentException("x/y coordinates must be finite");
        }
        if (dim == 3 && (!finite(minZ) || !finite(maxZ))) {
            throw new IllegalArgumentException("z coordinates must be finite");
        }
        if (minX > maxX || minY > maxY || (dim == 3 && minZ > maxZ)) {
            throw new IllegalArgumentException("min coordinate must not exceed max coordinate");
        }
        this.dim = dim;
        this.minX = minX;
        this.minY = minY;
        this.minZ = dim == 3 ? minZ : 0.0;
        this.maxX = maxX;
        this.maxY = maxY;
        this.maxZ = dim == 3 ? maxZ : 0.0;
    }

    private static boolean finite(double v) {
        return !Double.isNaN(v) && !Double.isInfinite(v);
    }

    public static Envelope point2D(double x, double y) {
        return new Envelope(2, x, y, 0.0, x, y, 0.0);
    }

    public static Envelope rect2D(double minX, double minY, double maxX, double maxY) {
        return new Envelope(2, minX, minY, 0.0, maxX, maxY, 0.0);
    }

    public static Envelope point3D(double x, double y, double z) {
        return new Envelope(3, x, y, z, x, y, z);
    }

    public static Envelope rect3D(double minX, double minY, double minZ,
                                  double maxX, double maxY, double maxZ) {
        return new Envelope(3, minX, minY, minZ, maxX, maxY, maxZ);
    }

    public int dimension() { return dim; }
    public double getMinX() { return minX; }
    public double getMinY() { return minY; }
    public double getMinZ() { return minZ; }
    public double getMaxX() { return maxX; }
    public double getMaxY() { return maxY; }
    public double getMaxZ() { return maxZ; }

    /** True when this envelope fully contains {@code other}; touching boundaries count. */
    public boolean contains(Envelope other) {
        return other.minX >= minX && other.maxX <= maxX
                && other.minY >= minY && other.maxY <= maxY
                && (dim == 2 || (other.minZ >= minZ && other.maxZ <= maxZ));
    }

    public boolean intersects(Envelope other) {
        boolean xy = other.minX <= maxX && other.maxX >= minX
                && other.minY <= maxY && other.maxY >= minY;
        if (!xy) {
            return false;
        }
        return dim == 2 || (other.minZ <= maxZ && other.maxZ >= minZ);
    }

    /** Minimum Euclidean distance between the two boxes (zero when they intersect/touch). */
    public double minDistance(Envelope other) {
        double dx = axisGap(minX, maxX, other.minX, other.maxX);
        double dy = axisGap(minY, maxY, other.minY, other.maxY);
        double dz = dim == 3 ? axisGap(minZ, maxZ, other.minZ, other.maxZ) : 0.0;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static double axisGap(double amin, double amax, double bmin, double bmax) {
        if (bmin > amax) {
            return bmin - amax;
        }
        if (amin > bmax) {
            return amin - bmax;
        }
        return 0.0;
    }

    /** Volume in 3D, area in 2D. */
    public double area() {
        double xy = (maxX - minX) * (maxY - minY);
        return dim == 3 ? xy * (maxZ - minZ) : xy;
    }

    public Envelope union(Envelope other) {
        double uMinX = Math.min(minX, other.minX);
        double uMinY = Math.min(minY, other.minY);
        double uMaxX = Math.max(maxX, other.maxX);
        double uMaxY = Math.max(maxY, other.maxY);
        if (dim == 3) {
            return new Envelope(3, uMinX, uMinY, Math.min(minZ, other.minZ),
                    uMaxX, uMaxY, Math.max(maxZ, other.maxZ));
        }
        return new Envelope(2, uMinX, uMinY, 0.0, uMaxX, uMaxY, 0.0);
    }

    /** Enlargement caused by adding {@code other} to this envelope. */
    public double unionAreaDelta(Envelope other) {
        return union(other).area() - area();
    }

    @Override
    public String toString() {
        return dim == 3
                ? "Envelope3D[(" + minX + "," + minY + "," + minZ + ")-(" + maxX + "," + maxY + "," + maxZ + ")]"
                : "Envelope2D[(" + minX + "," + minY + ")-(" + maxX + "," + maxY + ")]";
    }
}
