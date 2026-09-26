package com.github.guoyyit.spatialindexjava.web;

import com.github.guoyyit.spatialindexjava.lsm.SpatialIndexEngine;
import com.github.guoyyit.spatialindexjava.service.IndexService;
import com.github.guoyyit.spatialindexjava.web.dto.KnnQueryRequest;
import com.github.guoyyit.spatialindexjava.web.dto.ObjectDto;
import com.github.guoyyit.spatialindexjava.web.dto.ObjectResponse;
import com.github.guoyyit.spatialindexjava.web.dto.RangeQueryRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Local-only HTTP interface of the spatial index service.
 */
@RestController
@RequestMapping("/api/spatial")
public class IndexController {

    private final IndexService service;
    private final SpatialIndexEngine engine;

    public IndexController(IndexService service, SpatialIndexEngine engine) {
        this.service = service;
        this.engine = engine;
    }

    @PostMapping("/objects")
    public Map<String, Object> put(@RequestBody ObjectDto dto) throws Exception {
        service.put(dto);
        return Map.of("status", "committed", "id", dto.id);
    }

    @DeleteMapping("/objects/{id}")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable String id) throws Exception {
        boolean removed = service.delete(id);
        if (!removed) {
            return ResponseEntity.status(404).body(Map.of("status", "not_found", "id", id));
        }
        return ResponseEntity.ok(Map.of("status", "deleted", "id", id));
    }

    @GetMapping("/objects/{id}")
    public ResponseEntity<ObjectResponse> get(@PathVariable String id) {
        ObjectResponse response = service.get(id);
        return response == null
                ? ResponseEntity.notFound().build()
                : ResponseEntity.ok(response);
    }

    @PostMapping("/query/range")
    public List<ObjectResponse> range(@RequestBody RangeQueryRequest request) {
        return service.range(request);
    }

    @PostMapping("/query/knn")
    public List<ObjectResponse> knn(@RequestBody KnnQueryRequest request) {
        return service.knn(request);
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of("status", "UP", "segments", engine.segmentIds());
    }
}
