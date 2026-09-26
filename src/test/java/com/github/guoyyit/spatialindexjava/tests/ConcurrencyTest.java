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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class ConcurrencyTest {

    @TempDir
    Path dir;

    private SpatialIndexEngine openEngine() throws Exception {
        SpatialIndexProperties props = new SpatialIndexProperties();
        props.setDataDir(dir.toString());
        props.setMemtableMaxEntries(200);
        props.setMergeSegmentThreshold(3);
        return SpatialIndexEngine.open(props);
    }

    private static SpatialObject point(String id, double... coords) {
        return new SpatialObject(id, BoundingBox.point(coords), null);
    }

    @Test
    void readersNeverSeePartialBatches() throws Exception {
        try (SpatialIndexEngine engine = openEngine()) {
            int batches = 100;
            int batchSize = 20;
            ExecutorService pool = Executors.newFixedThreadPool(4);
            CountDownLatch start = new CountDownLatch(1);
            AtomicInteger violations = new AtomicInteger();

            Future<?> writer = pool.submit(() -> {
                try {
                    start.await();
                    for (int b = 0; b < batches; b++) {
                        List<SpatialObject> batch = new ArrayList<>();
                        for (int i = 0; i < batchSize; i++) {
                            batch.add(point("b" + b + "_" + i, b, i));
                        }
                        engine.putAll(batch);
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });

            List<Future<?>> readers = new ArrayList<>();
            for (int r = 0; r < 3; r++) {
                readers.add(pool.submit(() -> {
                    try {
                        start.await();
                        BoundingBox all = new BoundingBox(
                                new double[]{0, 0}, new double[]{batches, batchSize});
                        while (!writer.isDone()) {
                            List<SpatialObject> hits = engine.range(all, RangeMode.CONTAINS, 0);
                            // per-batch atomicity: each batch contributes 0 or batchSize hits
                            int[] counts = new int[batches];
                            for (SpatialObject o : hits) {
                                counts[Integer.parseInt(o.id().split("_")[0].substring(1))]++;
                            }
                            for (int c : counts) {
                                if (c != 0 && c != batchSize) {
                                    violations.incrementAndGet();
                                }
                            }
                        }
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }));
            }
            start.countDown();
            writer.get(60, TimeUnit.SECONDS);
            for (Future<?> reader : readers) {
                reader.get(60, TimeUnit.SECONDS);
            }
            pool.shutdown();
            assertEquals(0, violations.get(), "readers observed a partial batch");
            assertEquals(batches * batchSize, engine.stats().liveObjects());
        }
    }

    @Test
    void concurrentWritersAndFinalStateIsExact() throws Exception {
        try (SpatialIndexEngine engine = openEngine()) {
            int threads = 8;
            int perThread = 500;
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            List<Future<?>> futures = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                final int thread = t;
                futures.add(pool.submit(() -> {
                    try {
                        for (int i = 0; i < perThread; i++) {
                            engine.put(point("t" + thread + "_" + i, thread * 1000.0 + i, i));
                            if (i % 3 == 0) {
                                engine.delete("t" + thread + "_" + i);
                            }
                        }
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }));
            }
            for (Future<?> f : futures) {
                f.get(120, TimeUnit.SECONDS);
            }
            pool.shutdown();

            long expectedLive = threads * (perThread - (perThread + 2) / 3);
            assertEquals(expectedLive, engine.stats().liveObjects());

            // results are reproducible: two identical queries return identical lists
            BoundingBox all = new BoundingBox(new double[]{0, 0}, new double[]{100000, perThread});
            List<String> first = engine.range(all, RangeMode.CONTAINS, 0)
                    .stream().map(SpatialObject::id).toList();
            List<String> second = engine.range(all, RangeMode.CONTAINS, 0)
                    .stream().map(SpatialObject::id).toList();
            assertEquals(first, second);
            assertEquals(expectedLive, first.size());

            // knn is reproducible too
            List<String> knn1 = engine.knn(new double[]{500, 5}, 20)
                    .stream().map(s -> s.object().id()).toList();
            List<String> knn2 = engine.knn(new double[]{500, 5}, 20)
                    .stream().map(s -> s.object().id()).toList();
            assertEquals(knn1, knn2);
            assertEquals(20, knn1.size());
        }
    }

    @Test
    void queriesDuringFlushAndMergeStayConsistent() throws Exception {
        try (SpatialIndexEngine engine = openEngine()) {
            int total = 3000;
            for (int i = 0; i < total; i++) {
                engine.put(point("p" + i, i % 100, i / 100));
            }
            BoundingBox window = new BoundingBox(new double[]{10, 10}, new double[]{20, 20});
            List<String> before = engine.range(window, RangeMode.CONTAINS, 0)
                    .stream().map(SpatialObject::id).toList();
            List<ScoredObject> knnBefore = engine.knn(new double[]{50, 50}, 25);

            engine.flush();
            List<String> afterFlush = engine.range(window, RangeMode.CONTAINS, 0)
                    .stream().map(SpatialObject::id).toList();
            assertEquals(before, afterFlush, "flush changed range results");

            engine.compact();
            List<String> afterMerge = engine.range(window, RangeMode.CONTAINS, 0)
                    .stream().map(SpatialObject::id).toList();
            assertEquals(before, afterMerge, "merge changed range results");

            List<String> knnAfter = engine.knn(new double[]{50, 50}, 25)
                    .stream().map(s -> s.object().id()).toList();
            assertEquals(knnBefore.stream().map(s -> s.object().id()).toList(), knnAfter,
                    "merge changed knn results");
        }
    }

    @Test
    void noDeadlockUnderMixedLoad() throws Exception {
        try (SpatialIndexEngine engine = openEngine()) {
            ExecutorService pool = Executors.newFixedThreadPool(10);
            List<Future<?>> futures = new ArrayList<>();
            for (int t = 0; t < 10; t++) {
                final int thread = t;
                futures.add(pool.submit(() -> {
                    try {
                        for (int i = 0; i < 200; i++) {
                            switch (thread % 4) {
                                case 0 -> engine.put(point("w" + thread + "_" + i, i, i));
                                case 1 -> engine.delete("w0_" + i);
                                case 2 -> engine.range(
                                        new BoundingBox(new double[]{0, 0}, new double[]{50, 50}),
                                        RangeMode.CONTAINS, 10);
                                default -> engine.knn(new double[]{i, i}, 5);
                            }
                        }
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }));
            }
            for (Future<?> f : futures) {
                try {
                    f.get(90, TimeUnit.SECONDS);
                } catch (java.util.concurrent.TimeoutException e) {
                    fail("mixed load deadlocked");
                }
            }
            pool.shutdown();
            assertTrue(engine.stats().lastSeq() > 0);
        }
    }
}
