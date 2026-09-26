package com.github.guoyyit.spatialindexjava.core;

import com.github.guoyyit.spatialindexjava.model.Entry;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;

/**
 * Reads WAL records in order, stopping at the first incomplete or corrupt
 * record (a torn tail from a crash is normal and not an error).
 */
public final class WalReader {

    private static final int MAX_RECORD = 64 << 20;

    private WalReader() {
    }

    public static List<Entry> readAll(Path file) throws IOException {
        List<Entry> entries = new ArrayList<>();
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            ByteBuffer header = ByteBuffer.allocate(8);
            CRC32 crc = new CRC32();
            while (readFully(channel, header)) {
                header.flip();
                int len = header.getInt();
                int expectedCrc = header.getInt();
                header.clear();
                if (len <= 0 || len > MAX_RECORD) {
                    break; // corrupt length: stop replay here
                }
                ByteBuffer payload = ByteBuffer.allocate(len);
                if (!readFully(channel, payload)) {
                    break; // torn tail write
                }
                payload.flip();
                byte[] bytes = new byte[len];
                payload.get(bytes);
                crc.reset();
                crc.update(bytes);
                if ((int) crc.getValue() != expectedCrc) {
                    break; // corrupt record: stop replay here
                }
                try {
                    entries.add(WalCodec.decode(bytes));
                } catch (IOException | RuntimeException corrupt) {
                    break;
                }
            }
        }
        return entries;
    }

    private static boolean readFully(FileChannel channel, ByteBuffer buffer) throws IOException {
        while (buffer.hasRemaining()) {
            if (channel.read(buffer) < 0) {
                return false;
            }
        }
        return true;
    }
}
