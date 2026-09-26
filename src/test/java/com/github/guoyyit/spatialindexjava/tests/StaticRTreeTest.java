package com.github.guoyyit.spatialindexjava.tests;

import com.github.guoyyit.spatialindexjava.core.Envelope;
import com.github.guoyyit.spatialindexjava.core.IndexRecord;
import com.github.guoyyit.spatialindexjava.core.KnnCandidate;
import com.github.guoyyit.spatialindexjava.core.KnnSearchResult;
import com.github.guoyyit.spatialindexjava.core.ShapeType;
import com.github.guoyyit.spatialindexjava.core.SpatialObject;
import com.github.guoyyit.spatialindexjava.lsm.Memtable;
import com.github.guoyyit.spatialindexjava.rtree.EntryView;
import com.github.guoyyit.spatialindexjava.rtree.StaticRTree;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class StaticRTreeTest {

    private static IndexRecord point(long seq, String id, double x, double y) {
        return IndexRecord.put(seq, new SpatialObject(id, ShapeType.POINT, Envelope.point2D(x, y), null));
    }

    private static final EntryView<IndexRecord> VIEW = Memtable.RECORD_VIEW;

    private static StaticRTree<IndexRecord> treeOf(List<IndexRecord> recs) {
        return new StaticRTree<>(4, VIEW, recs);
    }

    private static List<String> ids(List<IndexRecord> recs) {
        return recs.stream().map(IndexRecord::getId).collect(Collectors.toList());
    }

    @Test
    void emptyTreeQueriesAreEmpty() {
        StaticRTree<IndexRecord> t = treeOf(new ArrayList<>());
        assertTrue(t.range(Envelope.rect2D(-100, -100, 100, 100)).isEmpty());
        assertTrue(t.knn(Envelope.point2D(0, 0), 5).candidates().isEmpty());
    }

    @Test
    void rangeIncludesBoundaryPoints() {
        List<IndexRecord> recs = new ArrayList<>();
        recs.add(point(1, "a", 0, 0));
        recs.add(point(2, "b", 10, 10));
        recs.add(point(3, "c", 10, 0));
        recs.add(point(4, "d", 5, 5));
        recs.add(point(5, "e", 10.001, 0));
        StaticRTree<IndexRecord> t = treeOf(recs);

        List<String> in = ids(t.range(Envelope.rect2D(0, 0, 10, 10)));
        assertTrue(in.containsAll(List.of("a", "b", "c", "d")));
        assertFalse(in.contains("e"));
    }

    @Test
    void knnSortsByDistanceThenIdAndIsDeterministic() {
        List<IndexRecord> recs = new ArrayList<>();
        recs.add(point(1, "tieB", 1, 0));
        recs.add(point(2, "tieA", 0, 1));
        recs.add(point(3, "far", 10, 10));
        recs.add(point(4, "origin", 0, 0));
        StaticRTree<IndexRecord> t = treeOf(recs);

        KnnSearchResult r1 = t.knn(Envelope.point2D(0, 0), 3);
        KnnSearchResult r2 = t.knn(Envelope.point2D(0, 0), 3);
        List<String> order = r1.candidates().stream().map(KnnCandidate::id).collect(Collectors.toList());
        assertEquals(List.of("origin", "tieA", "tieB"), order);
        assertEquals(order, r2.candidates().stream().map(KnnCandidate::id).toList());
        assertEquals(0.0, r1.candidates().get(0).distance(), 1e-12);
        assertEquals(1.0, r1.candidates().get(1).distance(), 1e-12);
    }

    @Test
    void knnHonoursBoundAndTombstones() {
        List<IndexRecord> recs = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            recs.add(point(i + 1, "p" + i, i, 0));
        }
        StaticRTree<IndexRecord> t = treeOf(recs);
        assertEquals(10, t.knn(Envelope.point2D(0, 0), 10).candidates().size());

        List<IndexRecord> withTomb = new ArrayList<>();
        withTomb.add(IndexRecord.tombstone(101, "p0", ShapeType.POINT, Envelope.point2D(0, 0)));
        for (int i = 1; i < 100; i++) {
            withTomb.add(point(i + 1, "p" + i, i, 0));
        }
        StaticRTree<IndexRecord> t2 = new StaticRTree<>(4, VIEW, withTomb);
        List<String> nearest = t2.knn(Envelope.point2D(0, 0), 3)
                .candidates().stream().map(KnnCandidate::id).toList();
        assertEquals(List.of("p1", "p2", "p3"), nearest);
    }

    @Test
    void bulkLoadHandlesManyRecordsAndAllQuadrants() {
        List<IndexRecord> recs = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            double x = (i % 23) - 11;
            double y = (i % 31) - 15;
            recs.add(point(i + 1, "id-" + i, x, y));
        }
        StaticRTree<IndexRecord> t = treeOf(recs);
        assertEquals(500, t.size());
        List<IndexRecord> hits = t.range(Envelope.rect2D(-5, -5, 5, 5));
        assertTrue(hits.size() > 50);
        for (IndexRecord r : hits) {
            assertTrue(r.getEnvelope().intersects(Envelope.rect2D(-5, -5, 5, 5)));
        }

        // kNN of a grid matches brute force ordering.
        Envelope q = Envelope.point2D(0.3, -0.7);
        List<String> got = t.knn(q, 12).candidates().stream().map(KnnCandidate::id).toList();
        List<String> brute = recs.stream()
                .sorted(Comparator.comparingDouble((IndexRecord r) -> r.getEnvelope().minDistance(q))
                        .thenComparing(IndexRecord::getId))
                .limit(12).map(IndexRecord::getId).toList();
        assertEquals(brute, got);
    }

    @Test
    void threeDimensionalRangeAndKnn() {
        List<IndexRecord> recs = new ArrayList<>();
        recs.add(IndexRecord.put(1, new SpatialObject("a", ShapeType.POINT,
                Envelope.point3D(0, 0, 0), null)));
        recs.add(IndexRecord.put(2, new SpatialObject("b", ShapeType.POINT,
                Envelope.point3D(0, 0, 5), null)));
        recs.add(IndexRecord.put(3, new SpatialObject("c", ShapeType.RECTANGLE,
                Envelope.rect3D(1, 1, 1, 2, 2, 2), null)));
        StaticRTree<IndexRecord> t = treeOf(recs);
        assertEquals(2, t.range(Envelope.rect3D(-1, -1, -1, 1, 1, 1)).size());
        List<String> nearest = t.knn(Envelope.point3D(0, 0, 0), 2)
                .candidates().stream().map(KnnCandidate::id).toList();
        assertEquals(List.of("a", "c"), nearest);
    }
}
