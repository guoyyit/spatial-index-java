package com.github.guoyyit.spatialindexjava.core;

import java.util.List;

/**
 * Outcome of one bounded kNN search over an immutable source.
 *
 * @param candidates candidates up to the requested bound, sorted by (distance, id)
 * @param exhausted  true when no subtree/entry was pruned for distance reasons,
 *                   i.e. every entry with distance &le; the returned bound was visited
 */
public record KnnSearchResult(List<KnnCandidate> candidates, boolean exhausted) {
}
