package io.github.gauthierhuguenin.atvwake.companion;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Apple OPACK serialization, ported from pyatv/support/opack.py (subset needed by Companion). */
public final class Opack {
    private Opack() {}

    public static byte[] pack(Object data) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        write(out, data);
        return out.toByteArray();
    }

    private static void write(ByteArrayOutputStream out, Object data) {
        if (data == null) {
            out.write(0x04);
        } else if (data instanceof Boolean) {
            out.write((Boolean) data ? 0x01 : 0x02);
        } else if (data instanceof Integer || data instanceof Long) {
            long v = ((Number) data).longValue();
            if (v >= 0 && v < 0x28) {
                out.write((int) v + 8);
            } else if (v >= 0 && v <= 0xFF) {
                out.write(0x30);
                writeLe(out, v, 1);
            } else if (v >= 0 && v <= 0xFFFF) {
                out.write(0x31);
                writeLe(out, v, 2);
            } else if (v >= 0 && v <= 0xFFFFFFFFL) {
                out.write(0x32);
                writeLe(out, v, 4);
            } else {
                out.write(0x33);
                writeLe(out, v, 8);
            }
        } else if (data instanceof Double) {
            out.write(0x36);
            out.write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
                    .putDouble((Double) data).array(), 0, 8);
        } else if (data instanceof String) {
            byte[] b = ((String) data).getBytes(StandardCharsets.UTF_8);
            if (b.length <= 0x20) {
                out.write(0x40 + b.length);
            } else if (b.length <= 0xFF) {
                out.write(0x61);
                writeLe(out, b.length, 1);
            } else if (b.length <= 0xFFFF) {
                out.write(0x62);
                writeLe(out, b.length, 2);
            } else {
                out.write(0x64);
                writeLe(out, b.length, 4);
            }
            out.write(b, 0, b.length);
        } else if (data instanceof byte[]) {
            byte[] b = (byte[]) data;
            if (b.length <= 0x20) {
                out.write(0x70 + b.length);
            } else if (b.length <= 0xFF) {
                out.write(0x91);
                writeLe(out, b.length, 1);
            } else if (b.length <= 0xFFFF) {
                out.write(0x92);
                writeLe(out, b.length, 2);
            } else {
                out.write(0x93);
                writeLe(out, b.length, 4);
            }
            out.write(b, 0, b.length);
        } else if (data instanceof List) {
            List<?> list = (List<?>) data;
            out.write(0xD0 + Math.min(list.size(), 0xF));
            for (Object o : list) write(out, o);
            if (list.size() >= 0xF) out.write(0x03);
        } else if (data instanceof Map) {
            Map<?, ?> map = (Map<?, ?>) data;
            out.write(0xE0 + Math.min(map.size(), 0xF));
            for (Map.Entry<?, ?> e : map.entrySet()) {
                write(out, e.getKey());
                write(out, e.getValue());
            }
            if (map.size() >= 0xF) out.write(0x03);
        } else {
            throw new IllegalArgumentException("unsupported type " + data.getClass());
        }
    }

    private static void writeLe(ByteArrayOutputStream out, long v, int n) {
        for (int i = 0; i < n; i++) out.write((int) (v >>> (8 * i)) & 0xFF);
    }

    public static Object unpack(byte[] data) {
        return new Reader(data).read();
    }

    private static final class Reader {
        private final byte[] d;
        private int p;
        private final List<Object> objects = new ArrayList<>();

        Reader(byte[] d) {
            this.d = d;
        }

        private int u8() {
            return d[p++] & 0xFF;
        }

        private long le(int n) {
            long v = 0;
            for (int i = 0; i < n; i++) v |= ((long) (d[p + i] & 0xFF)) << (8 * i);
            p += n;
            return v;
        }

        private byte[] take(int n) {
            byte[] b = Arrays.copyOfRange(d, p, p + n);
            p += n;
            return b;
        }

        Object read() {
            int t = u8();
            Object value;
            boolean remember = true;
            if (t == 0x01) {
                value = Boolean.TRUE;
                remember = false;
            } else if (t == 0x02) {
                value = Boolean.FALSE;
                remember = false;
            } else if (t == 0x04) {
                value = null;
                remember = false;
            } else if (t == 0x05) {
                ByteBuffer bb = ByteBuffer.wrap(take(16));
                value = new UUID(bb.getLong(), bb.getLong());
            } else if (t == 0x06) {
                value = le(8);
            } else if (t >= 0x08 && t <= 0x2F) {
                value = (long) (t - 8);
                remember = false;
            } else if (t == 0x35) {
                value = (double) ByteBuffer.wrap(take(4)).order(ByteOrder.LITTLE_ENDIAN).getFloat();
            } else if (t == 0x36) {
                value = ByteBuffer.wrap(take(8)).order(ByteOrder.LITTLE_ENDIAN).getDouble();
            } else if ((t & 0xF0) == 0x30) {
                value = le(1 << (t & 0xF));
            } else if (t >= 0x40 && t <= 0x60) {
                value = new String(take(t - 0x40), StandardCharsets.UTF_8);
            } else if (t > 0x60 && t <= 0x64) {
                int len = (int) le(t & 0xF);
                value = new String(take(len), StandardCharsets.UTF_8);
            } else if (t >= 0x70 && t <= 0x90) {
                value = take(t - 0x70);
            } else if (t >= 0x91 && t <= 0x94) {
                int len = (int) le(1 << ((t & 0xF) - 1));
                value = take(len);
            } else if ((t & 0xF0) == 0xD0) {
                int count = t & 0xF;
                List<Object> list = new ArrayList<>();
                if (count == 0xF) {
                    while ((d[p] & 0xFF) != 0x03) list.add(read());
                    p++;
                } else {
                    for (int i = 0; i < count; i++) list.add(read());
                }
                value = list;
                remember = false;
            } else if ((t & 0xE0) == 0xE0) {
                int count = t & 0xF;
                Map<Object, Object> map = new LinkedHashMap<>();
                if (count == 0xF) {
                    while ((d[p] & 0xFF) != 0x03) {
                        Object k = read();
                        map.put(k, read());
                    }
                    p++;
                } else {
                    for (int i = 0; i < count; i++) {
                        Object k = read();
                        map.put(k, read());
                    }
                }
                value = map;
                remember = false;
            } else if (t >= 0xA0 && t <= 0xC0) {
                value = objects.get(t - 0xA0);
            } else if (t >= 0xC1 && t <= 0xC4) {
                value = objects.get((int) le(t - 0xC0));
            } else {
                throw new IllegalArgumentException("unknown OPACK tag 0x" + Integer.toHexString(t));
            }
            if (remember && !contains(value)) objects.add(value);
            return value;
        }

        private boolean contains(Object v) {
            for (Object o : objects) {
                if (o instanceof byte[] && v instanceof byte[]) {
                    if (Arrays.equals((byte[]) o, (byte[]) v)) return true;
                } else if (o != null && o.equals(v)) {
                    return true;
                }
            }
            return false;
        }
    }
}
