package com.github.guoyyit.spatialindexjava.rtree;

import com.github.guoyyit.spatialindexjava.model.BoundingBox;
import com.github.guoyyit.spatialindexjava.model.Entry;
import com.github.guoyyit.spatialindexjava.model.RangeMode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Immutable R-tree bulk-loaded with the STR (sort-tile-recursive) method.
 * Used for on-disk segments. All queries are deterministic: range results
 * are unordered streams (callers sort), knn results are sorted by
 * (distance, id).
 */
public final class PackedRTree {

    /** Max entries per node; small fan-out keeps tree shallow and queries pruned. */
    static final int MAX_ENTRIES = 16;
    private static final int DIMS = 3;

    private final Node root;
    private final int size;

    private PackedRTree(Node root, int size) {
        this.root = root;
        this.size = size;
    }

    private static final class Node {
        final BoundingBox box;
        final List<Node> children;   // null for leaves
        final List<Entry> entries;   // null for internal nodes

        Node(BoundingBox box, List<Node> children, List<Entry> entries) {
            this.box = box;
            this.children = children;
            this.entries = entries;
        }

        boolean isLeaf() {
            return entries != null;
        }
    }

    /** Bulk-load a tree from the given entries (must all be puts). */
    public static PackedRTree build(List<Entry> entries) {
        if (entries.isEmpty()) {
            return new PackedRTree(null, 0);
        }
        List<Entry> copy = new ArrayList<>(entries);
        List<Node> level = new ArrayList<>();
        for (List<Entry> group : strTile(copy, e -> e.object().box())) {
            level.add(new Node(unionOf(group, e -> e.object().box()), null, List.copyOf(group)));
        }
        while (level.size() > 1) {
            List<Node> parents = new ArrayList<>();
            for (List<Node> group : strTile(level, n -> n.box)) {
                parents.add(new Node(unionOf(group, n -> n.box), List.copyOf(group), null));
            }
            level = parents;
        }
        return new PackedRTree(level.get(0), entries.size());
    }

    public int size() {
        return size;
    }

    /** Collect visible entries matching the query box into out. */
    public void range(BoundingBox query, RangeMode mode, Predicate<Entry> accept, List<Entry> out) {
        if (root != null) {
            rangeNode(root, query, mode, accept, out);
        }
    }

    private void rangeNode(Node node, BoundingBox query, RangeMode mode,
                           Predicate<Entry> accept, List<Entry> out) {
        if (!node.box.intersects(query)) {
            return;
        }
        if (node.isLeaf()) {
            for (Entry e : node.entries) {
                BoundingBox box = e.object().box();
                boolean match = mode == RangeMode.CONTAINS
                        ? query.contains(box)
                        : query.intersects(box);
                if (match && accept.test(e)) {
                    out.add(e);
                }
            }
            return;
        }
        for (Node child : node.children) {
            rangeNode(child, query, mode, accept, out);
        }
    }

    /**
     * Best-first k nearest neighbors to point. Only entries passing accept
     * are emitted. Result is sorted by (distance, id) so ties are
     * deterministic.
     */
    public List<Entry> knn(double[] point, int k, Predicate<Entry> accept) {
        if (root == null || k <= 0) {
            return List.of();
        }
        PriorityQueue<QueueItem> pq = new PriorityQueue<>();
        pq.add(new QueueItem(root.box.minDistSquared(point), root, null));
        List<Scored> accepted = new ArrayList<>();
        double kth = Double.POSITIVE_INFINITY;
        while (!pq.isEmpty()) {
            QueueItem item = pq.poll();
            if (item.distSq > kth) {
                break;
            }
            if (item.node != null) {
                if (item.node.isLeaf()) {
                    for (Entry e : item.node.entries) {
                        pq.add(new QueueItem(e.object().box().minDistSquared(point), null, e));
                    }
                } else {
                    for (Node child : item.node.children) {
                        pq.add(new QueueItem(child.box.minDistSquared(point), child, null));
                    }
                }
            } else if (accept.test(item.entry)) {
                accepted.add(new Scored(item.distSq, item.entry));
                if (accepted.size() >= k) {
                    accepted.sort(Scored.ORDER);
                    if (accepted.size() > k) {
                        accepted.subList(k, accepted.size()).clear();
                    }
                    kth = accepted.get(k - 1).distSq;
                }
            }
        }
        accepted.sort(Scored.ORDER);
        List<Entry> result = new ArrayList<>(Math.min(k, accepted.size()));
        for (int i = 0; i < accepted.size() && i < k; i++) {
            result.add(accepted.get(i).entry);
        }
        return result;
    }

    private record Scored(double distSq, Entry entry) {
        static final Comparator<Scored> ORDER = Comparator
                .comparingDouble(Scored::distSq)
                .thenComparing(s -> s.entry.id());
    }

    private record QueueItem(double distSq, Node node, Entry entry)
            implements Comparable<QueueItem> {
        @Override
        public int compareTo(QueueItem other) {
            return Double.compare(distSq, other.distSq);
        }
    }

    private static double center(BoundingBox box, int axis) {
        return (box.min(axis) + box.max(axis)) / 2.0;
    }

    private static <T> BoundingBox unionOf(List<T> items, Function<T, BoundingBox> boxOf) {
        BoundingBox union = boxOf.apply(items.get(0));
        for (int i = 1; i < items.size(); i++) {
            union = union.union(boxOf.apply(items.get(i)));
        }
        return union;
    }

    /** STR tiling: recursively sort by axis center and slice, chunking at the last axis. */
    private static <T> List<List<T>> strTile(List<T> items, Function<T, BoundingBox> boxOf) {
        int nodeCount = (items.size() + MAX_ENTRIES - 1) / MAX_ENTRIES;
        if (nodeCount <= 1) {
            return List.of(items);
        }
        int slices = (int) Math.ceil(Math.pow(nodeCount, 1.0 / DIMS));
        return tileAxis(items, boxOf, 0, slices);
    }

    private static <T> List<List<T>> tileAxis(List<T> items, Function<T, BoundingBox> boxOf,
                                              int axis, int slices) {
        List<T> sorted = new ArrayList<>(items);
        sorted.sort(Comparator.comparingDouble(t -> center(boxOf.apply(t), axis)));
        if (axis == DIMS - 1) {
            List<List<T>> groups = new ArrayList<>();
            for (int i = 0; i < sorted.size(); i += MAX_ENTRIES) {
                groups.add(sorted.subList(i, Math.min(sorted.size(), i + MAX_ENTRIES)));
            }
            return groups;
        }
        int sliceSize = (sorted.size() + slices - 1) / slices;
        List<List<T>> groups = new ArrayList<>();
        for (int i = 0; i < sorted.size(); i += sliceSize) {
            groups.addAll(tileAxis(sorted.subList(i, Math.min(sorted.size(), i + sliceSize)),
                    boxOf, axis + 1, slices));
        }
        return groups;
    }
}
