package com.github.guoyyit.spatialindexjava.api;

import com.github.guoyyit.spatialindexjava.api.dto.BatchPutRequest;
import com.github.guoyyit.spatialindexjava.api.dto.KnnQueryRequest;
import com.github.guoyyit.spatialindexjava.api.dto.RangeQueryRequest;
import com.github.guoyyit.spatialindexjava.api.dto.ScoredObjectDto;
import com.github.guoyyit.spatialindexjava.api.dto.SpatialObjectDto;
import com.github.guoyyit.spatialindexjava.core.EngineStats;
import com.github.guoyyit.spatialindexjava.model.BoundingBox;
import com.github.guoyyit.spatialindexjava.model.RangeMode;
import com.github.guoyyit.spatialindexjava.model.SpatialObject;
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

import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * Local REST API for the spatial index. The server binds to loopback by
 * default (see application.properties); all data stays on the local disk.
 */
@RestController
@RequestMapping("/api")
public class SpatialController {

    private final SpatialIndexService service;

    public SpatialController(SpatialIndexService service) {
        this.service = service;
    }

    @PutMapping("/objects")
    public Map<String, Object> put(@RequestBody SpatialObjectDto dto) {
        service.put(toModel(dto));
        return Map.of("ok", true);
    }

    @PostMapping("/objects/batch")
    public Map<String, Object> putBatch(@RequestBody BatchPutRequest request) {
        List<SpatialObject> objects = request.objects() == null
                ? List.of()
                : request.objects().stream().map(SpatialController::toModel).toList();
        service.putAll(objects);
        return Map.of("ok", true, "count", objects.size());
    }

    @DeleteMapping("/objects/{id}")
    public Map<String, Object> delete(@PathVariable String id) {
        return Map.of("ok", true, "existed", service.delete(id));
    }

    @GetMapping("/objects/{id}")
    public ResponseEntity<SpatialObjectDto> get(@PathVariable String id) {
        return service.get(id)
                .map(o -> ResponseEntity.ok(toDto(o)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/query/range")
    public List<SpatialObjectDto> range(@RequestBody RangeQueryRequest request) {
        BoundingBox query = new BoundingBox(toArray(request.min()), toArray(request.max()));
        RangeMode mode = request.mode() == null
                ? RangeMode.CONTAINS
                : RangeMode.valueOf(request.mode().toUpperCase());
        int limit = request.limit() == null ? 0 : request.limit();
        return service.range(query, mode, limit).stream().map(SpatialController::toDto).toList();
    }

    @PostMapping("/query/knn")
    public List<ScoredObjectDto> knn(@RequestBody KnnQueryRequest request) {
        return service.knn(toArray(request.point()), request.k()).stream()
                .map(s -> new ScoredObjectDto(toDto(s.object()), s.distance()))
                .toList();
    }

    @PostMapping("/admin/flush")
    public Map<String, Object> flush() {
        service.flush();
        return Map.of("ok", true);
    }

    @PostMapping("/admin/compact")
    public Map<String, Object> compact() {
        service.compact();
        return Map.of("ok", true);
    }

    @GetMapping("/stats")
    public EngineStats stats() {
        return service.stats();
    }

    // ------------------------------------------------------------ mapping

    private static SpatialObject toModel(SpatialObjectDto dto) {
        if (dto.id() == null || dto.id().isEmpty()) {
            throw new IllegalArgumentException("id must not be empty");
        }
        byte[] payload = dto.payload() == null
                ? null
                : Base64.getDecoder().decode(dto.payload());
        return new SpatialObject(dto.id(),
                new BoundingBox(toArray(dto.min()), toArray(dto.max())), payload);
    }

    private static SpatialObjectDto toDto(SpatialObject obj) {
        String payload = obj.payload() == null
                ? null
                : Base64.getEncoder().encodeToString(obj.payload());
        return new SpatialObjectDto(obj.id(),
                toList(obj.box().min()), toList(obj.box().max()), payload);
    }

    private static double[] toArray(List<Double> values) {
        if (values == null || (values.size() != 2 && values.size() != 3)) {
            throw new IllegalArgumentException("coordinates must have 2 or 3 elements");
        }
        double[] array = new double[values.size()];
        for (int i = 0; i < array.length; i++) {
            array[i] = values.get(i);
        }
        return array;
    }

    private static List<Double> toList(double[] values) {
        return java.util.Arrays.stream(values).boxed().toList();
    }
}
