package com.github.guoyyit.spatialindexjava.core;

import com.github.guoyyit.spatialindexjava.model.Entry;

import java.io.Closeable;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.util.zip.CRC32;

/**
 * Append-only write-ahead log. Records are length+CRC framed so a torn
 * tail write after a crash is detected and ignored on recovery.
 * {@link #sync()} fsyncs; a write is committed only after sync returns.
 */
public final class Wal implements Closeable {

    private final Path path;
    private final RandomAccessFile raf;
    private final FileChannel channel;
    private final CRC32 crc = new CRC32();
    private boolean closed;

    private Wal(Path path, RandomAccessFile raf) {
        this.path = path;
        this.raf = raf;
        this.channel = raf.getChannel();
    }

    /** Open (creating if needed) a WAL positioned at the end for appending. */
    public static Wal open(Path file) throws IOException {
        RandomAccessFile raf = new RandomAccessFile(file.toFile(), "rw");
        raf.seek(raf.length());
        return new Wal(file, raf);
    }

    /** Append one record; durable only after {@link #sync()} returns. */
    public synchronized void append(Entry e) throws IOException {
        ensureOpen();
        byte[] payload = WalCodec.encode(e);
        crc.reset();
        crc.update(payload);
        ByteBuffer frame = ByteBuffer.allocate(8 + payload.length);
        frame.putInt(payload.length);
        frame.putInt((int) crc.getValue());
        frame.put(payload);
        frame.flip();
        while (frame.hasRemaining()) {
            channel.write(frame);
        }
    }

    /** Fsync all appended records. */
    public synchronized void sync() throws IOException {
        ensureOpen();
        channel.force(true);
    }

    public Path path() {
        return path;
    }

    private void ensureOpen() throws IOException {
        if (closed) {
            throw new IOException("WAL closed");
        }
    }

    @Override
    public synchronized void close() throws IOException {
        if (!closed) {
            closed = true;
            channel.force(true);
            raf.close();
        }
    }
}
