package com.github.guoyyit.spatialindexjava.lsm;

import com.github.guoyyit.spatialindexjava.core.Envelope;
import com.github.guoyyit.spatialindexjava.core.IndexRecord;
import com.github.guoyyit.spatialindexjava.core.KnnSearchResult;
import com.github.guoyyit.spatialindexjava.core.SpatialObject;
import com.github.guoyyit.spatialindexjava.core.SpatialSource;
import com.github.guoyyit.spatialindexjava.rtree.EntryView;
import com.github.guoyyit.spatialindexjava.rtree.StaticRTree;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.List;

/**
 * Read-only view of a segment file. The whole file is memory mapped (off-heap,
 * paged lazily by the OS); only compact node metadata and one tiny
 * {@link SegEntry} per record are retained on the Java heap. Record bodies
 * (id/payload) are decoded on demand from the mapping, so opening a large
 * segment does not load all payloads into the heap.
 */
public final class SegmentReader implements SpatialSource {

    private static final class SegEntry {
        final int offset;
        final Envelope envelope;
        final boolean tombstone;

        SegEntry(int offset, Envelope envelope, boolean tombstone) {
            this.offset = offset;
            this.envelope = envelope;
            this.tombstone = tombstone;
        }
    }

    private final class SegEntryView implements EntryView<SegEntry> {
        @Override public Envelope envelope(SegEntry e) { return e.envelope; }
        @Override public String id(SegEntry e) { return decodeRecord(e).getId(); }
        @Override public boolean tombstone(SegEntry e) { return e.tombstone; }
        @Override public SpatialObject toObject(SegEntry e) { return decodeRecord(e).toObject(); }
    }

    private final Path file;
    private final long segmentId;
    private final long maxSequence;
    private final int recordCount;
    private final MappedByteBuffer mapping;
    private final FileChannel channel;
    private final StaticRTree<SegEntry> tree;
    private final Map<String, SegEntry> idIndex = new HashMap<>();

    public SegmentReader(Path file, long segmentId) throws IOException {
        this.file = file;
        this.segmentId = segmentId;
        this.channel = FileChannel.open(file, StandardOpenOption.READ);
        long size = channel.size();
        if (size < SegmentWriter.HEADER_BYTES) {
            channel.close();
            throw new IOException("segment file too short: " + file);
        }
        this.mapping = channel.map(FileChannel.MapMode.READ_ONLY, 0, size);
        this.mapping.order(ByteOrder.LITTLE_ENDIAN);

        long magic = mapping.getLong(0);
        if (magic != SegmentWriter.MAGIC) {
            throw new IOException("bad segment magic in " + file);
        }
        int version = mapping.getInt(8);
        if (version != SegmentWriter.VERSION) {
            throw new IOException("unsupported segment version " + version + " in " + file);
        }
        long fileSegId = mapping.getLong(12);
        if (fileSegId != segmentId) {
            throw new IOException("segment id mismatch in " + file + ": file=" + fileSegId + " expected=" + segmentId);
        }
        this.maxSequence = mapping.getLong(20);
        this.recordCount = mapping.getInt(28);
        int nodeCount = mapping.getInt(32);
        int rootIndex = mapping.getInt(36);

        List<Envelope> nodeEnvelopes = new ArrayList<>(nodeCount);
        List<int[]> nodeChildren = new ArrayList<>(nodeCount);
        List<Boolean> nodeLeaf = new ArrayList<>(nodeCount);

        int position = SegmentWriter.HEADER_BYTES;
        for (int i = 0; i < nodeCount; i++) {
            boolean leaf = mapping.get(position) != 0;
            Envelope env = readNodeEnvelope(position + 1);
            int childCount = mapping.getInt(position + 1 + 48);
            int[] children = new int[childCount];
            int childPos = position + SegmentWriter.NODE_FIXED_BYTES;
            for (int c = 0; c < childCount; c++) {
                children[c] = mapping.getInt(childPos + c * 4);
            }
            nodeEnvelopes.add(env);
            nodeChildren.add(children);
            nodeLeaf.add(leaf);
            position = childPos + childCount * 4;
        }

        List<SegEntry> entries = new ArrayList<>(recordCount);
        for (int i = 0; i < recordCount; i++) {
            int recordOffset = (int) position;
            byte flags = mapping.get(recordOffset + 8);
            Envelope env = readRecordEnvelope(recordOffset + 9, flags);
            boolean tombstone = (flags & RecordCodec.FLAG_TOMBSTONE) != 0;
            entries.add(new SegEntry(recordOffset, env, tombstone));
            position = skipRecord(recordOffset);
        }

        this.tree = new StaticRTree<>(new SegEntryView(), entries,
                nodeEnvelopes, nodeChildren, nodeLeaf, rootIndex);
        for (SegEntry e : entries) {
            idIndex.put(decodeRecord(e).getId(), e);
        }
    }

    /** Read a record envelope; dimension comes from the record flags byte. */
    private Envelope readRecordEnvelope(int position, byte flags) {
        double minX = mapping.getDouble(position);
        double minY = mapping.getDouble(position + 8);
        double minZ = mapping.getDouble(position + 16);
        double maxX = mapping.getDouble(position + 24);
        double maxY = mapping.getDouble(position + 32);
        double maxZ = mapping.getDouble(position + 40);
        if ((flags & RecordCodec.FLAG_3D) != 0) {
            return new Envelope(3, minX, minY, minZ, maxX, maxY, maxZ);
        }
        return new Envelope(2, minX, minY, 0.0d, maxX, maxY, 0.0d);
    }

    /** Read a node envelope; nodes always carry the full 3D extent. */
    private Envelope readNodeEnvelope(int position) {
        double minX = mapping.getDouble(position);
        double minY = mapping.getDouble(position + 8);
        double minZ = mapping.getDouble(position + 16);
        double maxX = mapping.getDouble(position + 24);
        double maxY = mapping.getDouble(position + 32);
        double maxZ = mapping.getDouble(position + 40);
        return new Envelope(3, minX, minY, minZ, maxX, maxY, maxZ);
    }

    private int skipRecord(int position) {
        // sequence(8) | flags(1) | 6 doubles(48) | id length prefix.
        int p = position + 8 + 1 + 48;
        int idLen = mapping.getInt(p);
        p += 4 + idLen;
        int payloadLen = mapping.getInt(p);
        p += 4;
        if (payloadLen > 0) {
            p += payloadLen;
        }
        return p;
    }

    private IndexRecord decodeRecord(SegEntry entry) {
        ByteBuffer slice = mapping.slice(entry.offset, mapping.capacity() - entry.offset);
        slice.order(ByteOrder.BIG_ENDIAN);
        return RecordCodec.read(slice);
    }

    /**
     * All records in the segment (merge use only). Segments are already
     * de-duplicated per id.
     */
    public List<IndexRecord> allRecords() {
        List<IndexRecord> all = new ArrayList<>(recordCount);
        for (int i = 0; i < recordCount; i++) {
            SegEntry e = tree.entryAt(i);
            all.add(decodeRecord(e));
        }
        return all;
    }

    /** Point lookup by id, or null. Segments keep a small hash of id -> entry. */
    public IndexRecord lookupById(String id) {
        SegEntry e = idIndex.get(id);
        return e == null ? null : decodeRecord(e);
    }

    public long segmentId() { return segmentId; }
    public int recordCount() { return recordCount; }
    @Override public long maxSequence() { return maxSequence; }

    @Override
    public List<IndexRecord> rangeQuery(Envelope query) {
        List<SegEntry> hits = tree.range(query);
        List<IndexRecord> out = new ArrayList<>(hits.size());
        for (SegEntry e : hits) {
            out.add(decodeRecord(e));
        }
        return out;
    }

    @Override
    public KnnSearchResult knnSearch(Envelope point, int bound) {
        return tree.knn(point, bound);
    }

    @Override
    public void close() {
        // MappedByteBuffers are unmapped by GC; the channel close is best-effort.
        try {
            channel.close();
        } catch (IOException ignored) {
            // closing a mapped channel has no functional effect on read correctness
        }
    }
}
