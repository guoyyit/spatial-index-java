package com.github.guoyyit.spatialindexjava.core;

import com.github.guoyyit.spatialindexjava.model.BoundingBox;
import com.github.guoyyit.spatialindexjava.model.Entry;
import com.github.guoyyit.spatialindexjava.model.SpatialObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Binary encoding of WAL records.
 *
 * <p>Frame: [payloadLength: int][crc32: int][payload]. The CRC covers the
 * payload only. A torn tail write (short read, bad length or bad CRC) is
 * detected by the reader, which stops replay at that point.
 *
 * <p>Payload: [seq: long][op: byte][id: utf][dims: byte][min/max doubles]
 * [payloadLen: int][payload bytes]. Deletes carry no box/payload.
 */
final class WalCodec {

    static final int OP_PUT = 1;
    static final int OP_DELETE = 0;

    private WalCodec() {
    }

    static byte[] encode(Entry e) {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bos);
            out.writeLong(e.seq());
            if (e.tombstone()) {
                out.writeByte(OP_DELETE);
                writeUtf(out, e.id());
            } else {
                out.writeByte(OP_PUT);
                SpatialObject obj = e.object();
                writeUtf(out, obj.id());
                BoundingBox box = obj.box();
                out.writeByte(box.dims());
                for (int i = 0; i < box.dims(); i++) {
                    out.writeDouble(box.min(i));
                }
                for (int i = 0; i < box.dims(); i++) {
                    out.writeDouble(box.max(i));
                }
                byte[] payload = obj.payload();
                if (payload == null) {
                    out.writeInt(-1);
                } else {
                    out.writeInt(payload.length);
                    out.write(payload);
                }
            }
            out.flush();
            return bos.toByteArray();
        } catch (IOException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    static Entry decode(byte[] payload) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload));
        long seq = in.readLong();
        int op = in.readByte();
        String id = readUtf(in);
        if (op == OP_DELETE) {
            return Entry.delete(seq, id);
        }
        if (op != OP_PUT) {
            throw new IOException("unknown op " + op);
        }
        int dims = in.readByte();
        double[] min = new double[dims];
        double[] max = new double[dims];
        for (int i = 0; i < dims; i++) {
            min[i] = in.readDouble();
        }
        for (int i = 0; i < dims; i++) {
            max[i] = in.readDouble();
        }
        int payloadLen = in.readInt();
        byte[] objPayload = null;
        if (payloadLen >= 0) {
            objPayload = in.readNBytes(payloadLen);
            if (objPayload.length != payloadLen) {
                throw new EOFException("short payload");
            }
        }
        return Entry.put(seq, new SpatialObject(id, new BoundingBox(min, max), objPayload));
    }

    private static void writeUtf(DataOutputStream out, String s) throws IOException {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    private static String readUtf(DataInputStream in) throws IOException {
        int len = in.readInt();
        if (len < 0 || len > 1 << 20) {
            throw new IOException("implausible id length " + len);
        }
        byte[] bytes = in.readNBytes(len);
        if (bytes.length != len) {
            throw new EOFException("short id");
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
