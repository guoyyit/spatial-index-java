package com.github.guoyyit.spatialindexjava.service;

import com.github.guoyyit.spatialindexjava.core.Envelope;
import com.github.guoyyit.spatialindexjava.core.KnnCandidate;
import com.github.guoyyit.spatialindexjava.core.ShapeType;
import com.github.guoyyit.spatialindexjava.core.SpatialObject;
import com.github.guoyyit.spatialindexjava.web.dto.KnnQueryRequest;
import com.github.guoyyit.spatialindexjava.web.dto.ObjectDto;
import com.github.guoyyit.spatialindexjava.web.dto.ObjectResponse;
import com.github.guoyyit.spatialindexjava.web.dto.RangeQueryRequest;

/**
 * Converts between HTTP DTOs and core model types with strict validation.
 */
public final class DtoMapper {

    private DtoMapper() { }

    public static SpatialObject toObject(ObjectDto dto) {
        if (dto == null) {
            throw new IllegalArgumentException("request body is required");
        }
        if (dto.id == null || dto.id.isBlank()) {
            throw new IllegalArgumentException("id is required");
        }
        ShapeType shape = parseShape(dto.shape);
        int dim = dto.dim == null ? 2 : dto.dim;
        require(dto.minX, "minX");
        require(dto.minY, "minY");
        Envelope envelope;
        if (shape == ShapeType.POINT) {
            if (dim == 3) {
                require(dto.minZ, "minZ");
                envelope = Envelope.point3D(dto.minX, dto.minY, dto.minZ);
            } else {
                envelope = Envelope.point2D(dto.minX, dto.minY);
            }
        } else {
            require(dto.maxX, "maxX");
            require(dto.maxY, "maxY");
            if (dim == 3) {
                require(dto.minZ, "minZ");
                require(dto.maxZ, "maxZ");
                envelope = Envelope.rect3D(dto.minX, dto.minY, dto.minZ,
                        dto.maxX, dto.maxY, dto.maxZ);
            } else {
                envelope = Envelope.rect2D(dto.minX, dto.minY, dto.maxX, dto.maxY);
            }
        }
        return new SpatialObject(dto.id, shape, envelope, emptyToNull(dto.payload));
    }

    public static Envelope toRangeEnvelope(RangeQueryRequest req) {
        if (req == null) {
            throw new IllegalArgumentException("request body is required");
        }
        int dim = req.dim == null ? 2 : req.dim;
        require(req.minX, "minX");
        require(req.minY, "minY");
        require(req.maxX, "maxX");
        require(req.maxY, "maxY");
        if (dim == 3) {
            require(req.minZ, "minZ");
            require(req.maxZ, "maxZ");
            return Envelope.rect3D(req.minX, req.minY, req.minZ,
                    req.maxX, req.maxY, req.maxZ);
        }
        return Envelope.rect2D(req.minX, req.minY, req.maxX, req.maxY);
    }

    public static Envelope toPointEnvelope(KnnQueryRequest req) {
        if (req == null) {
            throw new IllegalArgumentException("request body is required");
        }
        int dim = req.dim == null ? 2 : req.dim;
        require(req.x, "x");
        require(req.y, "y");
        if (dim == 3) {
            require(req.z, "z");
            return Envelope.point3D(req.x, req.y, req.z);
        }
        return Envelope.point2D(req.x, req.y);
    }

    public static int toK(KnnQueryRequest req) {
        if (req == null || req.k == null) {
            throw new IllegalArgumentException("k is required");
        }
        if (req.k <= 0) {
            throw new IllegalArgumentException("k must be positive");
        }
        return req.k;
    }

    public static ObjectResponse toResponse(SpatialObject o) {
        Envelope e = o.getEnvelope();
        return new ObjectResponse(
                o.getId(),
                o.getShapeType().name(),
                e.dimension(),
                e.getMinX(), e.getMinY(), e.getMinZ(),
                e.getMaxX(), e.getMaxY(), e.getMaxZ(),
                o.getPayload(), null);
    }

    public static ObjectResponse toResponse(KnnCandidate c) {
        ObjectResponse base = toResponse(c.object());
        return new ObjectResponse(
                base.id(), base.shape(), base.dim(),
                base.minX(), base.minY(), base.minZ(),
                base.maxX(), base.maxY(), base.maxZ(),
                base.payload(), c.distance());
    }

    private static ShapeType parseShape(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("shape is required (POINT or RECTANGLE)");
        }
        try {
            return ShapeType.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("shape must be POINT or RECTANGLE, got " + raw);
        }
    }

    private static void require(Double value, String field) {
        if (value == null) {
            throw new IllegalArgumentException(field + " is required");
        }
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            throw new IllegalArgumentException(field + " must be finite");
        }
    }

    private static String emptyToNull(String s) {
        return s == null || s.isEmpty() ? null : s;
    }
}
