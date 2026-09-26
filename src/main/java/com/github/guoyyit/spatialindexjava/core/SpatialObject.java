package com.github.guoyyit.spatialindexjava.core;

import java.util.Objects;

/**
 * A user-visible spatial object: unique id, geometry and optional string payload.
 */
public final class SpatialObject {

    private final String id;
    private final ShapeType shapeType;
    private final Envelope envelope;
    private final String payload;

    public SpatialObject(String id, ShapeType shapeType, Envelope envelope, String payload) {
        this.id = Objects.requireNonNull(id, "id");
        this.shapeType = Objects.requireNonNull(shapeType, "shapeType");
        this.envelope = Objects.requireNonNull(envelope, "envelope");
        this.payload = payload;
    }

    public String getId() { return id; }
    public ShapeType getShapeType() { return shapeType; }
    public Envelope getEnvelope() { return envelope; }
    public String getPayload() { return payload; }
}
