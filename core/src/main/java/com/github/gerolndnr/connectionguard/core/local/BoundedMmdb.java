package com.github.gerolndnr.connectionguard.core.local;

import com.github.gerolndnr.connectionguard.core.identity.Exemptions;
import java.math.BigInteger;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Immutable MMDB v2 reader for country/city/ASN records. No mapping, reflection or unbounded decoding.
 * Format: https://maxmind.github.io/MaxMind-DB/ . Limits apply before allocation, including metadata.
 */
public final class BoundedMmdb {
    public static final int MAX_BYTES = 64 * 1024 * 1024;
    private static final byte[] MARKER = {(byte) 0xab, (byte) 0xcd, (byte) 0xef, 'M', 'a', 'x', 'M', 'i', 'n', 'd', '.', 'c', 'o', 'm'};
    private final byte[] data;
    private final int nodes, recordSize, nodeSize, treeSize, dataStart, dataEnd, ipVersion;
    private final long buildTime;
    private final String databaseType;

    // Ownership of the array transfers to this reader; caller must not modify it.
    BoundedMmdb(byte[] data) {
        if (data.length < 32 || data.length > MAX_BYTES) throw invalid();
        this.data = data;
        int marker = -1;
        for (int i = data.length - MARKER.length; i >= Math.max(0, data.length - 131072); i--) {
            boolean match = true;
            for (int j = 0; j < MARKER.length; j++) if (data[i + j] != MARKER[j]) { match = false; break; }
            if (match) { marker = i; break; }
        }
        if (marker < 0) throw invalid();
        int metadataStart = marker + MARKER.length;
        Decoder decoder = new Decoder(metadataStart, data.length);
        Object decoded = decoder.value(new Cursor(metadataStart), 0, false);
        if (!(decoded instanceof Map)) throw invalid();
        Map<?, ?> metadata = (Map<?, ?>) decoded;
        nodes = boundedInt(metadata.get("node_count"));
        recordSize = boundedInt(metadata.get("record_size"));
        ipVersion = boundedInt(metadata.get("ip_version"));
        if (number(metadata.get("binary_format_major_version")) != 2 || number(metadata.get("binary_format_minor_version")) < 0
                || nodes < 1 || (recordSize != 24 && recordSize != 28 && recordSize != 32)
                || (ipVersion != 4 && ipVersion != 6) || !(metadata.get("database_type") instanceof String)) throw invalid();
        databaseType = (String) metadata.get("database_type");
        if (!databaseType.matches("[A-Za-z0-9_-]{1,100}")) throw invalid();
        long epoch = number(metadata.get("build_epoch"));
        if (epoch <= 0 || epoch > Long.MAX_VALUE / 1000) throw invalid();
        buildTime = epoch * 1000;
        nodeSize = recordSize / 4;
        long tree = (long) nodes * nodeSize;
        if (tree + 16 >= marker) throw invalid();
        treeSize = (int) tree;
        dataStart = treeSize + 16;
        dataEnd = marker;
        for (int i = treeSize; i < dataStart; i++) if (data[i] != 0) throw invalid();
        // Check every tree pointer, without decoding or expanding all records.
        for (int node = 0; node < nodes; node++) for (int side = 0; side < 2; side++) {
            long pointer = child(node, side);
            if (pointer > nodes && (pointer < (long) nodes + 16 || pointer - nodes + treeSize >= dataEnd)) throw invalid();
        }
    }
    public long getBuildTime() { return buildTime; }
    public String getDatabaseType() { return databaseType; }
    public int getIpVersion() { return ipVersion; }

    public Map<String, Object> lookup(String address) {
        final byte[] bytes;
        try { bytes = InetAddress.getByName(Exemptions.normalize(address)).getAddress(); }
        catch (java.net.UnknownHostException impossible) { throw invalid(); }
        if (bytes.length == 16 && ipVersion == 4) return Collections.emptyMap();
        long pointer = 0;
        if (bytes.length == 4 && ipVersion == 6) for (int bit = 0; bit < 96 && pointer < nodes; bit++) pointer = child((int) pointer, 0);
        for (int bit = 0; bit < bytes.length * 8 && pointer < nodes; bit++) {
            pointer = child((int) pointer, (bytes[bit / 8] >>> (7 - bit % 8)) & 1);
        }
        if (pointer == nodes) return Collections.emptyMap();
        if (pointer < nodes) throw invalid(); // A cycle or a tree deeper than this address.
        long position = pointer - nodes + treeSize;
        if (position < dataStart || position >= dataEnd) throw invalid();
        Object value = new Decoder(dataStart, dataEnd).value(new Cursor((int) position), 0, false);
        if (!(value instanceof Map)) throw invalid();
        @SuppressWarnings("unchecked") Map<String, Object> record = (Map<String, Object>) value;
        return record;
    }
    private long child(int node, int side) {
        if (node < 0 || node >= nodes) throw invalid();
        int at = node * nodeSize;
        if (recordSize == 28) {
            long head = side == 0 ? (data[at + 3] & 0xf0L) << 20 : (data[at + 3] & 15L) << 24;
            return head | unsigned(side == 0 ? at : at + 4, 3);
        }
        return unsigned(at + side * (recordSize / 8), recordSize / 8);
    }
    private long unsigned(int at, int size) {
        long result = 0;
        for (int i = 0; i < size; i++) result = (result << 8) | (data[at + i] & 255L);
        return result;
    }
    private static int boundedInt(Object value) {
        long number = number(value);
        if (number < 0 || number > Integer.MAX_VALUE) throw invalid();
        return (int) number;
    }
    static long number(Object value) {
        if (!(value instanceof Number) || value instanceof Double || value instanceof Float
                || (value instanceof BigInteger && ((BigInteger) value).bitLength() > 63)) throw invalid();
        return ((Number) value).longValue();
    }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("Invalid or excessive MMDB data (contents redacted)."); }
    private static final class Cursor { int position; Cursor(int position) { this.position = position; } }
    private final class Decoder {
        private final int base, end;
        private int values, payload;
        Decoder(int base, int end) { this.base = base; this.end = end; }
        private void require(Cursor cursor, int size) {
            if (size < 0 || cursor.position < base || (long) cursor.position + size > end) throw invalid();
        }
        private int read(Cursor cursor) { require(cursor, 1); return data[cursor.position++] & 255; }
        private long readNumber(Cursor cursor, int size) { require(cursor, size); long n = unsigned(cursor.position, size); cursor.position += size; return n; }
        private void reservePayload(int size) {
            if (size < 0 || size > 262144 - payload) throw invalid();
            payload += size;
        }
        Object value(Cursor cursor, int depth, boolean pointerTarget) {
            if (depth > 32 || ++values > 4096) throw invalid();
            int control = read(cursor), type = control >>> 5;
            if (type == 0) type = read(cursor) + 7;
            if (type == 1) {
                if (pointerTarget) throw invalid(); // The format forbids a pointer targeting a pointer.
                int size = ((control >>> 3) & 3) + 1;
                long pointer = readNumber(cursor, size);
                if (size < 4) pointer += ((long) (control & 7) << (size * 8)) + (size == 2 ? 2048 : size == 3 ? 526336 : 0);
                long target = base + pointer;
                if (target < base || target >= end) throw invalid();
                return value(new Cursor((int) target), depth + 1, true);
            }
            int size = control & 31;
            if (size == 29) size = 29 + read(cursor);
            else if (size == 30) size = 285 + (int) readNumber(cursor, 2);
            else if (size == 31) size = 65821 + (int) readNumber(cursor, 3);
            if (type == 7) {
                if (size > (4096 - values) / 2) throw invalid();
                Map<String, Object> map = new LinkedHashMap<>();
                for (int i = 0; i < size; i++) {
                    Object key = value(cursor, depth + 1, false);
                    if (!(key instanceof String) || map.containsKey(key)) throw invalid();
                    map.put((String) key, value(cursor, depth + 1, false));
                }
                return Collections.unmodifiableMap(map);
            }
            if (type == 11) {
                if (size > 4096 - values) throw invalid();
                List<Object> list = new ArrayList<>();
                for (int i = 0; i < size; i++) list.add(value(cursor, depth + 1, false));
                return Collections.unmodifiableList(list);
            }
            if (type == 14) { if (size > 1) throw invalid(); return size == 1; }
            require(cursor, size);
            reservePayload(size);
            if (type == 2) {
                try {
                    String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(data, cursor.position, size)).toString();
                    cursor.position += size; return text;
                } catch (CharacterCodingException invalid) { throw invalid(); }
            }
            if (type == 4) { byte[] bytes = Arrays.copyOfRange(data, cursor.position, cursor.position + size); cursor.position += size; return bytes; }
            if (type == 3 || type == 15) {
                if (size != (type == 3 ? 8 : 4)) throw invalid();
                long bits = readNumber(cursor, size);
                return type == 3 ? Double.longBitsToDouble(bits) : Float.intBitsToFloat((int) bits);
            }
            int max = type == 5 ? 2 : type == 6 || type == 8 ? 4 : type == 9 ? 8 : type == 10 ? 16 : -1;
            if (max < 0 || size > max) throw invalid();
            if (type == 9 || type == 10) {
                BigInteger number = size == 0 ? BigInteger.ZERO : new BigInteger(1, Arrays.copyOfRange(data, cursor.position, cursor.position + size));
                cursor.position += size; return number;
            }
            long number = readNumber(cursor, size);
            return type == 8 && size == 4 ? (long) (int) number : number;
        }
    }
}
