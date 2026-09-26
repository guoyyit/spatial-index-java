package com.github.guoyyit.spatialindexjava.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Durable description of the last consistent state: which segments are
 * live and up to which sequence number the WAL has been flushed.
 * Updates go to a tmp file, are fsynced and atomically renamed, so a
 * crash always leaves either the old or the new manifest, never a
 * half-written one.
 */
public final class Manifest {

    static final String FILE_NAME = "MANIFEST";
    private static final String TMP_NAME = FILE_NAME + ".tmp";

    private final long lastFlushedSeq;
    private final long lastSeq;
    private final List<String> segments;

    public Manifest(long lastFlushedSeq, long lastSeq, List<String> segments) {
        this.lastFlushedSeq = lastFlushedSeq;
        this.lastSeq = lastSeq;
        this.segments = List.copyOf(segments);
    }

    public static Manifest empty() {
        return new Manifest(0, 0, List.of());
    }

    /** Highest WAL sequence included in the live segments. */
    public long lastFlushedSeq() {
        return lastFlushedSeq;
    }

    /** Highest sequence ever assigned (monotonic across restarts). */
    public long lastSeq() {
        return lastSeq;
    }

    /** Live segment names, oldest first. */
    public List<String> segments() {
        return segments;
    }

    public static Manifest load(Path dir) throws IOException {
        Path file = dir.resolve(FILE_NAME);
        Files.deleteIfExists(dir.resolve(TMP_NAME)); // leftover from crashed store
        if (!Files.exists(file)) {
            return empty();
        }
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        long lastFlushedSeq = 0;
        long lastSeq = 0;
        List<String> segments = new ArrayList<>();
        for (String line : lines) {
            if (line.isBlank()) {
                continue;
            }
            int eq = line.indexOf('=');
            String key = line.substring(0, eq);
            String value = line.substring(eq + 1);
            switch (key) {
                case "lastFlushedSeq" -> lastFlushedSeq = Long.parseLong(value);
                case "lastSeq" -> lastSeq = Long.parseLong(value);
                case "segments" -> {
                    if (!value.isEmpty()) {
                        segments.addAll(Arrays.asList(value.split(",")));
                    }
                }
                default -> throw new IOException("unknown manifest key: " + key);
            }
        }
        return new Manifest(lastFlushedSeq, lastSeq, segments);
    }

    public static void store(Path dir, Manifest manifest) throws IOException {
        Files.createDirectories(dir);
        StringBuilder sb = new StringBuilder();
        sb.append("lastFlushedSeq=").append(manifest.lastFlushedSeq).append('\n');
        sb.append("lastSeq=").append(manifest.lastSeq).append('\n');
        sb.append("segments=").append(String.join(",", manifest.segments)).append('\n');
        Path tmp = dir.resolve(TMP_NAME);
        Files.write(tmp, sb.toString().getBytes(StandardCharsets.UTF_8));
        try (var channel = java.nio.channels.FileChannel.open(tmp,
                java.nio.file.StandardOpenOption.WRITE)) {
            channel.force(true);
        }
        Files.move(tmp, dir.resolve(FILE_NAME), StandardCopyOption.ATOMIC_MOVE);
        SegmentWriter.fsyncDir(dir);
    }
}
