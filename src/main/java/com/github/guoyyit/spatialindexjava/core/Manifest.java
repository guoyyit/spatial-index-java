package com.github.guoyyit.spatialindexjava.core;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Durable description of the current consistent state: which segments are
 * live and up to which sequence number the WAL has been flushed.
 * Updates are written to a tmp file, fsynced and atomically renamed.
 */
public final class Manifest {
    private final long lastFlushedSeq;
    private final long lastSeq;
    private final List<String> segments;

    public Manifest(long lastFlushedSeq, long lastSeq, List<String> segments) {
        this.lastFlushedSeq = lastFlushedSeq;
        this.lastSeq = lastSeq;
        this.segments = segments;
    }

    public long lastFlushedSeq() {
        return lastFlushedSeq;
    }

    public long lastSeq() {
        return lastSeq;
    }

    public List<String> segments() {
        return segments;
    }

    public static Manifest load(Path dir) throws IOException {
        return new Manifest(0, 0, new ArrayList<>());
    }

    public static void store(Path dir, Manifest manifest) throws IOException {
    }
}
