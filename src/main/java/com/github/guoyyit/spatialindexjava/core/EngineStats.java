package com.github.guoyyit.spatialindexjava.core;

/** Point-in-time engine statistics. */
public record EngineStats(
        long lastSeq,
        long lastFlushedSeq,
        int memtableSize,
        int segmentCount,
        long liveObjects,
        long totalPuts,
        long totalDeletes) {
}
