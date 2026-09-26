package com.github.guoyyit.spatialindexjava.lsm;

import com.github.guoyyit.spatialindexjava.core.IndexRecord;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;

/**
 * Write-ahead log of one active mem-table ({@code wal-N.log}).
 *
 * <p>Every appended record is one self-checking frame:
 * {@code int magic | int payloadLen | long crc | payload}. CRC32 covers the
 * payload bytes only. On recovery, reading stops at the first malformed or
 * torn frame, so a partial trailing append never corrupts the log.
 */
public final class Wal implements AutoCloseable {

    static final int MAGIC = 0x5741_4C31; // "WAL1"
    static final int HEADER_BYTES = 4 + 4 + 8;

    private final Path file;
    private final boolean fsync;
    private final FileChannel channel;

    public static Path fileFor(Path dir, long memtableId) {
        return dir.resolve("wal-" + memtableId + ".log");
    }

    public Wal(Path file, boolean fsync) throws IOException {
        this.file = file;
        this.fsync = fsync;
        this.channel = FileChannel.open(file,
                StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
        this.channel.position(this.channel.size());
    }

    /** Append one frame and make it durable (fsync when enabled). */
    public synchronized void append(IndexRecord record) throws IOException {
        byte[] payload = RecordCodec.encode(record);
        CRC32 crc = new CRC32();
        crc.update(payload);
        ByteBuffer header = ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.BIG_ENDIAN);
        header.putInt(MAGIC);
        header.putInt(payload.length);
        header.putLong(crc.getValue());
        header.flip();
        channel.write(new ByteBuffer[]{header, ByteBuffer.wrap(payload)});
        if (fsync) {
            channel.force(true);
        }
    }

    /** Read all intact frames; a torn/corrupt trailing frame terminates recovery. */
    public static List<IndexRecord> readAll(Path file) throws IOException {
        List<IndexRecord> records = new ArrayList<>();
        if (!Files.exists(file)) {
            return records;
        }
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ)) {
            long size = ch.size();
            long pos = 0;
            while (pos < size) {
                if (size - pos < HEADER_BYTES) {
                    break; // torn header
                }
                ByteBuffer header = ByteBuffer.allocate(HEADER_BYTES);
                readFully(ch, pos, header);
                header.flip();
                int magic = header.getInt();
                int payloadLen = header.getInt();
                long expectedCrc = header.getLong();
                if (magic != MAGIC || payloadLen < 0 || (long) payloadLen > size - pos - HEADER_BYTES) {
                    break;
                }
                ByteBuffer payload = ByteBuffer.allocate(payloadLen);
                readFully(ch, pos + HEADER_BYTES, payload);
                payload.flip();
                byte[] payloadBytes = new byte[payloadLen];
                payload.get(payloadBytes);
                CRC32 crc = new CRC32();
                crc.update(payloadBytes);
                if (crc.getValue() != expectedCrc) {
                    break; // torn or corrupted frame
                }
                records.add(RecordCodec.read(ByteBuffer.wrap(payloadBytes)));
                pos += (long) HEADER_BYTES + payloadLen;
            }
        }
        return records;
    }

    private static void readFully(FileChannel ch, long position, ByteBuffer buffer) throws IOException {
        while (buffer.hasRemaining()) {
            int read = ch.read(buffer, position + buffer.position());
            if (read == -1) {
                throw new IOException("unexpected end of file in " + ch);
            }
        }
    }

    @Override
    public synchronized void close() throws IOException {
        channel.close();
    }
}
