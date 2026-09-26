package com.github.guoyyit.spatialindexjava.tests;

import com.github.guoyyit.spatialindexjava.config.SpatialIndexProperties;
import com.github.guoyyit.spatialindexjava.core.SpatialIndexEngine;
import com.github.guoyyit.spatialindexjava.model.BoundingBox;
import com.github.guoyyit.spatialindexjava.model.RangeMode;
import com.github.guoyyit.spatialindexjava.model.SpatialObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MergeAndSpaceTest {

    @TempDir
    Path dir;

    private SpatialIndexEngine openEngine() throws Exception {
        SpatialIndexProperties props = new SpatialIndexProperties();
        props.setDataDir(dir.toString());
        props.setMemtableMaxEntries(100);
        props.setMergeSegmentThreshold(3);
        return SpatialIndexEngine.open(props);
    }

    private static SpatialObject point(String id, double... coords) {
        return new SpatialObject(id, BoundingBox.point(coords), null);
    }

    @Test
    void backgroundMergeCollapsesSegments() throws Exception {
        try (SpatialIndexEngine engine = openEngine()) {
            for (int round = 0; round < 5; round++) {
                for (int i = 0; i < 100; i++) {
                    engine.put(point("r" + round + "_" + i, i, round));
                }
                engine.flush();
            }
            assertTrue(engine.stats().segmentCount() >= 1);
            engine.compact();
            assertEquals(1, engine.stats().segmentCount());
            assertEquals(500, engine.stats().liveObjects());
        }
    }

    @Test
    void mergeDropsOverwrittenVersions() throws Exception {
        try (SpatialIndexEngine engine = openEngine()) {
            // write the same ids many times across several segments
            for (int round = 0; round < 4; round++) {
                for (int i = 0; i < 100; i++) {
                    engine.put(point("id" + i, i, round));
                }
                engine.flush();
            }
            engine.compact();
            assertEquals(100, engine.stats().liveObjects());
            // newest version wins
            SpatialObject obj = engine.get("id42").orElseThrow();
            assertEquals(3.0, obj.box().min(1));
            assertEquals(1, engine.stats().segmentCount());
        }
    }

    @Test
    void deletedSpaceIsReclaimedAfterMerge() throws Exception {
        Path segments = dir.resolve("segments");
        try (SpatialIndexEngine engine = openEngine()) {
            for (int i = 0; i < 1000; i++) {
                engine.put(point("p" + i, i, i));
            }
            engine.flush();
            long sizeWithData = directorySize(segments);
            assertTrue(sizeWithData > 0);

            for (int i = 0; i < 1000; i++) {
                engine.delete("p" + i);
            }
            engine.flush();
            engine.compact();

            assertEquals(0, engine.stats().liveObjects());
            assertEquals(0, engine.stats().segmentCount(), "empty merge should drop all segments");
            assertEquals(0, directorySize(segments), "segment files must be reclaimed");

            // queries on the empty index still work
            assertTrue(engine.range(new BoundingBox(new double[]{0, 0}, new double[]{999, 999}),
                    RangeMode.CONTAINS, 0).isEmpty());
            assertTrue(engine.knn(new double[]{0, 0}, 5).isEmpty());
        }
        // and the empty state survives a restart
        try (SpatialIndexEngine reopened = openEngine()) {
            assertEquals(0, reopened.stats().liveObjects());
        }
    }

    @Test
    void tombstonesDoNotResurrectOlderSegmentVersions() throws Exception {
        try (SpatialIndexEngine engine = openEngine()) {
            engine.put(point("x", 1, 1));
            engine.flush();          // segment 1 holds the put
            engine.delete("x");
            engine.flush();          // segment 2 holds the tombstone
            assertTrue(engine.get("x").isEmpty());
            engine.compact();        // merge must keep x gone
            assertTrue(engine.get("x").isEmpty());
            assertEquals(0, engine.stats().liveObjects());
        }
        try (SpatialIndexEngine reopened = openEngine()) {
            assertTrue(reopened.get("x").isEmpty());
        }
    }

    @Test
    void mergeWhileWritingKeepsNewerData() throws Exception {
        try (SpatialIndexEngine engine = openEngine()) {
            for (int i = 0; i < 300; i++) {
                engine.put(point("old" + i, i, i));
            }
            engine.flush();
            // new writes land in the memtable while segments merge
            for (int i = 0; i < 50; i++) {
                engine.put(point("new" + i, i, i));
            }
            engine.compact();
            assertEquals(350, engine.stats().liveObjects());
            List<SpatialObject> all = engine.range(
                    new BoundingBox(new double[]{0, 0}, new double[]{299, 299}),
                    RangeMode.CONTAINS, 0);
            assertEquals(350, all.size());
        }
    }

    private static long directorySize(Path dir) throws Exception {
        if (!Files.isDirectory(dir)) {
            return 0;
        }
        try (Stream<Path> files = Files.list(dir)) {
            long total = 0;
            for (Path f : files.toList()) {
                total += Files.size(f);
            }
            return total;
        }
    }
}
