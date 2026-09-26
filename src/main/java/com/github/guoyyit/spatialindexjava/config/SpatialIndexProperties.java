package com.github.guoyyit.spatialindexjava.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Configuration for the spatial index engine. */
@ConfigurationProperties(prefix = "spatial")
public class SpatialIndexProperties {

    /** Directory where WAL, segments and manifest are stored. */
    private String dataDir = "./data";

    /** Memtable entry count that triggers a background flush. */
    private int memtableMaxEntries = 10_000;

    /** Fsync the WAL before acknowledging a write (commit durability). */
    private boolean walSync = true;

    /** Merge is triggered when the live segment count reaches this. */
    private int mergeSegmentThreshold = 4;

    public String getDataDir() {
        return dataDir;
    }

    public void setDataDir(String dataDir) {
        this.dataDir = dataDir;
    }

    public int getMemtableMaxEntries() {
        return memtableMaxEntries;
    }

    public void setMemtableMaxEntries(int memtableMaxEntries) {
        this.memtableMaxEntries = memtableMaxEntries;
    }

    public boolean isWalSync() {
        return walSync;
    }

    public void setWalSync(boolean walSync) {
        this.walSync = walSync;
    }

    public int getMergeSegmentThreshold() {
        return mergeSegmentThreshold;
    }

    public void setMergeSegmentThreshold(int mergeSegmentThreshold) {
        this.mergeSegmentThreshold = mergeSegmentThreshold;
    }
}
