package com.github.guoyyit.spatialindexjava.tests;

import com.github.guoyyit.spatialindexjava.config.SpatialIndexProperties;
import com.github.guoyyit.spatialindexjava.core.Envelope;
import com.github.guoyyit.spatialindexjava.core.KnnCandidate;
import com.github.guoyyit.spatialindexjava.core.ShapeType;
import com.github.guoyyit.spatialindexjava.core.SpatialObject;
import com.github.guoyyit.spatialindexjava.lsm.SpatialIndexEngine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class EngineBasicTest {

    private SpatialIndexProperties props(Path dir, int memMax, int mergeThreshold) {
        SpatialIndexProperties p = new SpatialIndexProperties();
        p.setDataDir(dir.toString());
        p.setMemtableMaxRecords(memMax);
        p.setMergeThreshold(mergeThreshold);
        p.setRtreeNodeCapacity(8);
        p.setFsync(false);
        return p;
    }

    private static SpatialObject point2D(String id, double x, double y, String payload) {
        return new SpatialObject(id, ShapeType.POINT, Envelope.point2D(x, y), payload);
    }

    private SpatialIndexEngine start(Path dir, int memMax, int mergeThreshold) throws Exception {
        SpatialIndexEngine engine = new SpatialIndexEngine(props(dir, memMax, mergeThreshold));
        engine.start();
        return engine;
    }

    @Test
    void upsertDeleteGetRangeAndKnn(@TempDir Path dir) throws Exception {
        try (var engine = new AutoCloseableEngine(start(dir, 100, 4))) {
            engine.engine().put(point2D("a", 0, 0, "v1"));
            engine.engine().put(point2D("b", 3, 4, null));
            engine.engine().put(point2D("c", 10, 0, null));
            // duplicate id -> upsert, moving the object and replacing payload.
            engine.engine().put(point2D("a", 5, 5, "v2"));

            assertEquals("v2", engine.engine().get("a").getPayload());
            assertNull(engine.engine().get("missing"));

            List<SpatialObject> range = engine.engine().rangeQuery(Envelope.rect2D(0, 0, 4, 4));
            assertEquals(List.of("b"), range.stream().map(SpatialObject::getId).toList());

            List<String> nn = engine.engine().knnQuery(Envelope.point2D(0, 0), 2)
                    .stream().map(KnnCandidate::id).toList();
            // a moved to (5,5): dist 7.07; b(3,4): 5.0; c(10,0):10
            assertEquals(List.of("b", "a"), nn);

            assertTrue(engine.engine().delete("b"));
            assertFalse(engine.engine().delete("b")); // repeat delete is a no-op
            assertNull(engine.engine().get("b"));

            List<String> nn2 = engine.engine().knnQuery(Envelope.point2D(0, 0), 5)
                    .stream().map(KnnCandidate::id).toList();
            assertFalse(nn2.contains("b"));
        }
    }

    @Test
    void rangeBoundaryPointsInclusiveAndEmptyResult(@TempDir Path dir) throws Exception {
        try (var engine = new AutoCloseableEngine(start(dir, 100, 4))) {
            engine.engine().put(point2D("on00", 0, 0, null));
            engine.engine().put(point2D("on10", 10, 10, null));
            engine.engine().put(point2D("inside", 5, 5, null));
            engine.engine().put(point2D("outside", 11, 5, null));

            List<SpatialObject> hits = engine.engine().rangeQuery(Envelope.rect2D(0, 0, 10, 10));
            assertEquals(List.of("inside", "on00", "on10"),
                    hits.stream().map(SpatialObject::getId).sorted().toList());

            assertTrue(engine.engine().rangeQuery(Envelope.rect2D(100, 100, 101, 101)).isEmpty());
        }
    }

    @Test
    void knnDistanceTiesResolvedById(@TempDir Path dir) throws Exception {
        try (var engine = new AutoCloseableEngine(start(dir, 100, 4))) {
            engine.engine().put(point2D("z", 1, 0, null));
            engine.engine().put(point2D("a", 0, 1, null));
            engine.engine().put(point2D("m", 0, -1, null));
            engine.engine().put(point2D("origin", 0, 0, null));
            List<String> order1 = engine.engine().knnQuery(Envelope.point2D(0, 0), 3)
                    .stream().map(KnnCandidate::id).toList();
            List<String> order2 = engine.engine().knnQuery(Envelope.point2D(0, 0), 3)
                    .stream().map(KnnCandidate::id).toList();
            assertEquals(order1, order2);
            assertEquals("origin", order1.get(0));
            // equal-distance ring sorted lexicographically
            assertEquals(List.of("a", "m"), order1.subList(1, 3));
        }
    }

    @Test
    void restartRecoversCommittedDataWithoutRebuild(@TempDir Path dir) throws Exception {
        SpatialIndexEngine first = start(dir, 4, 10);
        for (int i = 0; i < 25; i++) {
            first.put(point2D("p" + i, i, i % 3, "pl" + i));
        }
        first.delete("p5");
        first.forceFlushAndMerge();
        first.stop();

        SpatialIndexEngine second = start(dir, 4, 10);
        try {
            assertEquals("pl7", second.get("p7").getPayload());
            assertNull(second.get("p5"));
            List<String> range = second.rangeQuery(Envelope.rect2D(0, 0, 30, 0))
                    .stream().map(SpatialObject::getId).toList();
            assertTrue(range.contains("p0"));
            assertFalse(range.contains("p5"));
            List<String> nn = second.knnQuery(Envelope.point2D(0, 0), 3)
                    .stream().map(KnnCandidate::id).toList();
            assertFalse(nn.contains("p5"));
        } finally {
            second.stop();
        }
    }

    /** Tiny JVM try-with-resources wrapper since engine has a Spring lifecycle. */
    private record AutoCloseableEngine(SpatialIndexEngine engine) implements AutoCloseable {
        @Override public void close() { engine.stop(); }
    }
}
