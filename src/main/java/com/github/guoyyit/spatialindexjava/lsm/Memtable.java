package com.github.guoyyit.spatialindexjava.lsm;

import com.github.guoyyit.spatialindexjava.core.Envelope;
import com.github.guoyyit.spatialindexjava.core.IndexRecord;
import com.github.guoyyit.spatialindexjava.core.KnnCandidate;
import com.github.guoyyit.spatialindexjava.core.KnnSearchResult;
import com.github.guoyyit.spatialindexjava.core.SpatialObject;
import com.github.guoyyit.spatialindexjava.core.SpatialSource;
import com.github.guoyyit.spatialindexjava.rtree.EntryView;
import com.github.guoyyit.spatialindexjava.rtree.StaticRTree;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Write buffer. While active it keeps the newest version of every id in a
 * linked map; after {@link #freeze()} versions are compacted and bulk-loaded
 * into an immutable R-tree and the instance becomes a read-only source.
 */
public final class Memtable implements SpatialSource {

    public static final EntryView<IndexRecord> RECORD_VIEW = new EntryView<>() {
        @Override public Envelope envelope(IndexRecord r) { return r.getEnvelope(); }
        @Override public String id(IndexRecord r) { return r.getId(); }
        @Override public boolean tombstone(IndexRecord r) { return r.isTombstone(); }
        @Override public SpatialObject toObject(IndexRecord r) { return r.toObject(); }
    };

    private final long id;
    private final int rtreeNodeCapacity;

    private final List<IndexRecord> raw = new ArrayList<>();
    private final LinkedHashMap<String, IndexRecord> latest = new LinkedHashMap<>();
    private boolean frozen;
    private StaticRTree<IndexRecord> tree;
    private long maxSequence = -1L;

    public Memtable(long id, int rtreeNodeCapacity) {
        this.id = id;
        this.rtreeNodeCapacity = rtreeNodeCapacity;
    }

    public long getId() { return id; }

    /** Number of committed write operations since creation. */
    public int appendCount() { return raw.size(); }

    public boolean isFrozen() { return frozen; }

    public void add(IndexRecord record) {
        if (frozen) {
            throw new IllegalStateException("memtable is frozen");
        }
        raw.add(record);
        latest.put(record.getId(), record);
        maxSequence = Math.max(maxSequence, record.getSequence());
    }

    /** Newest version per id (including tombstones). */
    public List<IndexRecord> compactedRecords() {
        return new ArrayList<>(latest.values());
    }

    public IndexRecord latestVersion(String id) {
        return latest.get(id);
    }

    public void freeze() {
        if (frozen) {
            return;
        }
        frozen = true;
        tree = new StaticRTree<>(rtreeNodeCapacity, RECORD_VIEW, new ArrayList<>(latest.values()));
    }

    @Override
    public long maxSequence() { return maxSequence; }

    @Override
    public List<IndexRecord> rangeQuery(Envelope query) {
        if (frozen) {
            return tree.range(query);
        }
        List<IndexRecord> hits = new ArrayList<>();
        for (Map.Entry<String, IndexRecord> e : latest.entrySet()) {
            if (e.getValue().getEnvelope().intersects(query)) {
                hits.add(e.getValue());
            }
        }
        return hits;
    }

    @Override
    public KnnSearchResult knnSearch(Envelope point, int bound) {
        if (frozen) {
            return tree.knn(point, bound);
        }
        List<KnnCandidate> all = new ArrayList<>();
        for (IndexRecord r : latest.values()) {
            if (!r.isTombstone()) {
                all.add(new KnnCandidate(r.toObject(), point.minDistance(r.getEnvelope())));
            }
        }
        all.sort((a, b) -> {
            int c = Double.compare(a.distance(), b.distance());
            return c != 0 ? c : a.id().compareTo(b.id());
        });
        int n = Math.min(bound, all.size());
        List<KnnCandidate> out = new ArrayList<>(all.subList(0, n));
        boolean exhausted = true;
        if (n < all.size()) {
            double cutoff = all.get(n - 1).distance();
            if (Double.compare(all.get(n).distance(), cutoff) <= 0) {
                exhausted = false;
            }
        }
        return new KnnSearchResult(out, exhausted);
    }

    @Override
    public void close() { }
}
