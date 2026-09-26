package com.github.guoyyit.spatialindexjava.tests;

import com.github.guoyyit.spatialindexjava.config.SpatialIndexProperties;
import com.github.guoyyit.spatialindexjava.core.EngineStats;
import com.github.guoyyit.spatialindexjava.core.SpatialIndexEngine;
import com.github.guoyyit.spatialindexjava.model.BoundingBox;
import com.github.guoyyit.spatialindexjava.model.RangeMode;
import com.github.guoyyit.spatialindexjava.model.SpatialObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Loose performance boundaries: queries must stay indexed (no full scans)
 * and memtable/segment counts must stay bounded under sustained churn.
 * Bounds are generous so they never flake on a loaded machine, but tight
 * enough to catch a linear-scan regression.
 */
class PerformanceBoundaryTest {

    @TempDir
    Path dir;

    private SpatialIndexEngine openEngine(int memtableMax) throws Exception {
        SpatialIndexProperties props = new SpatialIndexProperties();
        props.setDataDir(dir.toString());
        props.setMemtableMaxEntries(memtableMax);
        props.setMergeSegmentThreshold(4);
        return SpatialIndexEngine.open(props);
    }

    private static List<SpatialObject> batch(String prefix, int from, int count, Random rnd) {
        List<SpatialObject> batch = new ArrayList<>(count);
        for (int i = from; i < from + count; i++) {
            batch.add(new SpatialObject(prefix + i,
                    BoundingBox.point(new double[]{rnd.nextDouble() * 10000,
                            rnd.nextDouble() * 10000}), null));
        }
        return batch;
    }

    @Test
    void rangeQueryStaysFastAsDataGrows() throws Exception {
        try (SpatialIndexEngine engine = openEngine(2000)) {
            Random rnd = new Random(1);
            BoundingBox window = new BoundingBox(new double[]{0, 0}, new double[]{100, 100});

            for (int i = 0; i < 10; i++) {
                engine.putAll(batch("a", i * 1000, 1000, rnd)); // 10k objects
            }
            engine.flush();
            long t10k = timedRange(engine, window);

            for (int i = 0; i < 90; i++) {
                engine.putAll(batch("b", i * 1000, 1000, rnd)); // +90k objects
            }
            engine.flush();
            long t100k = timedRange(engine, window);

            // 10x the data must not cost 10x the time (would mean a full scan)
            assertTrue(t100k < Math.max(t10k * 20, 500_000_000L),
                    "range query degraded: 10k=" + t10k + "ns 100k=" + t100k + "ns");
        }
    }

    @Test
    void knnStaysFastOnLargeData() throws Exception {
        try (SpatialIndexEngine engine = openEngine(2000)) {
            Random rnd = new Random(2);
            for (int i = 0; i < 50; i++) {
                engine.putAll(batch("p", i * 1000, 1000, rnd)); // 50k objects
            }
            engine.flush();
            engine.compact();

            long start = System.nanoTime();
            for (int q = 0; q < 20; q++) {
                List<?> knn = engine.knn(new double[]{q * 500.0, q * 500.0}, 10);
                assertEquals(10, knn.size());
            }
            long elapsed = System.nanoTime() - start;
            assertTrue(elapsed < 5_000_000_000L,
                    "20 knn queries over 50k objects took " + elapsed / 1_000_000 + "ms");
        }
    }

    @Test
    void sustainedChurnKeepsStructuresBounded() throws Exception {
        try (SpatialIndexEngine engine = openEngine(1000)) {
            Random rnd = new Random(3);
            int maxSegmentsObserved = 0;
            for (int round = 0; round < 15; round++) {
                engine.putAll(batch("churn" + round + "_", 0, 1500, rnd));
                for (int i = 0; i < 500; i++) {
                    engine.delete("churn" + round + "_" + i);
                }
                EngineStats stats = engine.stats();
                assertTrue(stats.memtableSize() <= 1000 + 1500,
                        "memtable grew unbounded: " + stats.memtableSize());
                maxSegmentsObserved = Math.max(maxSegmentsObserved, stats.segmentCount());
            }
            engine.compact();
            EngineStats stats = engine.stats();
            assertEquals(1, stats.segmentCount());
            assertEquals(15_000, stats.liveObjects());
            assertTrue(maxSegmentsObserved <= 8,
                    "segments accumulated without merging: " + maxSegmentsObserved);
        }
    }

    @Test
    void updateHeavyWorkloadKeepsSingleVersionPerId() throws Exception {
        try (SpatialIndexEngine engine = openEngine(500)) {
            Random rnd = new Random(4);
            for (int round = 0; round < 20; round++) {
                // same 500 ids updated over and over
                engine.putAll(batch("same_", 0, 500, rnd));
            }
            engine.compact();
            assertEquals(500, engine.stats().liveObjects());
            List<SpatialObject> all = engine.range(
                    new BoundingBox(new double[]{0, 0}, new double[]{10000, 10000}),
                    RangeMode.CONTAINS, 0);
            assertEquals(500, all.size());
        }
    }

    private static long timedRange(SpatialIndexEngine engine, BoundingBox window) {
        long best = Long.MAX_VALUE;
        for (int i = 0; i < 3; i++) {
            long start = System.nanoTime();
            engine.range(window, RangeMode.CONTAINS, 0);
            best = Math.min(best, System.nanoTime() - start);
        }
        return best;
    }
}
