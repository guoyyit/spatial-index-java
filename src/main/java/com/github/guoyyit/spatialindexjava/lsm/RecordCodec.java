package com.github.guoyyit.spatialindexjava.lsm;

import com.github.guoyyit.spatialindexjava.core.Envelope;
import com.github.guoyyit.spatialindexjava.core.IndexRecord;
import com.github.guoyyit.spatialindexjava.core.ShapeType;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * Deterministic little-endian binary encoding for indexed records.
 *
 * <p>Record layout:
 * <pre>
 * long sequence | byte flags(bit0=tombstone, bit1=3D, bit2=rectangle)
 * 6 doubles envelope | int idLen bytes id | int payloadLen bytes payload(-1=null)
 * </pre>
 */
final class RecordCodec {

    static final byte FLAG_TOMBSTONE = 1;
    static final byte FLAG_3D = 2;
    static final byte FLAG_RECTANGLE = 4;

    static final int FIXED_BYTES = 8 + 1 + 6 * 8 + 4 + 4;

    private RecordCodec() { }

    static byte[] encode(IndexRecord record) {
        byte[] id = record.getId().getBytes(StandardCharsets.UTF_8);
        byte[] payload = record.getPayload() == null
                ? null : record.getPayload().getBytes(StandardCharsets.UTF_8);
        int payloadLen = payload == null ? 0 : payload.length;
        ByteBuffer buffer = ByteBuffer.allocate(FIXED_BYTES + id.length + payloadLen).order(ByteOrder.LITTLE_ENDIAN);
        write(buffer, record, id, payload);
        return buffer.array();
    }

    static int encodedSize(IndexRecord record) {
        int payloadLen = record.getPayload() == null ? 0
                : utf8Length(record.getPayload());
        return FIXED_BYTES + utf8Length(record.getId()) + payloadLen;
    }

    static int utf8Length(String s) {
        return s.getBytes(StandardCharsets.UTF_8).length;
    }

    static void write(ByteBuffer buffer, IndexRecord record) {
        write(buffer, record,
                record.getId().getBytes(StandardCharsets.UTF_8),
                record.getPayload() == null ? null
                        : record.getPayload().getBytes(StandardCharsets.UTF_8));
    }

    static void write(ByteBuffer buffer, IndexRecord record, byte[] idBytes, byte[] payloadBytes) {
        Envelope e = record.getEnvelope();
        byte flags = 0;
        if (record.isTombstone()) {
            flags |= FLAG_TOMBSTONE;
        }
        if (e.dimension() == 3) {
            flags |= FLAG_3D;
        }
        if (record.getShapeType() == ShapeType.RECTANGLE) {
            flags |= FLAG_RECTANGLE;
        }
        buffer.putLong(record.getSequence());
        buffer.put(flags);
        buffer.putDouble(e.getMinX());
        buffer.putDouble(e.getMinY());
        buffer.putDouble(e.getMinZ());
        buffer.putDouble(e.getMaxX());
        buffer.putDouble(e.getMaxY());
        buffer.putDouble(e.getMaxZ());
        buffer.putInt(idBytes.length);
        buffer.put(idBytes);
        if (payloadBytes == null) {
            buffer.putInt(-1);
        } else {
            buffer.putInt(payloadBytes.length);
            buffer.put(payloadBytes);
        }
    }

    static IndexRecord read(ByteBuffer buffer) {
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        long sequence = buffer.getLong();
        byte flags = buffer.get();
        double minX = buffer.getDouble();
        double minY = buffer.getDouble();
        double minZ = buffer.getDouble();
        double maxX = buffer.getDouble();
        double maxY = buffer.getDouble();
        double maxZ = buffer.getDouble();
        int idLen = buffer.getInt();
        byte[] idBytes = new byte[idLen];
        buffer.get(idBytes);
        int payloadLen = buffer.getInt();
        byte[] payloadBytes = null;
        if (payloadLen >= 0) {
            payloadBytes = new byte[payloadLen];
            buffer.get(payloadBytes);
        }
        boolean tombstone = (flags & FLAG_TOMBSTONE) != 0;
        boolean threeD = (flags & FLAG_3D) != 0;
        boolean rectangle = (flags & FLAG_RECTANGLE) != 0;
        Envelope envelope = threeD
                ? new Envelope(3, minX, minY, minZ, maxX, maxY, maxZ)
                : new Envelope(2, minX, minY, 0.0d, maxX, maxY, 0.0d);
        String id = new String(idBytes, StandardCharsets.UTF_8);
        String payload = payloadBytes == null ? null
                : new String(payloadBytes, StandardCharsets.UTF_8);
        ShapeType shape = rectangle ? ShapeType.RECTANGLE : ShapeType.POINT;
        return new IndexRecord(sequence, id, shape, envelope, payload, tombstone);
    }
}
