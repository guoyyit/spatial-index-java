package com.github.guoyyit.spatialindexjava.service;

import com.github.guoyyit.spatialindexjava.config.SpatialIndexProperties;
import com.github.guoyyit.spatialindexjava.core.EngineStats;
import com.github.guoyyit.spatialindexjava.core.SpatialIndexEngine;
import com.github.guoyyit.spatialindexjava.model.BoundingBox;
import com.github.guoyyit.spatialindexjava.model.RangeMode;
import com.github.guoyyit.spatialindexjava.model.ScoredObject;
import com.github.guoyyit.spatialindexjava.model.SpatialObject;
import jakarta.annotation.PreDestroy;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Optional;

/** Spring-managed lifecycle wrapper around the index engine. */
@Service
@EnableConfigurationProperties(SpatialIndexProperties.class)
public class SpatialIndexService {

    private final SpatialIndexEngine engine;

    public SpatialIndexService(SpatialIndexProperties props) {
        try {
            this.engine = SpatialIndexEngine.open(props);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public void put(SpatialObject obj) {
        try {
            engine.put(obj);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public void putAll(List<SpatialObject> objs) {
        try {
            engine.putAll(objs);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public boolean delete(String id) {
        try {
            return engine.delete(id);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public Optional<SpatialObject> get(String id) {
        return engine.get(id);
    }

    public List<SpatialObject> range(BoundingBox query, RangeMode mode, int limit) {
        return engine.range(query, mode, limit);
    }

    public List<ScoredObject> knn(double[] point, int k) {
        return engine.knn(point, k);
    }

    public void flush() {
        try {
            engine.flush();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public void compact() {
        try {
            engine.compact();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public EngineStats stats() {
        return engine.stats();
    }

    @PreDestroy
    public void shutdown() throws IOException {
        engine.close();
    }
}
