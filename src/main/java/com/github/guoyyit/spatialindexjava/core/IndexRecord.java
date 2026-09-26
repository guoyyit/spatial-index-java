package com.github.guoyyit.spatialindexjava.core;

/**
 * One indexed version of an object: either a live put or a tombstone (deletion).
 */
public final class IndexRecord {

    private final long sequence;
    private final String id;
    private final ShapeType shapeType;
    private final Envelope envelope;
    private final String payload;
    private final boolean tombstone;

    public IndexRecord(long sequence, String id, ShapeType shapeType,
                       Envelope envelope, String payload, boolean tombstone) {
        this.sequence = sequence;
        this.id = id;
        this.shapeType = shapeType;
        this.envelope = envelope;
        this.payload = payload;
        this.tombstone = tombstone;
    }

    public static IndexRecord put(long sequence, SpatialObject object) {
        return new IndexRecord(sequence, object.getId(), object.getShapeType(),
                object.getEnvelope(), object.getPayload(), false);
    }

    public static IndexRecord tombstone(long sequence, String id, ShapeType lastShape, Envelope lastEnvelope) {
        return new IndexRecord(sequence, id, lastShape, lastEnvelope, null, true);
    }

    public long getSequence() { return sequence; }
    public String getId() { return id; }
    public ShapeType getShapeType() { return shapeType; }
    public Envelope getEnvelope() { return envelope; }
    public String getPayload() { return payload; }
    public boolean isTombstone() { return tombstone; }

    public SpatialObject toObject() {
        return new SpatialObject(id, shapeType, envelope, payload);
    }
}
