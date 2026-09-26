package com.github.guoyyit.spatialindexjava.tests;

import com.github.guoyyit.spatialindexjava.core.Envelope;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EnvelopeTest {

    private static final double EPS = 1e-12;

    @Test
    void boundaryTouchingIntersectsAndIsContained() {
        Envelope a = Envelope.rect2D(0, 0, 10, 10);
        Envelope edge = Envelope.rect2D(10, 2, 12, 8);
        assertTrue(a.intersects(edge));
        assertTrue(edge.intersects(a));
        assertEquals(0.0, a.minDistance(edge), EPS);

        Envelope inside = Envelope.rect2D(0, 0, 5, 5);
        assertTrue(a.contains(inside));
        // The reverse is false even though they touch at the origin.
        assertFalse(inside.contains(a));

        Envelope outside = Envelope.rect2D(11, 0, 12, 1);
        assertFalse(a.intersects(outside));
        assertEquals(1.0, a.minDistance(outside), EPS);
    }

    @Test
    void pointsHaveZeroAreaAndDistanceToSelf() {
        Envelope p = Envelope.point2D(3, 4);
        assertEquals(0.0, p.area(), EPS);
        assertEquals(0.0, p.minDistance(Envelope.point2D(3, 4)), EPS);
        assertEquals(5.0, p.minDistance(Envelope.point2D(0, 0)), EPS);
    }

    @Test
    void threeDimensionalGapUsesZ() {
        Envelope box = Envelope.rect3D(0, 0, 0, 1, 1, 1);
        Envelope other = Envelope.rect3D(0, 0, 3, 1, 1, 4);
        assertFalse(box.intersects(other));
        assertEquals(2.0, box.minDistance(other), EPS);
    }

    @Test
    void mixedDimensionsIntersectOnSharedZPlane() {
        Envelope plane = Envelope.rect2D(0, 0, 2, 2);
        Envelope box = Envelope.rect3D(0, 0, 0, 1, 1, 1);
        assertTrue(plane.intersects(box));
        assertEquals(0.0, plane.minDistance(box), EPS);
    }

    @Test
    void invalidEnvelopesAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> Envelope.rect2D(2, 0, 1, 1));
        assertThrows(IllegalArgumentException.class,
                () -> Envelope.rect3D(0, 0, 2, 1, 1, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new Envelope(4, 0d, 0d, 0d, 0d, 0d, 0d));
        assertThrows(IllegalArgumentException.class,
                () -> Envelope.point2D(Double.NaN, 0));
    }

    @Test
    void unionAndDeltaAreConsistent() {
        Envelope a = Envelope.rect2D(0, 0, 1, 1);
        Envelope b = Envelope.rect2D(1, 1, 2, 2);
        Envelope u = a.union(b);
        assertEquals(0, u.getMinX(), EPS);
        assertEquals(2, u.getMaxX(), EPS);
        assertEquals(a.union(b).area() - a.area(), a.unionAreaDelta(b), EPS);
    }
}
