package com.github.guoyyit.spatialindexjava.tests;

import com.github.guoyyit.spatialindexjava.model.BoundingBox;
import com.github.guoyyit.spatialindexjava.model.Entry;
import com.github.guoyyit.spatialindexjava.model.RangeMode;
import com.github.guoyyit.spatialindexjava.model.SpatialObject;
import com.github.guoyyit.spatialindexjava.rtree.PackedRTree;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PackedRTreeTest {

    private static Entry point(String id, double... coords) {
        return Entry.put(1L, new SpatialObject(id, BoundingBox.point(coords), null));
    }

    @Test
    void rangeMatchesBruteForce2D() {
        Random rnd = new Random(42);
        List<Entry> entries = new ArrayList<>();
        for (int i = 0; i < 2000; i++) {
            entries.add(point("id" + i, rnd.nextDouble() * 1000, rnd.nextDouble() * 1000));
        }
        PackedRTree tree = PackedRTree.build(entries);
        assertEquals(2000, tree.size());

        for (int q = 0; q < 50; q++) {
            double x1 = rnd.nextDouble() * 900, y1 = rnd.nextDouble() * 900;
            BoundingBox query = new BoundingBox(new double[]{x1, y1},
                    new double[]{x1 + 100, y1 + 100});
            List<Entry> got = new ArrayList<>();
            tree.range(query, RangeMode.CONTAINS, e -> true, got);
            List<String> expected = entries.stream()
                    .filter(e -> query.contains(e.object().box()))
                    .map(Entry::id).sorted().toList();
            assertEquals(expected, got.stream().map(Entry::id).sorted().toList());
        }
    }

    @Test
    void rangeMatchesBruteForce3DAndIntersects() {
        Random rnd = new Random(7);
        List<Entry> entries = new ArrayList<>();
        for (int i = 0; i < 1500; i++) {
            double x = rnd.nextDouble() * 500, y = rnd.nextDouble() * 500, z = rnd.nextDouble() * 500;
            entries.add(Entry.put(1L, new SpatialObject("b" + i,
                    new BoundingBox(new double[]{x, y, z},
                            new double[]{x + 5, y + 5, z + 5}), null)));
        }
        PackedRTree tree = PackedRTree.build(entries);
        BoundingBox query = new BoundingBox(new double[]{100, 100, 100}, new double[]{200, 200, 200});

        List<Entry> got = new ArrayList<>();
        tree.range(query, RangeMode.INTERSECTS, e -> true, got);
        List<String> expected = entries.stream()
                .filter(e -> query.intersects(e.object().box()))
                .map(Entry::id).sorted().toList();
        assertEquals(expected, got.stream().map(Entry::id).sorted().toList());
    }

    @Test
    void boundaryPointsAreIncluded() {
        List<Entry> entries = List.of(
                point("corner", 0, 0),
                point("edge", 50, 0),
                point("outside", -1, 0));
        PackedRTree tree = PackedRTree.build(entries);
        BoundingBox query = new BoundingBox(new double[]{0, 0}, new double[]{50, 50});
        List<Entry> got = new ArrayList<>();
        tree.range(query, RangeMode.CONTAINS, e -> true, got);
        assertEquals(new TreeSet<>(List.of("corner", "edge")),
                new TreeSet<>(got.stream().map(Entry::id).toList()));
    }

    @Test
    void knnMatchesBruteForceWithDeterministicTies() {
        Random rnd = new Random(99);
        List<Entry> entries = new ArrayList<>();
        // grid points -> lots of exact distance ties
        for (int x = 0; x < 40; x++) {
            for (int y = 0; y < 40; y++) {
                entries.add(point("p_" + x + "_" + y, x * 10.0, y * 10.0));
            }
        }
        PackedRTree tree = PackedRTree.build(entries);

        for (int q = 0; q < 30; q++) {
            double[] qp = {rnd.nextDouble() * 400, rnd.nextDouble() * 400};
            int k = 1 + rnd.nextInt(20);
            List<Entry> got = tree.knn(qp, k, e -> true);
            List<String> expected = entries.stream()
                    .sorted(Comparator.comparingDouble(
                                    (Entry e) -> BoundingBox.distSquared(qp, e.object().box().min()))
                            .thenComparing(Entry::id))
                    .limit(k).map(Entry::id).toList();
            assertEquals(expected, got.stream().map(Entry::id).toList(),
                    "knn mismatch for k=" + k);
        }
    }

    @Test
    void knnRespectsAcceptFilter() {
        List<Entry> entries = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            entries.add(point("id" + i, i, 0));
        }
        PackedRTree tree = PackedRTree.build(entries);
        // hide the 10 nearest points to (0,0)
        List<Entry> got = tree.knn(new double[]{0, 0}, 5,
                e -> Integer.parseInt(e.id().substring(2)) >= 10);
        assertEquals(List.of("id10", "id11", "id12", "id13", "id14"),
                got.stream().map(Entry::id).toList());
    }

    @Test
    void emptyTree() {
        PackedRTree tree = PackedRTree.build(List.of());
        assertEquals(0, tree.size());
        assertTrue(tree.knn(new double[]{0, 0}, 5, e -> true).isEmpty());
        List<Entry> out = new ArrayList<>();
        tree.range(new BoundingBox(new double[]{0, 0}, new double[]{1, 1}),
                RangeMode.CONTAINS, e -> true, out);
        assertTrue(out.isEmpty());
    }
}
