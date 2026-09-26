package com.github.guoyyit.spatialindexjava.rtree;

import com.github.guoyyit.spatialindexjava.core.Envelope;
import com.github.guoyyit.spatialindexjava.core.KnnCandidate;
import com.github.guoyyit.spatialindexjava.core.KnnSearchResult;
import com.github.guoyyit.spatialindexjava.core.SpatialObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.PriorityQueue;

/**
 * Generic static R-tree, bulk-loaded once with the Sort-Tile-Recursive
 * algorithm and queried read-only from many threads afterwards.
 *
 * <p>Entries are abstracted by {@link EntryView} so the same tree can serve
 * heap records and memory-mapped segment entries without materialising all
 * record payloads on the heap.
 *
 * @param <E> entry storage type
 */
public final class StaticRTree<E> {

    /** Leaf children encode entry index as -(idx+1); internal children are node indexes. */
    private static final class Node {
        final Envelope envelope;
        final int[] children;
        final boolean leaf;

        Node(Envelope envelope, int[] children, boolean leaf) {
            this.envelope = envelope;
            this.children = children;
            this.leaf = leaf;
        }
    }

    private final int nodeCapacity;
    private final EntryView<E> view;
    private final List<E> entries;
    private final List<Node> nodes;
    private final int root;

    public StaticRTree(int nodeCapacity, EntryView<E> view, List<E> input) {
        if (nodeCapacity < 2) {
            throw new IllegalArgumentException("nodeCapacity must be >= 2");
        }
        this.nodeCapacity = nodeCapacity;
        this.view = view;
        this.entries = new ArrayList<>(input);
        this.nodes = new ArrayList<>();
        int r = -1;
        if (!entries.isEmpty()) {
            build();
            r = nodes.size() - 1; // build() finishes with root last
        }
        this.root = r;
    }

    /**
     * Deserialization constructor: rebuilds a queryable tree directly from
     * serialized nodes without bulk loading.
     *
     * @param view          entry accessor
     * @param entries       entries in index order
     * @param nodeEnvelopes node envelopes in node index order
     * @param nodeChildren  per node: leaf flag packed as {@code children < 0}
     *                      is not used; instead child encoding follows the
     *                      on-disk convention via {@code nodeLeaf}
     * @param nodeLeaf      per node leaf flag
     * @param rootIndex     root node index
     */
    public StaticRTree(EntryView<E> view,
                       List<E> entries,
                       List<Envelope> nodeEnvelopes,
                       List<int[]> nodeChildren,
                       List<Boolean> nodeLeaf,
                       int rootIndex) {
        this.nodeCapacity = 0;
        this.view = view;
        this.entries = new ArrayList<>(entries);
        this.nodes = new ArrayList<>(nodeEnvelopes.size());
        for (int i = 0; i < nodeEnvelopes.size(); i++) {
            this.nodes.add(new Node(nodeEnvelopes.get(i),
                    nodeChildren.get(i).clone(), nodeLeaf.get(i)));
        }
        this.root = rootIndex;
    }

    public int size() {
        return entries.size();
    }

    /** Entry at a stable index (merge / lookup use). */
    public E entryAt(int index) {
        return entries.get(index);
    }

    public Envelope rootEnvelope() {
        return root < 0 ? null : nodes.get(root).envelope;
    }

    // ----------------------------------------------------------------
    // STR bulk loading
    // ----------------------------------------------------------------

    private void build() {
        int leafCount = (int) Math.ceil((double) entries.size() / nodeCapacity);
        int sliceCount = (int) Math.ceil(Math.sqrt(leafCount));
        List<Integer> current = buildLeaves(Math.max(1, sliceCount));
        while (current.size() > 1) {
            current = buildInternalLevel(current);
        }
        // The single remaining level is the root; it is the last node added.
    }

    private List<Integer> buildLeaves(int sliceCount) {
        List<Integer> sorted = new ArrayList<>(entries.size());
        for (int i = 0; i < entries.size(); i++) {
            sorted.add(i);
        }
        sorted.sort((a, b) -> Double.compare(centerX(a), centerX(b)));

        int sliceSize = (int) Math.ceil((double) sorted.size() / sliceCount);
        sliceSize = Math.max(sliceSize, nodeCapacity);

        List<Integer> leafNodes = new ArrayList<>();
        for (int s = 0; s < sorted.size(); s += sliceSize) {
            List<Integer> slice = new ArrayList<>(sorted.subList(s, Math.min(s + sliceSize, sorted.size())));
            slice.sort((a, b) -> {
                Envelope ea = view.envelope(entries.get(a));
                Envelope eb = view.envelope(entries.get(b));
                int c = Double.compare(ea.getMinY() + ea.getMaxY(), eb.getMinY() + eb.getMaxY());
                if (c != 0) {
                    return c;
                }
                c = Double.compare(ea.getMinZ() + ea.getMaxZ(), eb.getMinZ() + eb.getMaxZ());
                if (c != 0) {
                    return c;
                }
                return view.id(entries.get(a)).compareTo(view.id(entries.get(b)));
            });
            for (int start = 0; start < slice.size(); start += nodeCapacity) {
                int end = Math.min(start + nodeCapacity, slice.size());
                int[] children = new int[end - start];
                Envelope env = null;
                for (int j = start; j < end; j++) {
                    int entryIndex = slice.get(j);
                    children[j - start] = -(entryIndex + 1);
                    Envelope ce = to3D(view.envelope(entries.get(entryIndex)));
                    env = env == null ? ce : union3D(env, ce);
                }
                leafNodes.add(newNode(new Node(env, children, true)));
            }
        }
        return leafNodes;
    }

    private List<Integer> buildInternalLevel(List<Integer> current) {
        List<Integer> next = new ArrayList<>();
        for (int start = 0; start < current.size(); start += nodeCapacity) {
            int end = Math.min(start + nodeCapacity, current.size());
            int[] children = new int[end - start];
            Envelope env = null;
            for (int j = start; j < end; j++) {
                int nodeIndex = current.get(j);
                children[j - start] = nodeIndex;
                Envelope ce = nodes.get(nodeIndex).envelope;
                env = env == null ? ce : union3D(env, ce);
            }
            next.add(newNode(new Node(env, children, false)));
        }
        return next;
    }

    private int newNode(Node node) {
        nodes.add(node);
        return nodes.size() - 1;
    }

    private double centerX(int index) {
        Envelope e = view.envelope(entries.get(index));
        return (e.getMinX() + e.getMaxX()) / 2.0;
    }

    private static Envelope to3D(Envelope e) {
        return e.dimension() == 3 ? e
                : new Envelope(3, e.getMinX(), e.getMinY(), 0.0d,
                        e.getMaxX(), e.getMaxY(), 0.0d);
    }

    /** Union of two envelopes forced to 3D, so internal nodes never drop z. */
    private static Envelope union3D(Envelope a, Envelope b) {
        return new Envelope(3,
                Math.min(a.getMinX(), b.getMinX()),
                Math.min(a.getMinY(), b.getMinY()),
                Math.min(a.getMinZ(), b.getMinZ()),
                Math.max(a.getMaxX(), b.getMaxX()),
                Math.max(a.getMaxY(), b.getMaxY()),
                Math.max(a.getMaxZ(), b.getMaxZ()));
    }

    /** Visitor over internal tree nodes, used by segment serialization. */
    public interface NodeVisitor {
        void node(int index, boolean leaf, Envelope envelope, int[] childIndexes);
    }

    /** Number of internal nodes (diagnostics). */
    public int nodeCount() { return nodes.size(); }

    public boolean nodeIsLeaf(int index) { return nodes.get(index).leaf; }
    public int[] nodeChildren(int index) { return nodes.get(index).children; }
    public int rootIndex() { return root; }

    /** Iterates nodes in index order (root is the last index). */
    public void forEachNode(NodeVisitor visitor) {
        for (int i = 0; i < nodes.size(); i++) {
            Node n = nodes.get(i);
            visitor.node(i, n.leaf, n.envelope, n.children);
        }
    }

    // ----------------------------------------------------------------
    // Range query (inclusive intersection)
    // ----------------------------------------------------------------

    public List<E> range(Envelope query) {
        List<E> hits = new ArrayList<>();
        if (root < 0) {
            return hits;
        }
        int[] stack = new int[32];
        int sp = 0;
        stack[sp++] = root;
        while (sp > 0) {
            Node node = nodes.get(stack[--sp]);
            if (!query.intersects(node.envelope)) {
                continue;
            }
            if (node.leaf) {
                for (int child : node.children) {
                    E e = entries.get(-child - 1);
                    if (query.intersects(view.envelope(e))) {
                        hits.add(e);
                    }
                }
            } else {
                for (int child : node.children) {
                    if (sp == stack.length) {
                        stack = Arrays.copyOf(stack, stack.length * 2);
                    }
                    stack[sp++] = child;
                }
            }
        }
        return hits;
    }

    // ----------------------------------------------------------------
    // Branch-and-bound kNN
    // ----------------------------------------------------------------

    public KnnSearchResult knn(Envelope point, int bound) {
        List<KnnCandidate> out = new ArrayList<>();
        if (bound <= 0 || root < 0) {
            return new KnnSearchResult(out, true);
        }
        PriorityQueue<HeapEntry> frontier = new PriorityQueue<>();
        frontier.add(new HeapEntry(point.minDistance(nodes.get(root).envelope), root, true));

        // Max-heap by (distance, id).
        PriorityQueue<BestEntry> best = new PriorityQueue<>((a, b) -> {
            int c = Double.compare(b.distance, a.distance);
            return c != 0 ? c : b.id.compareTo(a.id);
        });

        boolean exhausted = true;

        while (!frontier.isEmpty()) {
            HeapEntry h = frontier.poll();
            double cutoff = best.size() >= bound ? best.peek().distance : Double.POSITIVE_INFINITY;
            if (h.distance > cutoff) {
                exhausted = false;
                continue;
            }
            if (!h.isNode) {
                E e = entries.get(h.ref);
                if (view.tombstone(e)) {
                    continue;
                }
                String id = view.id(e);
                double d = point.minDistance(view.envelope(e));
                if (best.size() >= bound) {
                    BestEntry worst = best.peek();
                    if (cmpCand(d, id, worst.distance, worst.id) >= 0) {
                        // Further away, or equal distance but loser on the id tie-break.
                        exhausted = false;
                        continue;
                    }
                    best.poll();
                }
                best.add(new BestEntry(h.ref, id, d));
            } else {
                Node n = nodes.get(h.ref);
                for (int child : n.children) {
                    if (child >= 0) {
                        double d = point.minDistance(nodes.get(child).envelope);
                        double cut = best.size() >= bound ? best.peek().distance : Double.POSITIVE_INFINITY;
                        if (d > cut) {
                            exhausted = false;
                            continue;
                        }
                        frontier.add(new HeapEntry(d, child, true));
                    } else {
                        int ei = -child - 1;
                        E e = entries.get(ei);
                        double d = point.minDistance(view.envelope(e));
                        frontier.add(new HeapEntry(d, ei, false));
                    }
                }
            }
        }

        List<BestEntry> sorted = new ArrayList<>(best);
        sorted.sort((a, b) -> cmpCand(a.distance, a.id, b.distance, b.id));
        for (BestEntry be : sorted) {
            out.add(new KnnCandidate(view.toObject(entries.get(be.entryIndex)), be.distance));
        }
        return new KnnSearchResult(out, exhausted);
    }

    private static int cmpCand(double da, String ida, double db, String idb) {
        int c = Double.compare(da, db);
        return c != 0 ? c : ida.compareTo(idb);
    }

    private record HeapEntry(double distance, int ref, boolean isNode) implements Comparable<HeapEntry> {
        @Override
        public int compareTo(HeapEntry o) {
            return Double.compare(distance, o.distance);
        }
    }

    private record BestEntry(int entryIndex, String id, double distance) {
    }
}
