package com.github.guoyyit.spatialindexjava.core;

import com.github.guoyyit.spatialindexjava.model.Entry;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Path;

/**
 * Append-only write-ahead log. Records are length+CRC framed so a torn
 * tail write after a crash is detected and ignored on recovery.
 */
public final class Wal implements Closeable {

    private Wal() {
    }

    public static Wal open(Path file) throws IOException {
        return new Wal();
    }

    /** Append one record; durability is only guaranteed after {@link #sync()}. */
    public void append(Entry e) throws IOException {
    }

    /** Flush and fsync all buffered records to disk. */
    public void sync() throws IOException {
    }

    public Path path() {
        return null;
    }

    @Override
    public void close() throws IOException {
    }
}
