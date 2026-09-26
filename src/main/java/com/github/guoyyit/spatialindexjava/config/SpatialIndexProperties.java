package com.github.guoyyit.spatialindexjava.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration of the spatial index, prefixed {@code spatial.index}.
 */
@Component
@ConfigurationProperties(prefix = "spatial.index")
public class SpatialIndexProperties {

    /** Local data directory; created on startup if missing. */
    private String dataDir = "data/spatial-index";
    /** A mem-table is flushed once it holds that many records. */
    private int memtableMaxRecords = 2000;
    /** Trigger a merge when at least that many segments are committed. */
    private int mergeThreshold = 4;
    /** Bulk-loaded R-tree node capacity. */
    private int rtreeNodeCapacity = 32;
    /** Fsync WAL and segment files on commit. Disable only for tests/benchmarks. */
    private boolean fsync = true;

    public String getDataDir() { return dataDir; }
    public void setDataDir(String dataDir) { this.dataDir = dataDir; }
    public int getMemtableMaxRecords() { return memtableMaxRecords; }
    public void setMemtableMaxRecords(int memtableMaxRecords) { this.memtableMaxRecords = memtableMaxRecords; }
    public int getMergeThreshold() { return mergeThreshold; }
    public void setMergeThreshold(int mergeThreshold) { this.mergeThreshold = mergeThreshold; }
    public int getRtreeNodeCapacity() { return rtreeNodeCapacity; }
    public void setRtreeNodeCapacity(int rtreeNodeCapacity) { this.rtreeNodeCapacity = rtreeNodeCapacity; }
    public boolean isFsync() { return fsync; }
    public void setFsync(boolean fsync) { this.fsync = fsync; }
}
