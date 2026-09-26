package com.github.guoyyit.spatialindexjava.core;

import com.github.guoyyit.spatialindexjava.model.Entry;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.zip.CRC32;

/**
 * Writes an immutable segment file. Layout:
 * <pre>[magic][version][count] count*[len][payload] [crc32]</pre>
 * Data is written to a tmp file, fsynced, then atomically renamed into
 * place, so a crash can leave at worst an orphan tmp file.
 */
public final class SegmentWriter {

    static final int MAGIC = 0x53504731; // "SPG1"
    static final int VERSION = 1;
    static final String SUFFIX = ".seg";
    static final String TMP_SUFFIX = ".tmp";

    private SegmentWriter() {
    }

    public static Path write(Path segmentDir, String name, List<Entry> entries) throws IOException {
        Files.createDirectories(segmentDir);
        Path tmp = segmentDir.resolve(name + TMP_SUFFIX);
        Path target = segmentDir.resolve(name + SUFFIX);
        CRC32 crc = new CRC32();
        try (RandomAccessFile raf = new RandomAccessFile(tmp.toFile(), "rw");
             FileChannel channel = raf.getChannel()) {
            raf.setLength(0);
            ByteBuffer header = ByteBuffer.allocate(12);
            header.putInt(MAGIC).putInt(VERSION).putInt(entries.size());
            writeAndCrc(channel, header, crc);
            for (Entry e : entries) {
                byte[] payload = WalCodec.encode(e);
                ByteBuffer frame = ByteBuffer.allocate(4 + payload.length);
                frame.putInt(payload.length).put(payload);
                writeAndCrc(channel, frame, crc);
            }
            ByteBuffer footer = ByteBuffer.allocate(4);
            footer.putInt((int) crc.getValue());
            footer.flip();
            while (footer.hasRemaining()) {
                channel.write(footer);
            }
            channel.force(true);
        }
        Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
        fsyncDir(segmentDir);
        return target;
    }

    private static void writeAndCrc(FileChannel channel, ByteBuffer buffer, CRC32 crc)
            throws IOException {
        buffer.flip();
        byte[] copy = new byte[buffer.remaining()];
        buffer.mark();
        buffer.get(copy);
        buffer.reset();
        crc.update(copy);
        while (buffer.hasRemaining()) {
            channel.write(buffer);
        }
    }

    static void fsyncDir(Path dir) throws IOException {
        try (FileChannel channel = FileChannel.open(dir)) {
            channel.force(true);
        } catch (IOException ignored) {
            // some filesystems do not support directory fsync; best effort
        }
    }

    /** Delete leftover tmp files from an interrupted write. */
    static void cleanTmpFiles(Path segmentDir) throws IOException {
        if (!Files.isDirectory(segmentDir)) {
            return;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(segmentDir, "*" + TMP_SUFFIX)) {
            for (Path tmp : stream) {
                Files.deleteIfExists(tmp);
            }
        }
    }
}
