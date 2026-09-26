package com.github.guoyyit.spatialindexjava.tests;

import com.github.guoyyit.spatialindexjava.config.SpatialIndexProperties;
import com.github.guoyyit.spatialindexjava.core.SpatialIndexEngine;
import com.github.guoyyit.spatialindexjava.model.BoundingBox;
import com.github.guoyyit.spatialindexjava.model.RangeMode;
import com.github.guoyyit.spatialindexjava.model.ScoredObject;
import com.github.guoyyit.spatialindexjava.model.SpatialObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EngineBasicTest {

    @TempDir
    Path dir;

    private SpatialIndexEngine openEngine() throws Exception {
        SpatialIndexProperties props = new SpatialIndexProperties();
        props.setDataDir(dir.toString());
        props.setMemtableMaxEntries(100);
        return SpatialIndexEngine.open(props);
    }

    private static SpatialObject point(String id, double... coords) {
        return new SpatialObject(id, BoundingBox.point(coords), null);
    }

    @Test
    void putGetDelete() throws Exception {
        try (SpatialIndexEngine engine = openEngine()) {
            engine.put(point("a", 1, 1));
            engine.put(point("b", 2, 2));
            assertTrue(engine.get("a").isPresent());
            assertEquals(2, engine.stats().liveObjects());

            assertTrue(engine.delete("a"));
            assertTrue(engine.get("a").isEmpty());
            assertTrue(engine.get("b").isPresent());
            // deleting again is a deterministic no-op
            assertFalse(engine.delete("a"));
            assertFalse(engine.delete("never-existed"));
        }
    }

    @Test
    void duplicateIdIsAnUpdate() throws Exception {
        try (SpatialIndexEngine engine = openEngine()) {
            engine.put(point("x", 1, 1));
            engine.put(point("x", 5, 5));
            engine.put(point("x", 9, 9));
            assertEquals(1, engine.stats().liveObjects());
            assertEquals(9.0, engine.get("x").orElseThrow().box().min(0));

            List<SpatialObject> inRange = engine.range(
                    new BoundingBox(new double[]{0, 0}, new double[]{10, 10}),
                    RangeMode.CONTAINS, 0);
            assertEquals(1, inRange.size());
        }
    }

    @Test
    void rangeContainsBoundaryAndEmpty() throws Exception {
        try (SpatialIndexEngine engine = openEngine()) {
            engine.put(point("on-min", 0, 0));
            engine.put(point("on-max", 10, 10));
            engine.put(point("inside", 5, 5));
            engine.put(point("outside", 11, 5));
            BoundingBox box = new BoundingBox(new double[]{0, 0}, new double[]{10, 10});

            List<SpatialObject> contains = engine.range(box, RangeMode.CONTAINS, 0);
            assertEquals(List.of("inside", "on-max", "on-min"),
                    contains.stream().map(SpatialObject::id).sorted().toList());

            List<SpatialObject> empty = engine.range(
                    new BoundingBox(new double[]{100, 100}, new double[]{200, 200}),
                    RangeMode.CONTAINS, 0);
            assertTrue(empty.isEmpty());

            // box straddling the boundary: intersects yes, contains no
            engine.put(new SpatialObject("straddle",
                    new BoundingBox(new double[]{8, 8}, new double[]{12, 12}), null));
            assertEquals(4, engine.range(box, RangeMode.INTERSECTS, 0).size());
            assertEquals(3, engine.range(box, RangeMode.CONTAINS, 0).size());
        }
    }

    @Test
    void knnOrderAndTiesAreDeterministic() throws Exception {
        try (SpatialIndexEngine engine = openEngine()) {
            // four points at identical distance 1 from origin
            engine.put(point("d", 1, 0));
            engine.put(point("b", -1, 0));
            engine.put(point("a", 0, 1));
            engine.put(point("c", 0, -1));
            engine.put(point("far", 10, 10));

            List<ScoredObject> knn = engine.knn(new double[]{0, 0}, 4);
            assertEquals(List.of("a", "b", "c", "d"),
                    knn.stream().map(s -> s.object().id()).toList());
            assertEquals(1.0, knn.get(0).distance(), 1e-9);

            // same query repeated: identical result
            assertEquals(knn.stream().map(s -> s.object().id()).toList(),
                    engine.knn(new double[]{0, 0}, 4).stream()
                            .map(s -> s.object().id()).toList());
        }
    }

    @Test
    void deletedObjectsNeverAppearInQueries() throws Exception {
        try (SpatialIndexEngine engine = openEngine()) {
            for (int i = 0; i < 50; i++) {
                engine.put(point("p" + i, i, i));
            }
            engine.flush();
            for (int i = 0; i < 50; i += 2) {
                engine.delete("p" + i);
            }
            List<SpatialObject> all = engine.range(
                    new BoundingBox(new double[]{0, 0}, new double[]{49, 49}),
                    RangeMode.CONTAINS, 0);
            assertEquals(25, all.size());
            assertTrue(all.stream().allMatch(o -> Integer.parseInt(o.id().substring(1)) % 2 == 1));

            List<ScoredObject> knn = engine.knn(new double[]{0, 0}, 3);
            assertEquals(List.of("p1", "p3", "p5"),
                    knn.stream().map(s -> s.object().id()).toList());
        }
    }

    @Test
    void threeDimensionalObjects() throws Exception {
        try (SpatialIndexEngine engine = openEngine()) {
            engine.put(point("p3d", 1, 2, 3));
            engine.put(new SpatialObject("box3d",
                    new BoundingBox(new double[]{0, 0, 0}, new double[]{5, 5, 5}), null));
            List<SpatialObject> hits = engine.range(
                    new BoundingBox(new double[]{0, 0, 0}, new double[]{4, 4, 4}),
                    RangeMode.CONTAINS, 0);
            assertEquals(List.of("p3d"), hits.stream().map(SpatialObject::id).toList());

            List<ScoredObject> knn = engine.knn(new double[]{10, 10, 10}, 1);
            assertEquals("box3d", knn.get(0).object().id());
        }
    }

    @Test
    void payloadRoundTrips() throws Exception {
        try (SpatialIndexEngine engine = openEngine()) {
            engine.put(new SpatialObject("with-payload", BoundingBox.point(new double[]{1, 1}),
                    "hello".getBytes()));
            engine.put(point("no-payload", 2, 2));
            assertEquals("hello", new String(engine.get("with-payload").orElseThrow().payload()));
            assertTrue(engine.get("no-payload").orElseThrow().payload() == null);
            engine.flush();
            assertEquals("hello", new String(engine.get("with-payload").orElseThrow().payload()));
        }
    }

    @Test
    void restartRecoversCommittedData() throws Exception {
        try (SpatialIndexEngine engine = openEngine()) {
            engine.put(point("committed", 3, 3));
            engine.put(point("flushed", 7, 7));
            engine.flush();
            engine.put(point("in-wal-only", 9, 9));
        }
        try (SpatialIndexEngine reopened = openEngine()) {
            assertTrue(reopened.get("committed").isPresent());
            assertTrue(reopened.get("flushed").isPresent());
            assertTrue(reopened.get("in-wal-only").isPresent());
            assertEquals(3, reopened.stats().liveObjects());
        }
    }

    @Test
    void restartAfterDeleteDoesNotResurrect() throws Exception {
        try (SpatialIndexEngine engine = openEngine()) {
            engine.put(point("gone", 1, 1));
            engine.delete("gone");
            engine.put(point("alive", 2, 2));
        }
        try (SpatialIndexEngine reopened = openEngine()) {
            assertTrue(reopened.get("gone").isEmpty());
            assertTrue(reopened.get("alive").isPresent());
            assertEquals(1, reopened.stats().liveObjects());
        }
    }
}
