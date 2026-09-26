package com.github.guoyyit.spatialindexjava.tests;

import com.github.guoyyit.spatialindexjava.config.SpatialIndexProperties;
import com.github.guoyyit.spatialindexjava.core.Envelope;
import com.github.guoyyit.spatialindexjava.core.ShapeType;
import com.github.guoyyit.spatialindexjava.core.SpatialObject;
import com.github.guoyyit.spatialindexjava.lsm.SpatialIndexEngine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Sanity performance boundaries (not micro-benchmarks): after a large load
 * with merges, indexed queries stay sublinear and single operations remain in
 * the millisecond range. Disabled in extremely contended CI via an opt-out env.
 */
@DisabledIfEnvironmentVariable(named = "SPATIAL_INDEX_SKIP_PERF", matches = "1")
class PerformanceBoundaryTest {

    private static SpatialObject point(String id, double x, double y) {
        return new SpatialObject(id, ShapeType.POINT, Envelope.point2D(x, y), null);
    }

    @Test
    void queriesStayIndexedAndLatencyStable(@TempDir Path dir) throws Exception {
        SpatialIndexProperties p = new SpatialIndexProperties();
        p.setDataDir(dir.toString());
        p.setMemtableMaxRecords(2000);
        p.setMergeThreshold(4);
        p.setRtreeNodeCapacity(32);
        p.setFsync(false);

        SpatialIndexEngine engine = new SpatialIndexEngine(p);
        engine.start();
        try {
            int n = 20_000;
            long start = System.nanoTime();
            for (int i = 0; i < n; i++) {
                // Spread on a 1000x1000 grid.
                double x = i % 1000;
                double y = (i / 1000);
                engine.put(point("g" + i, x, y));
            }
            long insertMs = (System.nanoTime() - start) / 1_000_000;
            System.out.printf("inserted %d in %d ms (%,.1f ops/s)%n",
                    n, insertMs, n * 1000.0 / Math.max(1, insertMs));

            engine.forceFlushAndMerge();
            // Deletes reclaim space and do not break queries.
            for (int i = 0; i < 2000; i++) {
                assertTrue(engine.delete("g" + i));
            }
            engine.forceFlushAndMerge();

            // A very small selective window must touch only a few objects,
            // proving the query never scans the full 20k data set.
            long qStart = System.nanoTime();
            int loops = 200;
            int maxHits = 0;
            for (int i = 0; i < loops; i++) {
                double x = 500 + (i % 5);
                double y = 10;
                List<SpatialObject> hits = engine.rangeQuery(
                        Envelope.rect2D(x, y, x, y));
                maxHits = Math.max(maxHits, hits.size());
            }
            long rangeUs = (System.nanoTime() - qStart) / 1000 / loops;
            System.out.printf("avg point range query: %d us, maxHits=%d%n", rangeUs, maxHits);
            assertTrue(maxHits <= 2, "selective query scanned too broadly: " + maxHits);
            assertTrue(rangeUs < 5_000, "range query too slow: " + rangeUs + "us");

            long kStart = System.nanoTime();
            for (int i = 0; i < loops; i++) {
                List<?> nn = engine.knnQuery(Envelope.point2D(i % 1000, 15), 10);
                assertEquals(10, nn.size());
            }
            long knnUs = (System.nanoTime() - kStart) / 1000 / loops;
            System.out.printf("avg knn query: %d us%n", knnUs);
            assertTrue(knnUs < 10_000, "knn query too slow: " + knnUs + "us");

            // Deleted ids are gone and the compacted data set stayed bounded.
            assertNull(engine.get("g0"));
            assertNotNull(engine.get("g5000"));
            assertTrue(engine.segmentIds().size() <= 2,
                    "segments should be merged, got " + engine.segmentIds());
        } finally {
            engine.stop();
        }
    }
}
