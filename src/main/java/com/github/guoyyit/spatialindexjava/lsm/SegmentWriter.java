package com.github.guoyyit.spatialindexjava.lsm;

import com.github.guoyyit.spatialindexjava.core.Envelope;
import com.github.guoyyit.spatialindexjava.core.IndexRecord;
import com.github.guoyyit.spatialindexjava.rtree.StaticRTree;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

/**
 * Writes an immutable segment file {@code seg-N.seg} from a compacted,
 * de-duplicated record list. The data goes to {@code seg-N.seg.tmp}, is
 * fsynced, then atomically renamed and the directory fsynced, so a crash can
 * never leave a partial segment visible to the manifest.
 *
 * <p>File layout (little-endian):
 * <pre>
 * magic(8) | version(int) | segId(long) | maxSeq(long) | recordCount(int)
 * | nodeCount(int) | rootNodeIndex(int)
 * node[nodeCount]: leaf(byte) | 6 doubles envelope | childCount(int) | int[childCount]
 * record[recordCount]: encoded RecordCodec records
 * </pre>
 */
public final class SegmentWriter {

    static final long MAGIC = 0x53_45_47_31L; // "SEG1"
    static final int VERSION = 1;
    static final int HEADER_BYTES = 8 + 4 + 8 + 8 + 4 + 4 + 4;
    static final int NODE_FIXED_BYTES = 1 + 6 * 8 + 4;

    private final Path directory;
    private final long segmentId;
    private final int rtreeNodeCapacity;

    public SegmentWriter(Path directory, long segmentId, int rtreeNodeCapacity) {
        this.directory = directory;
        this.segmentId = segmentId;
        this.rtreeNodeCapacity = rtreeNodeCapacity;
    }

    public Path segmentFile() {
        return directory.resolve("seg-" + segmentId + ".seg");
    }

    public Path tempFile() {
        return directory.resolve("seg-" + segmentId + ".seg.tmp");
    }

    public void write(List<IndexRecord> records) throws IOException {
        if (records.isEmpty()) {
            throw new IllegalArgumentException("cannot persist an empty segment");
        }
        StaticRTree<IndexRecord> tree =
                new StaticRTree<>(rtreeNodeCapacity, Memtable.RECORD_VIEW, records);
        long maxSequence = Long.MIN_VALUE;
        long recordsBytes = 0;
        long nodesBytes = 0;
        int nodeCount = 0;
        int rootIndex = 0;
        for (IndexRecord r : records) {
            maxSequence = Math.max(maxSequence, r.getSequence());
            recordsBytes += RecordCodec.encodedSize(r);
        }
        // Count nodes and root index via a first pass.
        int[] rootHolder = new int[]{-1};
        final int[] nodeCountHolder = new int[]{0};
        final long[] nodesBytesHolder = new long[]{0};
        tree.forEachNode((index, leaf, envelope, children) -> {
            rootHolder[0] = index;
            nodeCountHolder[0]++;
            nodesBytesHolder[0] += NODE_FIXED_BYTES + (long) children.length * 4;
        });
        nodeCount = nodeCountHolder[0];
        rootIndex = rootHolder[0];
        long total = HEADER_BYTES + nodesBytesHolder[0] + recordsBytes;

        Files.createDirectories(directory);
        Path tmp = tempFile();
        Files.deleteIfExists(tmp);
        try (FileChannel ch = FileChannel.open(tmp,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            ByteBuffer header = ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN);
            header.putLong(MAGIC);
            header.putInt(VERSION);
            header.putLong(segmentId);
            header.putLong(maxSequence);
            header.putInt(records.size());
            header.putInt(nodeCount);
            header.putInt(rootIndex);
            header.flip();
            ch.write(header);

            // Nodes in index order.
            tree.forEachNode((index, leaf, envelope, children) -> {
                try {
                    ByteBuffer nodeBuf = ByteBuffer
                            .allocate(NODE_FIXED_BYTES + children.length * 4)
                            .order(ByteOrder.LITTLE_ENDIAN);
                    nodeBuf.put((byte) (leaf ? 1 : 0));
                    putEnvelope(nodeBuf, envelope);
                    nodeBuf.putInt(children.length);
                    for (int child : children) {
                        nodeBuf.putInt(child);
                    }
                    nodeBuf.flip();
                    writeFully(ch, nodeBuf);
                } catch (IOException e) {
                    throw new SegmentWriteException(e);
                }
            });

            // Records in input order; leaf children encode entry index -(idx+1).
            for (IndexRecord record : records) {
                ByteBuffer recordBuf = ByteBuffer
                        .allocate(RecordCodec.encodedSize(record))
                        .order(ByteOrder.LITTLE_ENDIAN);
                RecordCodec.write(recordBuf, record);
                recordBuf.flip();
                writeFully(ch, recordBuf);
            }
            ch.force(true);
        } catch (SegmentWriteException e) {
            Files.deleteIfExists(tmp);
            throw e.getCause() instanceof IOException ioe ? ioe : new IOException(e.getCause());
        }

        Files.move(tmp, segmentFile(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        Manifest.fsyncDirectory(directory);
    }

    private static void writeFully(FileChannel ch, ByteBuffer buffer) throws IOException {
        while (buffer.hasRemaining()) {
            ch.write(buffer);
        }
    }

    static void putEnvelope(ByteBuffer buffer, Envelope e) {
        buffer.putDouble(e.getMinX());
        buffer.putDouble(e.getMinY());
        buffer.putDouble(e.getMinZ());
        buffer.putDouble(e.getMaxX());
        buffer.putDouble(e.getMaxY());
        buffer.putDouble(e.getMaxZ());
    }

    private static final class SegmentWriteException extends RuntimeException {
        SegmentWriteException(IOException cause) {
            super(cause);
        }
    }
}
