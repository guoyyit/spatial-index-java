package com.github.guoyyit.spatialindexjava.core;

import com.github.guoyyit.spatialindexjava.config.SpatialIndexProperties;
import com.github.guoyyit.spatialindexjava.model.BoundingBox;
import com.github.guoyyit.spatialindexjava.model.Entry;
import com.github.guoyyit.spatialindexjava.model.RangeMode;
import com.github.guoyyit.spatialindexjava.model.ScoredObject;
import com.github.guoyyit.spatialindexjava.model.SpatialObject;

import java.io.Closeable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * LSM-style spatial index engine.
 *
 * <p>Write path: entries are appended to the WAL and fsynced (the commit
 * point, when {@code spatial.wal-sync=true}), then applied to the
 * memtable. Readers hold the read lock and therefore only ever see
 * fully committed writes.
 *
 * <p>Flush: when the memtable exceeds its limit it is frozen and written
 * to an immutable segment in the background; queries keep reading the
 * frozen copy until the segment is atomically installed.
 *
 * <p>Merge: all segments are merged in the background into one, dropping
 * overwritten versions and tombstones. Readers use the old segments
 * until the manifest swap; results are identical before and after.
 *
 * <p>Recovery: the manifest names the last consistent segment set; WAL
 * records newer than the flushed sequence are replayed; orphan tmp/seg
 * files from interrupted flush/merge are removed.
 */
public final class SpatialIndexEngine implements Closeable {

    private final SpatialIndexProperties props;
    private final Path dataDir;
    private final Path walDir;
    private final Path segmentDir;

    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    /** id -> latest seq; negative means the latest version is a tombstone. */
    private final ConcurrentHashMap<String, Long> versions = new ConcurrentHashMap<>();

    private final AtomicLong lastSeq = new AtomicLong();
    private volatile long lastFlushedSeq;

    private Memtable memtable = new Memtable();
    /** Frozen memtables being flushed; still part of the read view. */
    private final List<Memtable> flushing = new ArrayList<>();
    /** Live segments, oldest first. */
    private final List<Segment> segments = new ArrayList<>();

    private Wal wal;
    private int walCounter;
    private int segmentCounter;
    /** Rotated WAL files pending deletion once the flush covering them is durable. */
    private final List<PendingWal> pendingWals = new ArrayList<>();

    private final ExecutorService maintenance =
            Executors.newSingleThreadExecutor(r -> new Thread(r, "spatial-maintenance"));
    private final AtomicBoolean flushScheduled = new AtomicBoolean();

    private volatile boolean closed;
    private long totalPuts;
    private long totalDeletes;

    private record PendingWal(Path path, long maxSeq) {
    }

    private SpatialIndexEngine(SpatialIndexProperties props) {
        this.props = props;
        this.dataDir = Path.of(props.getDataDir());
        this.walDir = dataDir.resolve("wal");
        this.segmentDir = dataDir.resolve("segments");
    }

    public static SpatialIndexEngine open(SpatialIndexProperties props) throws IOException {
        SpatialIndexEngine engine = new SpatialIndexEngine(props);
        engine.recover();
        return engine;
    }

    // ---------------------------------------------------------------- recovery

    private void recover() throws IOException {
        Files.createDirectories(walDir);
        Files.createDirectories(segmentDir);
        SegmentWriter.cleanTmpFiles(segmentDir);

        Manifest manifest = Manifest.load(dataDir);
        lastFlushedSeq = manifest.lastFlushedSeq();

        // Open live segments; remove orphans from interrupted flush/merge.
        Map<String, Segment> live = new HashMap<>();
        for (String name : manifest.segments()) {
            Segment segment = Segment.open(segmentDir.resolve(name + SegmentWriter.SUFFIX));
            live.put(name, segment);
            segments.add(segment);
        }
        try (DirectoryStream<Path> stream =
                     Files.newDirectoryStream(segmentDir, "*" + SegmentWriter.SUFFIX)) {
            for (Path file : stream) {
                String name = file.getFileName().toString();
                name = name.substring(0, name.length() - SegmentWriter.SUFFIX.length());
                if (!live.containsKey(name)) {
                    Files.deleteIfExists(file);
                }
                segmentCounter = Math.max(segmentCounter, numericSuffix(name) + 1);
            }
        }
        // Rebuild the version map from segments (oldest first, newest wins).
        for (Segment segment : segments) {
            for (Entry e : segment.entries()) {
                applyToVersions(e);
            }
        }

        // Replay every WAL file in name order; entries already covered by
        // a flushed segment are skipped.
        long maxSeq = manifest.lastSeq();
        List<Path> walFiles = sortedWalFiles();
        for (Path file : walFiles) {
            walCounter = Math.max(walCounter, numericSuffix(stripExt(file)) + 1);
            long fileMaxSeq = -1;
            for (Entry e : WalReader.readAll(file)) {
                fileMaxSeq = Math.max(fileMaxSeq, e.seq());
                maxSeq = Math.max(maxSeq, e.seq());
                if (e.seq() > lastFlushedSeq) {
                    memtable.apply(e);
                    applyToVersions(e);
                }
            }
            if (fileMaxSeq >= 0) {
                pendingWals.add(new PendingWal(file, fileMaxSeq));
            }
        }
        lastSeq.set(maxSeq);
        deleteCoveredWals();
        wal = Wal.open(walDir.resolve(walName(++walCounter)));
    }

    private void applyToVersions(Entry e) {
        versions.merge(e.id(), e.tombstone() ? -e.seq() : e.seq(),
                (old, neu) -> Math.abs(neu) >= Math.abs(old) ? neu : old);
    }

    // ---------------------------------------------------------------- writes

    /** Insert or replace the object with the same id. */
    public void put(SpatialObject obj) throws IOException {
        putAll(List.of(obj));
    }

    /** Atomic batch insert/update: readers see all of it or none of it. */
    public void putAll(List<SpatialObject> objs) throws IOException {
        if (objs.isEmpty()) {
            return;
        }
        lock.writeLock().lock();
        try {
            ensureOpen();
            List<Entry> entries = new ArrayList<>(objs.size());
            for (SpatialObject obj : objs) {
                entries.add(Entry.put(lastSeq.incrementAndGet(), obj));
            }
            for (Entry e : entries) {
                wal.append(e);
            }
            if (props.isWalSync()) {
                wal.sync(); // commit point
            }
            for (Entry e : entries) {
                memtable.apply(e);
                applyToVersions(e);
            }
            totalPuts += objs.size();
            maybeScheduleFlush();
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** @return true if the id existed (as a live object) and was deleted. */
    public boolean delete(String id) throws IOException {
        lock.writeLock().lock();
        try {
            ensureOpen();
            long current = versions.getOrDefault(id, 0L);
            if (current <= 0) {
                return false; // unknown or already deleted: deterministic no-op
            }
            Entry tombstone = Entry.delete(lastSeq.incrementAndGet(), id);
            wal.append(tombstone);
            if (props.isWalSync()) {
                wal.sync(); // commit point
            }
            memtable.apply(tombstone);
            applyToVersions(tombstone);
            totalDeletes++;
            maybeScheduleFlush();
            return true;
        } finally {
            lock.writeLock().unlock();
        }
    }

    // ---------------------------------------------------------------- reads

    public Optional<SpatialObject> get(String id) {
        lock.readLock().lock();
        try {
            long version = versions.getOrDefault(id, 0L);
            if (version <= 0) {
                return Optional.empty();
            }
            Entry e = findEntry(id, version);
            return e == null ? Optional.empty() : Optional.of(e.object());
        } finally {
            lock.readLock().unlock();
        }
    }

    private Entry findEntry(String id, long seq) {
        Entry e = memtable.get(id);
        if (e != null && e.seq() == seq) {
            return e;
        }
        for (Memtable m : flushing) {
            e = m.get(id);
            if (e != null && e.seq() == seq) {
                return e;
            }
        }
        for (int i = segments.size() - 1; i >= 0; i--) {
            e = segments.get(i).get(id);
            if (e != null && e.seq() == seq) {
                return e;
            }
        }
        return null;
    }

    private boolean isVisible(Entry e) {
        return versions.getOrDefault(e.id(), 0L) == e.seq();
    }

    /** Range query; result sorted by id so repeated runs are identical. */
    public List<SpatialObject> range(BoundingBox query, RangeMode mode, int limit) {
        lock.readLock().lock();
        try {
            List<Entry> matched = new ArrayList<>();
            memtable.range(query, mode, this::isVisible, matched);
            for (Memtable m : flushing) {
                m.range(query, mode, this::isVisible, matched);
            }
            for (Segment s : segments) {
                s.range(query, mode, this::isVisible, matched);
            }
            matched.sort(Comparator.comparing(Entry::id));
            List<SpatialObject> result = new ArrayList<>();
            for (Entry e : matched) {
                if (limit > 0 && result.size() >= limit) {
                    break;
                }
                result.add(e.object());
            }
            return result;
        } finally {
            lock.readLock().unlock();
        }
    }

    /** k nearest neighbors; distance ties broken by id, so order is stable. */
    public List<ScoredObject> knn(double[] point, int k) {
        if (k <= 0) {
            return List.of();
        }
        lock.readLock().lock();
        try {
            List<Entry> merged = new ArrayList<>();
            merged.addAll(memtable.knn(point, k, this::isVisible));
            for (Memtable m : flushing) {
                merged.addAll(m.knn(point, k, this::isVisible));
            }
            for (Segment s : segments) {
                merged.addAll(s.knn(point, k, this::isVisible));
            }
            merged.sort(Comparator
                    .comparingDouble((Entry e) -> e.object().box().minDistSquared(point))
                    .thenComparing(Entry::id));
            List<ScoredObject> result = new ArrayList<>(Math.min(k, merged.size()));
            for (int i = 0; i < merged.size() && i < k; i++) {
                Entry e = merged.get(i);
                result.add(new ScoredObject(e.object(),
                        Math.sqrt(e.object().box().minDistSquared(point))));
            }
            return result;
        } finally {
            lock.readLock().unlock();
        }
    }

    // ---------------------------------------------------------------- flush

    private void maybeScheduleFlush() {
        if (closed) {
            return;
        }
        if (memtable.size() >= props.getMemtableMaxEntries()
                && flushScheduled.compareAndSet(false, true)) {
            maintenance.execute(() -> doFlush(false));
        }
    }

    /** Force a memtable flush and wait for it. */
    public void flush() throws IOException {
        Future<?> future = maintenance.submit(() -> doFlush(true));
        waitFor(future);
    }

    private void doFlush(boolean force) {
        flushScheduled.set(false);
        Memtable frozen;
        Wal rotated;
        try {
            lock.writeLock().lock();
            try {
                if (memtable.size() == 0 || (!force && memtable.size() < props.getMemtableMaxEntries())) {
                    return;
                }
                frozen = memtable;
                memtable = new Memtable();
                flushing.add(frozen);
                rotated = wal;
                pendingWals.add(new PendingWal(rotated.path(), lastSeq.get()));
                rotated.close();
                wal = Wal.open(walDir.resolve(walName(++walCounter)));
            } finally {
                lock.writeLock().unlock();
            }
            List<Entry> entries = frozen.entries().stream()
                    .sorted(Comparator.comparing(Entry::id))
                    .toList();
            long frozenMaxSeq = entries.stream().mapToLong(Entry::seq).max().orElse(lastFlushedSeq);
            String name = segmentName(++segmentCounter);
            Path path = SegmentWriter.write(segmentDir, name, entries);
            Segment segment = Segment.open(path);

            lock.writeLock().lock();
            try {
                segments.add(segment);
                flushing.remove(frozen);
                lastFlushedSeq = Math.max(lastFlushedSeq, frozenMaxSeq);
            } finally {
                lock.writeLock().unlock();
            }
            storeManifest();
            deleteCoveredWals();
            maybeScheduleMerge();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ---------------------------------------------------------------- merge

    private void maybeScheduleMerge() {
        if (closed) {
            return;
        }
        lock.readLock().lock();
        try {
            if (segments.size() >= props.getMergeSegmentThreshold()) {
                maintenance.execute(() -> doMerge(false));
            }
        } finally {
            lock.readLock().unlock();
        }
    }

    /** Force a background merge of all segments and wait for it. */
    public void compact() throws IOException {
        flush();
        Future<?> future = maintenance.submit(() -> doMerge(true));
        waitFor(future);
    }

    private void doMerge(boolean force) {
        List<Segment> toMerge;
        try {
            lock.writeLock().lock();
            try {
                if (segments.size() < 2 && !(force && !segments.isEmpty())) {
                    return;
                }
                toMerge = List.copyOf(segments);
            } finally {
                lock.writeLock().unlock();
            }
            // Newest version per id wins; tombstones are dropped because a
            // full merge covers every older source they could shadow.
            Map<String, Entry> newest = new HashMap<>();
            for (Segment segment : toMerge) {
                for (Entry e : segment.entries()) {
                    newest.merge(e.id(), e, (a, b) -> a.seq() >= b.seq() ? a : b);
                }
            }
            List<Entry> droppedTombstones = newest.values().stream()
                    .filter(Entry::tombstone).toList();
            List<Entry> merged = newest.values().stream()
                    .filter(e -> !e.tombstone())
                    .sorted(Comparator.comparing(Entry::id))
                    .toList();

            Segment newSegment = null;
            if (!merged.isEmpty()) {
                String name = segmentName(++segmentCounter);
                newSegment = Segment.open(SegmentWriter.write(segmentDir, name, merged));
            }

            lock.writeLock().lock();
            try {
                segments.removeAll(toMerge);
                if (newSegment != null) {
                    segments.add(newSegment);
                }
                // GC version entries whose tombstone just became obsolete.
                for (Entry tomb : droppedTombstones) {
                    versions.computeIfPresent(tomb.id(),
                            (id, v) -> v == -tomb.seq() ? null : v);
                }
            } finally {
                lock.writeLock().unlock();
            }
            storeManifest();
            for (Segment old : toMerge) {
                old.close();
                Files.deleteIfExists(old.path());
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ---------------------------------------------------------------- misc

    private void storeManifest() throws IOException {
        List<String> names;
        lock.readLock().lock();
        try {
            names = segments.stream().map(Segment::name).toList();
        } finally {
            lock.readLock().unlock();
        }
        Manifest.store(dataDir, new Manifest(lastFlushedSeq, lastSeq.get(), names));
    }

    private void deleteCoveredWals() {
        var it = pendingWals.iterator();
        while (it.hasNext()) {
            PendingWal pending = it.next();
            if (pending.maxSeq() <= lastFlushedSeq) {
                try {
                    Files.deleteIfExists(pending.path());
                } catch (IOException ignored) {
                    // deletion is best-effort cleanup; replay skips old entries
                }
                it.remove();
            }
        }
    }

    public EngineStats stats() {
        lock.readLock().lock();
        try {
            long live = versions.values().stream().filter(v -> v > 0).count();
            return new EngineStats(lastSeq.get(), lastFlushedSeq, memtable.size(),
                    segments.size(), live, totalPuts, totalDeletes);
        } finally {
            lock.readLock().unlock();
        }
    }

    private void ensureOpen() throws IOException {
        if (closed) {
            throw new IOException("engine is closed");
        }
    }

    private void waitFor(Future<?> future) throws IOException {
        try {
            future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", e);
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof UncheckedIOException uio) {
                throw uio.getCause();
            }
            throw new IOException(cause);
        }
    }

    private List<Path> sortedWalFiles() throws IOException {
        List<Path> files = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(walDir, "*.log")) {
            for (Path file : stream) {
                files.add(file);
            }
        }
        files.sort(Comparator.comparing(p -> p.getFileName().toString()));
        return files;
    }

    private static String stripExt(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? name : name.substring(0, dot);
    }

    private static int numericSuffix(String name) {
        int dash = name.lastIndexOf('-');
        if (dash < 0) {
            return 0;
        }
        try {
            return Integer.parseInt(name.substring(dash + 1));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String walName(int n) {
        return String.format("wal-%06d.log", n);
    }

    private static String segmentName(int n) {
        return String.format("seg-%06d", n);
    }

    @Override
    public void close() throws IOException {
        lock.writeLock().lock();
        try {
            if (closed) {
                return;
            }
            closed = true;
        } finally {
            lock.writeLock().unlock();
        }
        maintenance.shutdown();
        try {
            if (!maintenance.awaitTermination(1, TimeUnit.MINUTES)) {
                maintenance.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            maintenance.shutdownNow();
        }
        // Final flush so a clean restart needs no WAL replay.
        if (memtable.size() > 0) {
            doFlush(true);
        }
        wal.close();
    }
}
