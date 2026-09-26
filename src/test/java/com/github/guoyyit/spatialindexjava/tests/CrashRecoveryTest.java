package com.github.guoyyit.spatialindexjava.tests;

import com.github.guoyyit.spatialindexjava.config.SpatialIndexProperties;
import com.github.guoyyit.spatialindexjava.core.Envelope;
import com.github.guoyyit.spatialindexjava.core.IndexRecord;
import com.github.guoyyit.spatialindexjava.core.ShapeType;
import com.github.guoyyit.spatialindexjava.core.SpatialObject;
import com.github.guoyyit.spatialindexjava.lsm.SpatialIndexEngine;
import com.github.guoyyit.spatialindexjava.lsm.Wal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class CrashRecoveryTest {

    private SpatialIndexProperties props(Path dir) {
        SpatialIndexProperties p = new SpatialIndexProperties();
        p.setDataDir(dir.toString());
        p.setMemtableMaxRecords(100000); // keep data in the WAL for replay tests
        p.setMergeThreshold(4);
        p.setRtreeNodeCapacity(16);
        p.setFsync(false);
        return p;
    }

    private static SpatialObject point(String id, double x, double y) {
        return new SpatialObject(id, ShapeType.POINT, Envelope.point2D(x, y), null);
    }

    @Test
    @SuppressWarnings("resource")
    void tornWalFrameAfterCrashIsIgnoredAndCommittedRecordsSurvive(@TempDir Path dir) throws Exception {
        SpatialIndexEngine first = new SpatialIndexEngine(props(dir));
        first.start();
        for (int i = 0; i < 50; i++) {
            first.put(point("p" + i, i, i));
        }
        first.delete("p49");
        first.stop(); // orderly stop leaves the WAL on disk (no flush yet)

        // Find the WAL and append a torn partial frame (simulated crash mid-write).
        Path wal;
        try (var list = Files.list(dir)) {
            wal = list.filter(p -> p.getFileName().toString().startsWith("wal-")).findFirst().orElseThrow();
        }
        long goodSize = Files.size(wal);
        try (RandomAccessFile raf = new RandomAccessFile(wal.toFile(), "rw")) {
            raf.seek(goodSize);
            raf.write(new byte[]{0, 0, 0, 0x57, 0, 0, 1, 0}); // partial bogus header
        }

        SpatialIndexEngine second = new SpatialIndexEngine(props(dir));
        second.start();
        try {
            assertNotNull(second.get("p0"));
            assertNotNull(second.get("p48"));
            assertNull(second.get("p49"), "tombstone must be recovered");
            Set<String> ids = new HashSet<>(second.rangeQuery(Envelope.rect2D(0, 0, 100, 100))
                    .stream().map(SpatialObject::getId).toList());
            assertEquals(49, ids.size());
            assertFalse(ids.contains("p49"));
        } finally {
            second.stop();
        }
    }

    @Test
    void corruptWholeFrameCrcStopsReplayAtThatPoint(@TempDir Path dir) throws Exception {
        SpatialIndexEngine first = new SpatialIndexEngine(props(dir));
        first.start();
        first.put(point("a", 1, 1));
        first.put(point("b", 2, 2));
        first.stop();

        Path wal;
        try (var list = Files.list(dir)) {
            wal = list.filter(p -> p.getFileName().toString().startsWith("wal-")).findFirst().orElseThrow();
        }
        // Corrupt a byte inside the file; frames after the corruption are unreachable,
        // but the index must still open (frames before it may be partial too).
        long size = Files.size(wal);
        try (RandomAccessFile raf = new RandomAccessFile(wal.toFile(), "rw")) {
            raf.seek(size / 2);
            raf.write(0xFF);
            raf.write(0xFF);
        }
        SpatialIndexEngine second = new SpatialIndexEngine(props(dir));
        second.start(); // must not throw
        try {
            List<SpatialObject> all = second.rangeQuery(Envelope.rect2D(-100, -100, 100, 100));
            // Whatever survived is consistent: no duplicate ids.
            Set<String> unique = new HashSet<>(all.stream().map(SpatialObject::getId).toList());
            assertEquals(unique.size(), all.size());
        } finally {
            second.stop();
        }
    }

    @Test
    void leftoverTempFilesAreRemovedOnRestart(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("seg-99.seg.tmp"), "partial");
        Files.writeString(dir.resolve("manifest.json.tmp"), "{}");
        SpatialIndexEngine engine = new SpatialIndexEngine(props(dir));
        engine.start();
        try {
            assertFalse(Files.exists(dir.resolve("seg-99.seg.tmp")));
            assertFalse(Files.exists(dir.resolve("manifest.json.tmp")));
            assertFalse(Files.exists(dir.resolve("seg-99.seg")), "uncommitted segment must not appear");
            assertTrue(engine.rangeQuery(Envelope.rect2D(-1, -1, 1, 1)).isEmpty());
        } finally {
            engine.stop();
        }
    }

    @Test
    void restartAfterMergeHasNoDuplicatesOrMissingObjects(@TempDir Path dir) throws Exception {
        SpatialIndexProperties p = props(dir);
        p.setMemtableMaxRecords(30);
        p.setMergeThreshold(3);
        SpatialIndexEngine first = new SpatialIndexEngine(p);
        first.start();
        for (int i = 0; i < 300; i++) {
            first.put(point("k" + i, i % 50, i / 50));
            if (i % 7 == 0) {
                first.delete("k" + i);
            }
        }
        first.forceFlushAndMerge();
        first.stop();

        SpatialIndexEngine second = new SpatialIndexEngine(props(dir));
        second.start();
        try {
            List<SpatialObject> all = second.rangeQuery(Envelope.rect2D(-100, -100, 100, 100));
            Set<String> ids = new HashSet<>();
            for (SpatialObject o : all) {
                assertTrue(ids.add(o.getId()), "duplicate after recovery: " + o.getId());
            }
            for (int i = 0; i < 300; i++) {
                boolean shouldExist = i % 7 != 0;
                assertEquals(shouldExist, second.get("k" + i) != null, "id k" + i);
            }
            assertTrue(second.segmentIds().size() <= 2, "merge should compact segments");
        } finally {
            second.stop();
        }
    }
}
