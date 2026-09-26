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
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MergeConsistencyTest {

    private SpatialIndexProperties props(Path dir) {
        SpatialIndexProperties p = new SpatialIndexProperties();
        p.setDataDir(dir.toString());
        p.setMemtableMaxRecords(25);
        p.setMergeThreshold(3);
        p.setRtreeNodeCapacity(8);
        p.setFsync(false);
        return p;
    }

    private static SpatialObject point(String id, double x, double y, double z) {
        return new SpatialObject(id, ShapeType.POINT, Envelope.point3D(x, y, z), null);
    }

    @Test
    void rangeAndKnnResultsAreIdenticalAcrossBackgroundMerges(@TempDir Path dir) throws Exception {
        SpatialIndexEngine engine = new SpatialIndexEngine(props(dir));
        engine.start();
        try {
            for (int i = 0; i < 400; i++) {
                engine.put(point("n" + i, (i % 40) - 20, ((i * 7) % 40) - 20, ((i * 3) % 20) - 10));
                if (i % 11 == 0) {
                    engine.delete("n" + i);
                }
            }

            Envelope range = Envelope.rect3D(-10, -10, -5, 10, 10, 5);
            Envelope knnPoint = Envelope.point3D(0, 0, 0);

            List<String> rangeBefore = engine.rangeQuery(range).stream()
                    .map(SpatialObject::getId).toList();
            List<String> knnBefore = engine.knnQuery(knnPoint, 25).stream()
                    .map(KnnCandidate::id).toList();
            int segmentsBefore = engine.segmentIds().size();

            engine.forceFlushAndMerge();
            assertTrue(engine.segmentIds().size() < segmentsBefore || segmentsBefore > 0);

            List<String> rangeAfter = engine.rangeQuery(range).stream()
                    .map(SpatialObject::getId).toList();
            List<String> knnAfter = engine.knnQuery(knnPoint, 25).stream()
                    .map(KnnCandidate::id).toList();

            assertEquals(rangeBefore, rangeAfter, "range result changed across merge");
            assertEquals(knnBefore, knnAfter, "knn result changed across merge");

            // A second full merge must be idempotent.
            engine.forceFlushAndMerge();
            assertEquals(rangeBefore, engine.rangeQuery(range).stream()
                    .map(SpatialObject::getId).toList());
            assertEquals(knnBefore, engine.knnQuery(knnPoint, 25).stream()
                    .map(KnnCandidate::id).toList());
        } finally {
            engine.stop();
        }
    }

    @Test
    void queriesWhileMergingRemainConsistent(@TempDir Path dir) throws Exception {
        SpatialIndexProperties p = props(dir);
        p.setMemtableMaxRecords(40);
        SpatialIndexEngine engine = new SpatialIndexEngine(p);
        engine.start();
        try {
            for (int i = 0; i < 200; i++) {
                engine.put(point("q" + i, i % 30, (i * 3) % 30, 0));
            }
            List<Throwable> errors = new ArrayList<>();
            Thread writer = new Thread(() -> {
                try {
                    for (int i = 200; i < 500; i++) {
                        engine.put(point("q" + i, i % 30, (i * 3) % 30, 0));
                        if (i % 50 == 0) {
                            engine.waitForMerge();
                        }
                    }
                } catch (Throwable t) {
                    errors.add(t);
                }
            });
            Thread reader = new Thread(() -> {
                try {
                    for (int i = 0; i < 1000; i++) {
                        List<String> r = engine.rangeQuery(Envelope.rect3D(0, 0, -1, 29, 29, 1))
                                .stream().map(SpatialObject::getId).toList();
                        assertEquals(r.size(), new java.util.HashSet<>(r).size());
                        engine.knnQuery(Envelope.point3D(10, 10, 0), 15);
                    }
                } catch (Throwable t) {
                    errors.add(t);
                }
            });
            writer.start();
            reader.start();
            writer.join(60000);
            reader.join(60000);
            assertTrue(errors.isEmpty(), errors::toString);
            engine.forceFlushAndMerge();
        } finally {
            engine.stop();
        }
    }
}
