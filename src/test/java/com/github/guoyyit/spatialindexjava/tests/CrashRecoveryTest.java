package com.github.guoyyit.spatialindexjava.tests;

import com.github.guoyyit.spatialindexjava.config.SpatialIndexProperties;
import com.github.guoyyit.spatialindexjava.core.SpatialIndexEngine;
import com.github.guoyyit.spatialindexjava.model.BoundingBox;
import com.github.guoyyit.spatialindexjava.model.RangeMode;
import com.github.guoyyit.spatialindexjava.model.SpatialObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CrashRecoveryTest {

    @TempDir
    Path dir;

    private SpatialIndexEngine openEngine() throws Exception {
        SpatialIndexProperties props = new SpatialIndexProperties();
        props.setDataDir(dir.toString());
        props.setMemtableMaxEntries(50);
        return SpatialIndexEngine.open(props);
    }

    private static SpatialObject point(String id, double... coords) {
        return new SpatialObject(id, BoundingBox.point(coords), null);
    }

    @Test
    void crashWithoutCleanShutdownKeepsCommittedWrites() throws Exception {
        SpatialIndexEngine engine = openEngine();
        for (int i = 0; i < 200; i++) {
            engine.put(point("p" + i, i, i));
        }
        engine.delete("p0");
        engine.delete("p1");
        engine.simulateCrash(); // no clean close, no final flush

        try (SpatialIndexEngine recovered = openEngine()) {
            assertEquals(198, recovered.stats().liveObjects());
            assertTrue(recovered.get("p0").isEmpty());
            assertTrue(recovered.get("p1").isEmpty());
            assertTrue(recovered.get("p199").isPresent());
            List<SpatialObject> all = recovered.range(
                    new BoundingBox(new double[]{0, 0}, new double[]{199, 199}),
                    RangeMode.CONTAINS, 0);
            assertEquals(198, all.size());
        }
    }

    @Test
    void tornWalTailIsIgnoredOnRecovery() throws Exception {
        SpatialIndexEngine engine = openEngine();
        engine.put(point("good1", 1, 1));
        engine.put(point("good2", 2, 2));
        engine.simulateCrash();

        // simulate a torn tail write: garbage appended to the WAL
        Path wal = singleWalFile();
        try (RandomAccessFile raf = new RandomAccessFile(wal.toFile(), "rw")) {
            raf.seek(raf.length());
            raf.write(new byte[]{0x7f, 0x01, 0x02}); // partial frame header
        }

        try (SpatialIndexEngine recovered = openEngine()) {
            assertTrue(recovered.get("good1").isPresent());
            assertTrue(recovered.get("good2").isPresent());
            assertEquals(2, recovered.stats().liveObjects());
            // engine still accepts writes after recovery
            recovered.put(point("after", 3, 3));
            assertTrue(recovered.get("after").isPresent());
        }
    }

    @Test
    void orphanSegmentAndTmpFilesAreCleanedUp() throws Exception {
        SpatialIndexEngine engine = openEngine();
        for (int i = 0; i < 120; i++) { // triggers background flushes
            engine.put(point("p" + i, i, i));
        }
        engine.flush();
        engine.simulateCrash();

        // leftovers from an interrupted flush/merge
        Files.write(dir.resolve("segments").resolve("seg-999999.tmp"), new byte[]{1, 2, 3});
        Files.write(dir.resolve("segments").resolve("seg-999998.seg"), new byte[]{1, 2, 3});
        Files.write(dir.resolve("MANIFEST.tmp"), "garbage".getBytes());

        try (SpatialIndexEngine recovered = openEngine()) {
            assertEquals(120, recovered.stats().liveObjects());
            assertTrue(Files.notExists(dir.resolve("segments").resolve("seg-999999.tmp")));
            assertTrue(Files.notExists(dir.resolve("segments").resolve("seg-999998.seg")));
            assertTrue(Files.notExists(dir.resolve("MANIFEST.tmp")));
        }
    }

    @Test
    void crashBetweenFlushAndWalDeletionReplaysWithoutDuplicates() throws Exception {
        SpatialIndexEngine engine = openEngine();
        for (int i = 0; i < 100; i++) {
            engine.put(point("p" + i, i, i));
        }
        engine.flush(); // segment durable, WAL deletion may or may not have happened
        engine.simulateCrash();

        try (SpatialIndexEngine recovered = openEngine()) {
            // replayed entries must not duplicate the flushed ones
            assertEquals(100, recovered.stats().liveObjects());
            List<SpatialObject> all = recovered.range(
                    new BoundingBox(new double[]{0, 0}, new double[]{99, 99}),
                    RangeMode.CONTAINS, 0);
            assertEquals(100, all.size());
            assertEquals(100, all.stream().map(SpatialObject::id).distinct().count());
        }
    }

    @Test
    void repeatedCrashRecoveryCyclesStayConsistent() throws Exception {
        for (int cycle = 0; cycle < 5; cycle++) {
            SpatialIndexEngine engine = openEngine();
            engine.put(point("cycle" + cycle, cycle, cycle));
            engine.delete("cycle" + (cycle > 0 ? cycle - 1 : 0));
            engine.simulateCrash();
        }
        try (SpatialIndexEngine recovered = openEngine()) {
            assertEquals(1, recovered.stats().liveObjects());
            assertTrue(recovered.get("cycle4").isPresent());
        }
    }

    private Path singleWalFile() throws Exception {
        try (Stream<Path> files = Files.list(dir.resolve("wal"))) {
            List<Path> logs = files.filter(p -> p.toString().endsWith(".log")).toList();
            assertEquals(1, logs.size(), "expected exactly one WAL file");
            return logs.get(0);
        }
    }
}
