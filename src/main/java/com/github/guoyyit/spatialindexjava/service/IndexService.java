package com.github.guoyyit.spatialindexjava.service;

import com.github.guoyyit.spatialindexjava.core.Envelope;
import com.github.guoyyit.spatialindexjava.core.KnnCandidate;
import com.github.guoyyit.spatialindexjava.core.SpatialObject;
import com.github.guoyyit.spatialindexjava.lsm.SpatialIndexEngine;
import com.github.guoyyit.spatialindexjava.web.dto.KnnQueryRequest;
import com.github.guoyyit.spatialindexjava.web.dto.ObjectDto;
import com.github.guoyyit.spatialindexjava.web.dto.ObjectResponse;
import com.github.guoyyit.spatialindexjava.web.dto.RangeQueryRequest;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.List;

/**
 * Application service boundary around {@link SpatialIndexEngine}.
 */
@Service
public class IndexService {

    private final SpatialIndexEngine engine;

    public IndexService(SpatialIndexEngine engine) {
        this.engine = engine;
    }

    public void put(ObjectDto dto) throws IOException {
        engine.put(DtoMapper.toObject(dto));
    }

    public boolean delete(String id) throws IOException {
        return engine.delete(id);
    }

    public ObjectResponse get(String id) {
        SpatialObject object = engine.get(id);
        return object == null ? null : DtoMapper.toResponse(object);
    }

    public List<ObjectResponse> range(RangeQueryRequest request) {
        Envelope query = DtoMapper.toRangeEnvelope(request);
        return engine.rangeQuery(query).stream()
                .map(DtoMapper::toResponse)
                .toList();
    }

    public List<ObjectResponse> knn(KnnQueryRequest request) {
        Envelope point = DtoMapper.toPointEnvelope(request);
        int k = DtoMapper.toK(request);
        return engine.knnQuery(point, k).stream()
                .map(DtoMapper::toResponse)
                .toList();
    }
}
