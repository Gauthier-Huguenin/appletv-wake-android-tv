package io.github.gauthierhuguenin.atvwake.companion;

import java.io.ByteArrayOutputStream;
import java.util.LinkedHashMap;
import java.util.Map;

/** HAP TLV8 encoding, ported from pyatv/auth/hap_tlv8.py. */
final class Tlv8 {
    static final int IDENTIFIER = 0x01;
    static final int PUBLIC_KEY = 0x03;
    static final int ENCRYPTED_DATA = 0x05;
    static final int SEQ_NO = 0x06;
    static final int ERROR = 0x07;
    static final int SIGNATURE = 0x0A;

    private Tlv8() {}

    static byte[] write(Map<Integer, byte[]> items) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (Map.Entry<Integer, byte[]> e : items.entrySet()) {
            byte[] v = e.getValue();
            int pos = 0;
            do {
                int size = Math.min(v.length - pos, 255);
                out.write(e.getKey());
                out.write(size);
                out.write(v, pos, size);
                pos += size;
            } while (pos < v.length);
        }
        return out.toByteArray();
    }

    static Map<Integer, byte[]> read(byte[] data) {
        Map<Integer, byte[]> result = new LinkedHashMap<>();
        int pos = 0;
        while (pos < data.length) {
            int tag = data[pos] & 0xFF;
            int len = data[pos + 1] & 0xFF;
            byte[] value = new byte[len];
            System.arraycopy(data, pos + 2, value, 0, len);
            byte[] prev = result.get(tag);
            if (prev != null) {
                byte[] merged = new byte[prev.length + len];
                System.arraycopy(prev, 0, merged, 0, prev.length);
                System.arraycopy(value, 0, merged, prev.length, len);
                value = merged;
            }
            result.put(tag, value);
            pos += 2 + len;
        }
        return result;
    }
}
