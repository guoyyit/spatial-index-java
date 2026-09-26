package com.github.guoyyit.spatialindexjava.tests;

import com.github.guoyyit.spatialindexjava.core.Envelope;
import com.github.guoyyit.spatialindexjava.core.IndexRecord;
import com.github.guoyyit.spatialindexjava.core.KnnCandidate;
import com.github.guoyyit.spatialindexjava.core.ShapeType;
import com.github.guoyyit.spatialindexjava.core.SpatialObject;
import com.github.guoyyit.spatialindexjava.lsm.SegmentReader;
import com.github.guoyyit.spatialindexjava.lsm.SegmentWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SegmentRoundTripTest {

    private static IndexRecord rec(long seq, String id, double x, double y, boolean threeD, String payload) {
        Envelope env = threeD ? Envelope.point3D(x, y, x + y) : Envelope.point2D(x, y);
        return IndexRecord.put(seq, new SpatialObject(id, ShapeType.POINT, env, payload));
    }

    @Test
    void writeThenReadPreservesRangeKnnPayloadsAndMetadata(@TempDir Path dir) throws Exception {
        List<IndexRecord> records = new ArrayList<>();
        for (int i = 1; i < 200; i++) {
            records.add(rec(i + 1, "p" + i, i % 13, i / 13, i % 5 == 0, "payload-" + i));
        }
        // Include a 3D rectangle and a tombstone (segments are deduped per id).
        records.add(IndexRecord.put(1000, new SpatialObject("box", ShapeType.RECTANGLE,
                Envelope.rect3D(-1, -1, -1, 1, 1, 1), "b")));
        records.add(IndexRecord.tombstone(1001, "p0", ShapeType.POINT, Envelope.point2D(0, 0)));

        new SegmentWriter(dir, 7, 8).write(records);
        assertTrue(dir.resolve("seg-7.seg").toFile().exists());
        assertFalse(dir.resolve("seg-7.seg.tmp").toFile().exists());

        try (SegmentReader reader = new SegmentReader(dir.resolve("seg-7.seg"), 7)) {
            assertEquals(7, reader.segmentId());
            assertEquals(records.size(), reader.recordCount());
            assertEquals(1001, reader.maxSequence());

            List<IndexRecord> hits = reader.rangeQuery(Envelope.rect2D(0, 0, 3, 3));
            assertFalse(hits.isEmpty());
            for (IndexRecord r : hits) {
                assertTrue(Envelope.rect2D(0, 0, 3, 3).intersects(r.getEnvelope()));
            }

            List<String> nearest = reader.knnSearch(Envelope.point2D(0, 0), 5)
                    .candidates().stream().map(KnnCandidate::id).toList();
            // p0 is tombstoned and must never be returned.
            assertFalse(nearest.contains("p0"));
            assertEquals(5, nearest.size());

            // 3D query sees the box and 3D points.
            List<IndexRecord> boxHits = reader.rangeQuery(Envelope.rect3D(-1, -1, -1, 1, 1, 1));
            assertTrue(boxHits.stream().anyMatch(r -> r.getId().equals("box")));

            // Payload and shape preserved.
            SpatialObject box = boxHits.stream().filter(r -> r.getId().equals("box"))
                    .findFirst().orElseThrow().toObject();
            assertEquals(ShapeType.RECTANGLE, box.getShapeType());
            assertEquals("b", box.getPayload());
        }
    }

    @Test
    void truncatedTempFilesAreNeverPublished(@TempDir Path dir) throws Exception {
        List<IndexRecord> records = new ArrayList<>();
        records.add(rec(1, "a", 0, 0, false, null));
        SegmentWriter writer = new SegmentWriter(dir, 1, 8);
        writer.write(records);
        // A corrupt leftover temp file from an aborted write must not open as a segment.
        Path tmp = dir.resolve("seg-9.seg.tmp");
        java.nio.file.Files.write(tmp, new byte[]{0, 1, 2, 3});
        assertThrows(IOException.class,
                () -> new SegmentReader(tmp, 9).close());
    }
}
