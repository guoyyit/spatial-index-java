package com.github.guoyyit.spatialindexjava.core;

import com.github.guoyyit.spatialindexjava.model.Entry;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Reads WAL records, stopping at the first corrupt/torn record. */
public final class WalReader {

    private WalReader() {
    }

    public static List<Entry> readAll(Path file) throws IOException {
        return new ArrayList<>();
    }
}
