package com.github.guoyyit.spatialindexjava.core;

import com.github.guoyyit.spatialindexjava.model.BoundingBox;
import com.github.guoyyit.spatialindexjava.model.Entry;
import com.github.guoyyit.spatialindexjava.model.RangeMode;
import com.github.guoyyit.spatialindexjava.rtree.PackedRTree;

import java.io.Closeable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.zip.CRC32;

/**
 * An opened immutable segment: entries plus their STR-packed R-tree,
 * both fully resident in memory while the segment is open.
 */
public final class Segment implements Closeable {

    private final Path path;
    private final List<Entry> entries;
    private final PackedRTree tree;
    private final Map<String, Entry> byId;

    private Segment(Path path, List<Entry> entries) {
        this.path = path;
        this.entries = entries;
        this.tree = PackedRTree.build(entries.stream().filter(e -> !e.tombstone()).toList());
        this.byId = new HashMap<>();
        for (Entry e : entries) {
            byId.put(e.id(), e);
        }
    }

    public static Segment open(Path file) {
        try {
            return new Segment(file, readEntries(file));
        } catch (IOException e) {
            throw new UncheckedIOException("failed to open segment " + file, e);
        }
    }

    private static List<Entry> readEntries(Path file) throws IOException {
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            long fileSize = channel.size();
            if (fileSize < 16) {
                throw new IOException("segment too small: " + file);
            }
            ByteBuffer raw = ByteBuffer.allocate((int) fileSize);
            while (raw.hasRemaining()) {
                if (channel.read(raw) < 0) {
                    throw new IOException("unexpected EOF in " + file);
                }
            }
            raw.flip();
            CRC32 crc = new CRC32();
            byte[] all = new byte[raw.remaining()];
            raw.get(all);
            crc.update(all, 0, all.length - 4);
            ByteBuffer footer = ByteBuffer.wrap(all, all.length - 4, 4);
            if ((int) crc.getValue() != footer.getInt()) {
                throw new IOException("segment CRC mismatch: " + file);
            }
            ByteBuffer buffer = ByteBuffer.wrap(all);
            if (buffer.getInt() != SegmentWriter.MAGIC) {
                throw new IOException("bad magic in " + file);
            }
            if (buffer.getInt() != SegmentWriter.VERSION) {
                throw new IOException("bad version in " + file);
            }
            int count = buffer.getInt();
            List<Entry> entries = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                int len = buffer.getInt();
                byte[] payload = new byte[len];
                buffer.get(payload);
                entries.add(WalCodec.decode(payload));
            }
            return entries;
        }
    }

    public String name() {
        String fileName = path.getFileName().toString();
        return fileName.substring(0, fileName.length() - SegmentWriter.SUFFIX.length());
    }

    public Path path() {
        return path;
    }

    public int size() {
        return entries.size();
    }

    public void range(BoundingBox query, RangeMode mode, Predicate<Entry> accept, List<Entry> out) {
        tree.range(query, mode, accept, out);
    }

    public List<Entry> knn(double[] point, int k, Predicate<Entry> accept) {
        return tree.knn(point, k, accept);
    }

    /** Point lookup; returns the entry (possibly a tombstone) or null. */
    public Entry get(String id) {
        return byId.get(id);
    }

    /** All entries in storage order, tombstones included (used by merge). */
    public List<Entry> entries() {
        return entries;
    }

    @Override
    public void close() {
        // entries are memory-resident; nothing to release explicitly
    }
}
