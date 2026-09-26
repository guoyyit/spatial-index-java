package com.github.guoyyit.spatialindexjava.lsm;

import com.github.guoyyit.spatialindexjava.config.SpatialIndexProperties;
import com.github.guoyyit.spatialindexjava.core.Envelope;
import com.github.guoyyit.spatialindexjava.core.IndexRecord;
import com.github.guoyyit.spatialindexjava.core.KnnCandidate;
import com.github.guoyyit.spatialindexjava.core.KnnSearchResult;
import com.github.guoyyit.spatialindexjava.core.ShapeType;
import com.github.guoyyit.spatialindexjava.core.SpatialObject;
import com.github.guoyyit.spatialindexjava.core.SpatialSource;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * LSM-style persistent spatial index engine.
 *
 * <h3>Visibility</h3>
 * Writes are serialized by a write lock and appended to the active
 * mem-table's WAL before they become visible. Readers acquire the read lock
 * only long enough to copy an immutable list of sources, then query without
 * holding any lock. Sources are ordered newest-first; per-id dedup keeps the
 * first (highest sequence) version, so every operation sees an atomic
 * snapshot.
 *
 * <h3>Durability &amp; recovery</h3>
 * Each committed write is fsynced in the WAL (configurable). Flush writes a
 * segment atomically (temp + rename + dir fsync), publishes it in an
 * atomically-replaced manifest, then deletes the WAL. On startup, committed
 * segments are loaded and WAL frames above the highest committed sequence are
 * replayed into a fresh active mem-table. A torn trailing WAL frame or a
 * leftover temp file is discarded.
 *
 * <h3>Background merge</h3>
 * When the segment count reaches the configured threshold a full merge runs
 * on a single background thread. It snapshots the committed segment list
 * under the read lock (writes/reads continue against other sources), writes
 * one new segment, then briefly takes the write lock to swap the manifest and
 * readers. Old segment files are deleted after the swap.
 */
@Component
public class SpatialIndexEngine {

    private static final Logger log = LoggerFactory.getLogger(SpatialIndexEngine.class);

    private final SpatialIndexProperties props;
    private final ReentrantReadWriteLock rwLock = new ReentrantReadWriteLock(true);
    private final AtomicLong sequence = new AtomicLong(0);
    private final AtomicLong memtableIdGen = new AtomicLong(1);
    private final AtomicLong segmentIdGen = new AtomicLong(1);

    private final ExecutorService flushExecutor =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "spatial-index-flush");
                t.setDaemon(true);
                return t;
            });
    private final ExecutorService mergeExecutor =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "spatial-index-merge");
                t.setDaemon(true);
                return t;
            });

    private final Object flushGate = new Object();
    private int pendingFlushCount = 0;
    private volatile Future<?> mergeFuture = CompletableFuture.completedFuture(null);

    private Path dataDir;
    private Wal activeWal;
    private Memtable active;

    // Newest first ordering by mem-table id / segment id.
    private final TreeSet<FrozenMemtable> frozen =
            new TreeSet<>(Comparator.comparingLong((FrozenMemtable f) -> f.memtable.getId()).reversed());
    private final TreeSet<LoadedSegment> segments =
            new TreeSet<>(Comparator.comparingLong((LoadedSegment s) -> s.meta.getId()).reversed());

    public SpatialIndexEngine(SpatialIndexProperties props) {
        this.props = props;
    }

    static final class FrozenMemtable {
        final Memtable memtable;
        final long walId;

        FrozenMemtable(Memtable memtable, long walId) {
            this.memtable = memtable;
            this.walId = walId;
        }
    }

    static final class LoadedSegment {
        final Manifest.SegmentMeta meta;
        final SegmentReader reader;

        LoadedSegment(Manifest.SegmentMeta meta, SegmentReader reader) {
            this.meta = meta;
            this.reader = reader;
        }
    }

    @PostConstruct
    public void start() throws IOException {
        dataDir = Path.of(props.getDataDir());
        Files.createDirectories(dataDir);
        recover();
    }

    @PreDestroy
    public void stop() {
        try {
            flushExecutor.shutdown();
            mergeExecutor.shutdown();
            rwLock.writeLock().lock();
            try {
                if (activeWal != null) {
                    activeWal.close();
                }
                for (LoadedSegment s : segments) {
                    s.reader.close();
                }
            } finally {
                rwLock.writeLock().unlock();
            }
        } catch (Exception e) {
            log.warn("error while closing spatial index", e);
        }
    }

    // ----------------------------------------------------------------
    // Recovery
    // ----------------------------------------------------------------

    private void recover() throws IOException {
        Manifest manifest = Manifest.load(dataDir);
        java.util.Set<Long> referenced = new java.util.HashSet<>();
        long maxCommittedSeq = -1L;
        for (Manifest.SegmentMeta meta : manifest.getSegments()) {
            SegmentReader reader = new SegmentReader(segmentPath(meta.getId()), meta.getId());
            segments.add(new LoadedSegment(meta, reader));
            referenced.add(meta.getId());
            maxCommittedSeq = Math.max(maxCommittedSeq, meta.getMaxSequence());
            segmentIdGen.set(Math.max(segmentIdGen.get(), meta.getId() + 1));
        }
        removeOrphanSegments(referenced);

        List<Path> walFiles = new ArrayList<>();
        try (var stream = Files.list(dataDir)) {
            stream.filter(p -> {
                String n = p.getFileName().toString();
                return n.startsWith("wal-") && n.endsWith(".log");
            }).forEach(walFiles::add);
        }
        walFiles.sort(Comparator.comparingLong(SpatialIndexEngine::walIdFromFile));

        long maxReplayedSeq = -1L;
        Memtable replayed = null;
        for (Path walFile : walFiles) {
            long walId = walIdFromFile(walFile);
            memtableIdGen.set(Math.max(memtableIdGen.get(), walId + 1));
            List<IndexRecord> records = Wal.readAll(walFile);
            if (records.isEmpty()) {
                continue;
            }
            if (replayed == null) {
                replayed = new Memtable(walId, props.getRtreeNodeCapacity());
            }
            for (IndexRecord record : records) {
                if (record.getSequence() <= maxCommittedSeq) {
                    continue;
                }
                replayed.add(record);
                maxReplayedSeq = Math.max(maxReplayedSeq, record.getSequence());
            }
        }
        sequence.set(Math.max(maxCommittedSeq, maxReplayedSeq));

        long newActiveId = memtableIdGen.getAndIncrement();
        if (replayed != null && replayed.appendCount() > 0) {
            // Preserve durability of replayed records under the new active WAL.
            replayed.freeze();
            Wal wal = new Wal(Wal.fileFor(dataDir, newActiveId), props.isFsync());
            for (IndexRecord record : replayed.compactedRecords()) {
                wal.append(record);
            }
            this.active = asUnfrozenCopy(replayed, newActiveId);
            this.activeWal = wal;
        } else {
            this.active = new Memtable(newActiveId, props.getRtreeNodeCapacity());
            this.activeWal = new Wal(Wal.fileFor(dataDir, newActiveId), props.isFsync());
        }
        // WALs fully covered by segments (and the replay source) are obsolete.
        for (Path walFile : walFiles) {
            Files.deleteIfExists(walFile);
        }
        removeStrayTempFiles();
        log.info("spatial index recovered at {} (segments={}, seq={})",
                dataDir, segments.size(), sequence.get());
    }

    private Memtable asUnfrozenCopy(Memtable frozenMemtable, long newId) {
        Memtable copy = new Memtable(newId, props.getRtreeNodeCapacity());
        for (IndexRecord r : frozenMemtable.compactedRecords()) {
            copy.add(r);
        }
        return copy;
    }

    // ----------------------------------------------------------------
    // Writes
    // ----------------------------------------------------------------

    public void put(SpatialObject object) throws IOException {
        validate(object);
        long seq = sequence.incrementAndGet();
        IndexRecord record = IndexRecord.put(seq, object);
        FrozenMemtable rotated;
        rwLock.writeLock().lock();
        try {
            activeWal.append(record);
            active.add(record);
            rotated = active.appendCount() >= props.getMemtableMaxRecords()
                    ? rotateActiveLocked() : null;
        } finally {
            rwLock.writeLock().unlock();
        }
        if (rotated != null) {
            scheduleFlush(rotated);
        }
    }

    public boolean delete(String id) throws IOException {
        if (id == null || id.isEmpty()) {
            throw new IllegalArgumentException("id must not be empty");
        }
        long seq = sequence.incrementAndGet();
        FrozenMemtable rotated;
        boolean removed;
        rwLock.writeLock().lock();
        try {
            IndexRecord latest = findLatestIndexRecordLocked(id);
            if (latest == null || latest.isTombstone()) {
                sequence.decrementAndGet();
                return false;
            }
            IndexRecord tombstone = IndexRecord.tombstone(
                    seq, id, latest.getShapeType(), latest.getEnvelope());
            activeWal.append(tombstone);
            active.add(tombstone);
            removed = true;
            rotated = active.appendCount() >= props.getMemtableMaxRecords()
                    ? rotateActiveLocked() : null;
        } finally {
            rwLock.writeLock().unlock();
        }
        if (rotated != null) {
            scheduleFlush(rotated);
        }
        return removed;
    }

    public SpatialObject get(String id) {
        rwLock.readLock().lock();
        try {
            for (SpatialSource source : sourcesLocked()) {
                IndexRecord r = lookup(source, id);
                if (r != null) {
                    return r.isTombstone() ? null : r.toObject();
                }
            }
            return null;
        } finally {
            rwLock.readLock().unlock();
        }
    }

    // ----------------------------------------------------------------
    // Queries
    // ----------------------------------------------------------------

    public List<SpatialObject> rangeQuery(Envelope query) {
        rwLock.readLock().lock();
        try {
            LinkedHashMap<String, IndexRecord> winner = new LinkedHashMap<>();
            for (SpatialSource source : sourcesLocked()) {
                for (IndexRecord r : source.rangeQuery(query)) {
                    winner.putIfAbsent(r.getId(), r);
                }
            }
            List<SpatialObject> out = new ArrayList<>();
            for (IndexRecord r : winner.values()) {
                if (!r.isTombstone()) {
                    out.add(r.toObject());
                }
            }
            out.sort(Comparator.comparing(SpatialObject::getId));
            return out;
        } finally {
            rwLock.readLock().unlock();
        }
    }

    public List<KnnCandidate> knnQuery(Envelope point, int k) {
        if (k <= 0) {
            return new ArrayList<>();
        }
        rwLock.readLock().lock();
        try {
            List<SpatialSource> sources = sourcesLocked();
            int bound = k;
            Map<String, IndexRecord> winner;
            List<KnnCandidate> finalists;
            double cutoffDistance;
            while (true) {
                winner = new HashMap<>();
                boolean allExhausted = true;
                double perSourceCutoff = Double.POSITIVE_INFINITY;
                for (SpatialSource source : sources) {
                    KnnSearchResult result = source.knnSearch(point, bound);
                    List<KnnCandidate> cands = result.candidates();
                    if (!result.exhausted()) {
                        allExhausted = false;
                    }
                    if (!cands.isEmpty()) {
                        perSourceCutoff = Math.min(perSourceCutoff,
                                cands.get(cands.size() - 1).distance());
                    }
                    for (KnnCandidate c : cands) {
                        IndexRecord asRecord = recordOf(source, c);
                        winner.merge(asRecord.getId(), asRecord,
                                (a, b) -> a.getSequence() > b.getSequence() ? a : b);
                    }
                }
                finalists = new ArrayList<>();
                for (IndexRecord r : winner.values()) {
                    if (!r.isTombstone()) {
                        finalists.add(new KnnCandidate(r.toObject(),
                                point.minDistance(r.getEnvelope())));
                    }
                }
                finalists.sort(Comparator.comparingDouble(KnnCandidate::distance)
                        .thenComparing(c -> c.object().getId()));
            if (finalists.size() > k) {
                finalists = new ArrayList<>(finalists.subList(0, k));
            }
            cutoffDistance = finalists.size() == k
                    ? finalists.get(k - 1).distance() : Double.POSITIVE_INFINITY;
            // Stop when every source visited all potentially relevant entries, or
            // no source could still hold an entry at/inside the global cutoff
            // (strictly greater => equal-distance tie entries are never missed).
            boolean cutoffReached = finalists.size() == k
                    && Double.compare(perSourceCutoff, cutoffDistance) > 0;
            if (allExhausted || cutoffReached) {
                return finalists;
            }
            bound = Math.max(bound * 2, k * 2);
            }
        } finally {
            rwLock.readLock().unlock();
        }
    }

    public List<Long> segmentIds() {
        rwLock.readLock().lock();
        try {
            List<Long> ids = new ArrayList<>();
            for (LoadedSegment s : segments) {
                ids.add(s.meta.getId());
            }
            return ids;
        } finally {
            rwLock.readLock().unlock();
        }
    }

    public void waitForMerge() throws InterruptedException {
        try {
            mergeFuture.get();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public void forceFlushAndMerge() throws IOException, InterruptedException {
        FrozenMemtable rotated;
        rwLock.writeLock().lock();
        try {
            rotated = active.appendCount() > 0 ? rotateActiveLocked() : null;
        } finally {
            rwLock.writeLock().unlock();
        }
        if (rotated != null) {
            scheduleFlush(rotated);
        }
        awaitFlushes();
        scheduleMergeIfNeeded(true);
        waitForMerge();
    }

    // ----------------------------------------------------------------
    // Flush / merge internals
    // ----------------------------------------------------------------

    /** Builds the newest-first source list; caller MUST hold the read or write lock. */
    private List<SpatialSource> sourcesLocked() {
        List<SpatialSource> list = new ArrayList<>(1 + frozen.size() + segments.size());
        list.add(active);
        for (FrozenMemtable f : frozen) {
            list.add(f.memtable);
        }
        for (LoadedSegment s : segments) {
            list.add(s.reader);
        }
        return list;
    }

    private IndexRecord lookup(SpatialSource source, String id) {
        if (source instanceof Memtable m) {
            return m.latestVersion(id);
        }
        if (source instanceof SegmentReader sr) {
            return sr.lookupById(id);
        }
        return null;
    }

    private IndexRecord recordOf(SpatialSource source, KnnCandidate candidate) {
        // Candidate objects carry payload but not sequence; fetch the source record.
        String id = candidate.id();
        IndexRecord r = lookup(source, id);
        if (r == null) {
            throw new IllegalStateException("knn candidate missing in its source: " + id);
        }
        return r;
    }

    private IndexRecord findLatestIndexRecordLocked(String id) {
        IndexRecord r = active.latestVersion(id);
        if (r != null) {
            return r;
        }
        for (FrozenMemtable f : frozen) {
            r = f.memtable.latestVersion(id);
            if (r != null) {
                return r;
            }
        }
        for (LoadedSegment s : segments) {
            r = s.reader.lookupById(id);
            if (r != null) {
                return r;
            }
        }
        return null;
    }

    /** Move active mem-table to frozen and create a new active. Caller schedules the flush. */
    private FrozenMemtable rotateActiveLocked() throws IOException {
        active.freeze();
        long oldId = active.getId();
        FrozenMemtable fm = new FrozenMemtable(active, oldId);
        frozen.add(fm);
        activeWal.close();

        long newId = memtableIdGen.getAndIncrement();
        active = new Memtable(newId, props.getRtreeNodeCapacity());
        activeWal = new Wal(Wal.fileFor(dataDir, newId), props.isFsync());
        return fm;
    }

    private void scheduleFlush(FrozenMemtable fm) {
        // Backpressure is applied outside the write lock so the flush thread
        // can always acquire the lock to publish a finished segment.
        synchronized (flushGate) {
            while (pendingFlushCount >= 2) {
                try {
                    flushGate.wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(e);
                }
            }
            pendingFlushCount++;
        }
        flushExecutor.submit(() -> flushMemtable(fm));
    }

    private void flushMemtable(FrozenMemtable fm) {
        try {
            List<IndexRecord> records = fm.memtable.compactedRecords();
            long segId = segmentIdGen.getAndIncrement();
            SegmentWriter writer = new SegmentWriter(dataDir, segId, props.getRtreeNodeCapacity());
            writer.write(records);
            long maxSeq = fm.memtable.maxSequence();
            Manifest.SegmentMeta meta = new Manifest.SegmentMeta(
                    segId, maxSeq, records.size(), fm.walId);

            SegmentReader reader;
            rwLock.writeLock().lock();
            try {
                Manifest current = Manifest.load(dataDir);
                List<Manifest.SegmentMeta> all = new ArrayList<>(current.getSegments());
                all.add(meta);
                all.sort(Comparator.comparingLong(Manifest.SegmentMeta::getId));
                new Manifest(all).store(dataDir);

                reader = new SegmentReader(segmentPath(segId), segId);
                segments.add(new LoadedSegment(meta, reader));
                frozen.remove(fm);
            } finally {
                rwLock.writeLock().unlock();
            }
            Files.deleteIfExists(Wal.fileFor(dataDir, fm.walId));
            Manifest.fsyncDirectory(dataDir);
            log.debug("flushed mem-table {} to segment {}", fm.walId, segId);
        } catch (Exception e) {
            log.error("flush of mem-table {} failed; records remain in WAL and will replay",
                    fm.walId, e);
        } finally {
            synchronized (flushGate) {
                pendingFlushCount--;
                flushGate.notifyAll();
            }
            // Trigger a merge if thresholds are crossed, after visibility.
            scheduleMergeIfNeeded(false);
        }
    }

    private void awaitFlushes() throws InterruptedException, IOException {
        synchronized (flushGate) {
            while (pendingFlushCount > 0) {
                flushGate.wait();
            }
        }
    }

    private void scheduleMergeIfNeeded(boolean force) {
        rwLock.readLock().lock();
        int count;
        try {
            count = segments.size();
        } finally {
            rwLock.readLock().unlock();
        }
        if (!force && count < props.getMergeThreshold()) {
            return;
        }
        if (count < 2 && !force) {
            return;
        }
        synchronized (mergeExecutor) {
            if (!mergeFuture.isDone()) {
                return;
            }
            mergeFuture = mergeExecutor.submit(this::runMerge);
        }
    }

    private void runMerge() {
        try {
            List<LoadedSegment> snapshot;
            long newId;
            rwLock.readLock().lock();
            try {
                snapshot = new ArrayList<>(segments);
                if (snapshot.size() < 2) {
                    return;
                }
                newId = segmentIdGen.getAndIncrement();
            } finally {
                rwLock.readLock().unlock();
            }
            // Snapshot is newest-first; merge by keeping the first (newest)
            // version of every id.
            LinkedHashMap<String, IndexRecord> merged = new LinkedHashMap<>();
            for (LoadedSegment ls : snapshot) {
                for (IndexRecord r : ls.reader.allRecords()) {
                    merged.putIfAbsent(r.getId(), r);
                }
            }
            List<IndexRecord> mergedRecords = new ArrayList<>(merged.values());
            // This merge covers the whole data set, so tombstones are obsolete.
            mergedRecords.removeIf(IndexRecord::isTombstone);
            if (mergedRecords.isEmpty()) {
                return;
            }
            long maxSeq = mergedRecords.stream()
                    .mapToLong(IndexRecord::getSequence).max().orElse(-1L);

            new SegmentWriter(dataDir, newId, props.getRtreeNodeCapacity()).write(mergedRecords);
            Manifest.SegmentMeta newMeta = new Manifest.SegmentMeta(
                    newId, maxSeq, mergedRecords.size(), -1L);

            List<Long> replacedIds = new ArrayList<>();
            for (LoadedSegment ls : snapshot) {
                replacedIds.add(ls.meta.getId());
            }

            rwLock.writeLock().lock();
            try {
                // Re-verify under lock: another merge may have replaced some of these.
                List<Manifest.SegmentMeta> currentMetas = Manifest.load(dataDir).getSegments();
                Map<Long, Manifest.SegmentMeta> byId = new HashMap<>();
                for (Manifest.SegmentMeta m : currentMetas) {
                    byId.put(m.getId(), m);
                }
                boolean stillValid = replacedIds.stream().allMatch(byId::containsKey);
                if (!stillValid) {
                    Files.deleteIfExists(segmentPath(newId));
                    return;
                }
                List<Manifest.SegmentMeta> kept = new ArrayList<>();
                for (Manifest.SegmentMeta m : currentMetas) {
                    if (!replacedIds.contains(m.getId())) {
                        kept.add(m);
                    }
                }
                kept.add(newMeta);
                kept.sort(Comparator.comparingLong(Manifest.SegmentMeta::getId));
                new Manifest(kept).store(dataDir);

                for (LoadedSegment ls : snapshot) {
                    segments.remove(ls);
                    ls.reader.close();
                }
                segments.add(new LoadedSegment(newMeta,
                        new SegmentReader(segmentPath(newId), newId)));
            } finally {
                rwLock.writeLock().unlock();
            }
            for (long oldId : replacedIds) {
                Files.deleteIfExists(segmentPath(oldId));
            }
            Manifest.fsyncDirectory(dataDir);
            log.info("merged {} segments into segment {}", replacedIds.size(), newId);
        } catch (Exception e) {
            log.error("background merge failed", e);
        }
    }

    // ----------------------------------------------------------------
    // helpers
    // ----------------------------------------------------------------

    private void validate(SpatialObject object) {
        if (object == null || object.getId() == null || object.getId().isEmpty()) {
            throw new IllegalArgumentException("object id must not be empty");
        }
    }

    private Path segmentPath(long id) {
        return dataDir.resolve("seg-" + id + ".seg");
    }

    static long walIdFromFile(Path path) {
        String name = path.getFileName().toString();
        return Long.parseLong(name.substring(4, name.length() - 4));
    }

    private void removeOrphanSegments(java.util.Set<Long> referenced) throws IOException {
        try (var stream = Files.list(dataDir)) {
            stream.filter(p -> {
                String n = p.getFileName().toString();
                return n.startsWith("seg-") && n.endsWith(".seg");
            }).forEach(p -> {
                try {
                    String n = p.getFileName().toString();
                    long id = Long.parseLong(n.substring(4, n.length() - 4));
                    if (!referenced.contains(id)) {
                        Files.deleteIfExists(p);
                    }
                } catch (IOException | NumberFormatException e) {
                    log.warn("cannot inspect candidate segment {}", p, e);
                }
            });
        }
    }

    private void removeStrayTempFiles() throws IOException {
        try (var stream = Files.list(dataDir)) {
            stream.filter(p -> p.getFileName().toString().endsWith(".tmp")).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    log.warn("cannot remove stray temp file {}", p, e);
                }
            });
        }
    }
}
