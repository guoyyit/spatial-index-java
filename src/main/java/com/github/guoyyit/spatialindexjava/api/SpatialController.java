package com.github.guoyyit.spatialindexjava.api;

import com.github.guoyyit.spatialindexjava.api.dto.BatchPutRequest;
import com.github.guoyyit.spatialindexjava.api.dto.KnnQueryRequest;
import com.github.guoyyit.spatialindexjava.api.dto.RangeQueryRequest;
import com.github.guoyyit.spatialindexjava.api.dto.ScoredObjectDto;
import com.github.guoyyit.spatialindexjava.api.dto.SpatialObjectDto;
import com.github.guoyyit.spatialindexjava.core.EngineStats;
import com.github.guoyyit.spatialindexjava.service.SpatialIndexService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Local REST API for the spatial index. Bound to loopback by default. */
@RestController
@RequestMapping("/api")
public class SpatialController {

    private final SpatialIndexService service;

    public SpatialController(SpatialIndexService service) {
        this.service = service;
    }

    @PutMapping("/objects")
    public ResponseEntity<Map<String, Object>> put(@RequestBody SpatialObjectDto dto) {
        return ResponseEntity.ok(Map.of("ok", true));
    }

    @PostMapping("/objects/batch")
    public ResponseEntity<Map<String, Object>> putBatch(@RequestBody BatchPutRequest request) {
        return ResponseEntity.ok(Map.of("ok", true, "count", 0));
    }

    @DeleteMapping("/objects/{id}")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable String id) {
        return ResponseEntity.ok(Map.of("ok", true, "existed", false));
    }

    @GetMapping("/objects/{id}")
    public ResponseEntity<SpatialObjectDto> get(@PathVariable String id) {
        return ResponseEntity.notFound().build();
    }

    @PostMapping("/query/range")
    public List<SpatialObjectDto> range(@RequestBody RangeQueryRequest request) {
        return new ArrayList<>();
    }

    @PostMapping("/query/knn")
    public List<ScoredObjectDto> knn(@RequestBody KnnQueryRequest request) {
        return new ArrayList<>();
    }

    @PostMapping("/admin/flush")
    public Map<String, Object> flush() {
        return Map.of("ok", true);
    }

    @PostMapping("/admin/compact")
    public Map<String, Object> compact() {
        return Map.of("ok", true);
    }

    @GetMapping("/stats")
    public EngineStats stats() {
        return service.stats();
    }
}
