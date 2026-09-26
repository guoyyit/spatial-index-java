package com.github.guoyyit.spatialindexjava.core;

import com.github.guoyyit.spatialindexjava.model.Entry;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Writes an immutable segment file: data goes to a tmp file, is fsynced,
 * then atomically renamed into place. A crash leaves at most an orphan
 * tmp file which recovery cleans up.
 */
public final class SegmentWriter {

    private SegmentWriter() {
    }

    public static Path write(Path segmentDir, String name, List<Entry> entries) throws IOException {
        return null;
    }
}
