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
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class ConcurrencyTest {

    private SpatialIndexProperties props(Path dir) {
        SpatialIndexProperties p = new SpatialIndexProperties();
        p.setDataDir(dir.toString());
        p.setMemtableMaxRecords(200);
        p.setMergeThreshold(3);
        p.setRtreeNodeCapacity(16);
        p.setFsync(false);
        return p;
    }

    private static SpatialObject point(String id, double x, double y) {
        return new SpatialObject(id, ShapeType.POINT, Envelope.point2D(x, y), null);
    }

    @Test
    void concurrentWritesAndQueriesAreConsistent(@TempDir Path dir) throws Exception {
        SpatialIndexEngine engine = new SpatialIndexEngine(props(dir));
        engine.start();
        try {
            int writers = 4;
            int perWriter = 500;
            ExecutorService pool = Executors.newFixedThreadPool(8);
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(writers);
            List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());
            Set<String> liveIds = ConcurrentHashMap.newKeySet();

            for (int w = 0; w < writers; w++) {
                final int base = w;
                pool.submit(() -> {
                    try {
                        start.await();
                        for (int i = 0; i < perWriter; i++) {
                            String id = "w" + base + "-" + i;
                            double x = (base * 31 + i) % 100;
                            double y = (base * 17 + i) % 100;
                            engine.put(point(id, x, y));
                            liveIds.add(id);
                            if (i % 10 == 0) {
                                engine.delete(id);
                                liveIds.remove(id);
                                engine.put(point(id, x + 1, y)); // re-insert moved
                                liveIds.add(id);
                            }
                        }
                    } catch (Throwable t) {
                        errors.add(t);
                    } finally {
                        done.countDown();
                    }
                });
            }
            // Readers: queries must never throw, never return duplicate ids,
            // and range results must be id-sorted (deterministic shape).
            AtomicReference<List<String>> lastRange = new AtomicReference<>();
            List<Thread> readers = new ArrayList<>();
            AtomicBoolean reading = new AtomicBoolean(true);
            for (int r = 0; r < 3; r++) {
                Thread reader = new Thread(() -> {
                    while (reading.get()) {
                        try {
                            List<SpatialObject> q = engine.rangeQuery(
                                    Envelope.rect2D(0, 0, 60, 60));
                            List<String> ids = q.stream().map(SpatialObject::getId).toList();
                            assertEquals(ids.size(), new HashSet<>(ids).size(), "duplicate id in range result");
                            List<String> sorted = new ArrayList<>(ids);
                            Collections.sort(sorted);
                            assertEquals(sorted, ids, "range result must be id-sorted");
                            List<String> nn = engine.knnQuery(Envelope.point2D(50, 50), 20)
                                    .stream().map(KnnCandidate::id).toList();
                            assertEquals(nn.size(), new HashSet<>(nn).size(), "duplicate id in knn result");
                            lastRange.set(ids);
                        } catch (Throwable t) {
                            errors.add(t);
                        }
                    }
                });
                reader.setDaemon(true);
                reader.start();
                readers.add(reader);
            }

            start.countDown();
            assertTrue(done.await(60, TimeUnit.SECONDS));
            reading.set(false);
            for (Thread t : readers) {
                t.join(2000);
            }
            pool.shutdown();
            pool.awaitTermination(5, TimeUnit.SECONDS);

            engine.forceFlushAndMerge();
            assertTrue(errors.isEmpty(), () -> "worker errors: " + errors);

            // Final state: every expected id present exactly once, deleted ones absent.
            Set<String> present = new HashSet<>();
            for (SpatialObject o : engine.rangeQuery(Envelope.rect2D(-1000, -1000, 1000, 1000))) {
                assertTrue(present.add(o.getId()), "duplicate committed id " + o.getId());
            }
            assertTrue(present.containsAll(liveIds), "missing committed objects");
            assertEquals(liveIds.size(), present.size());

            // Repeatable result after merge.
            List<String> a = engine.rangeQuery(Envelope.rect2D(0, 0, 60, 60))
                    .stream().map(SpatialObject::getId).toList();
            List<String> b = engine.rangeQuery(Envelope.rect2D(0, 0, 60, 60))
                    .stream().map(SpatialObject::getId).toList();
            assertEquals(a, b);
        } finally {
            engine.stop();
        }
    }
}
