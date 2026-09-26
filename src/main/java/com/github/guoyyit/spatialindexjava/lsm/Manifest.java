package com.github.guoyyit.spatialindexjava.lsm;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Lists committed segment files in id order. Persistence is atomic:
 * {@code manifest.json.tmp} is written, fsynced and renamed over
 * {@code manifest.json}, after which the directory itself is fsynced.
 * A crash therefore always leaves either the old or the new manifest, never a
 * half-written one.
 */
public final class Manifest {

    public static final class SegmentMeta {
        private final long id;
        private final long maxSequence;
        private final int recordCount;
        private final long walId;

        @JsonCreator
        public SegmentMeta(@JsonProperty("id") long id,
                           @JsonProperty("maxSequence") long maxSequence,
                           @JsonProperty("recordCount") int recordCount,
                           @JsonProperty("walId") long walId) {
            this.id = id;
            this.maxSequence = maxSequence;
            this.recordCount = recordCount;
            this.walId = walId;
        }

        public long getId() { return id; }
        public long getMaxSequence() { return maxSequence; }
        public int getRecordCount() { return recordCount; }
        public long getWalId() { return walId; }
    }

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .setSerializationInclusion(JsonInclude.Include.NON_NULL);

    private final List<SegmentMeta> segments;

    @JsonCreator
    public Manifest(@JsonProperty("segments") List<SegmentMeta> segments) {
        this.segments = List.copyOf(segments);
    }

    public List<SegmentMeta> getSegments() { return segments; }

    public static Path manifestFile(Path dir) {
        return dir.resolve("manifest.json");
    }

    public static Path tempFile(Path dir) {
        return dir.resolve("manifest.json.tmp");
    }

    public static Manifest empty() {
        return new Manifest(new ArrayList<>());
    }

    public static Manifest load(Path dir) throws IOException {
        Path file = manifestFile(dir);
        if (!Files.exists(file)) {
            return empty();
        }
        return MAPPER.readValue(file.toFile(), Manifest.class);
    }

    public void store(Path dir) throws IOException {
        Path tmp = tempFile(dir);
        try (FileChannel ch = FileChannel.open(tmp,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            byte[] bytes = MAPPER.writerWithDefaultPrettyPrinter()
                    .writeValueAsBytes(this);
            ch.write(java.nio.ByteBuffer.wrap(bytes));
            ch.force(true);
        }
        Files.move(tmp, manifestFile(dir),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        fsyncDirectory(dir);
    }

    static void fsyncDirectory(Path dir) throws IOException {
        try (FileChannel ch = FileChannel.open(dir, StandardOpenOption.READ)) {
            ch.force(true);
        }
    }
}
