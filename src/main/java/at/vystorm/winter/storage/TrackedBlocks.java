package at.vystorm.winter.storage;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.*;
import java.util.function.Consumer;
import java.util.zip.CRC32;

/**
 * The positions winter changed, kept per chunk (1.24.2). One compact record per chunk lists its positions with the
 * original block, the block before the last change and the block that change sets. Only loaded chunks are held in
 * memory; each chunk's record is stored inside the chunk itself ({@link Storage}, the chunk's persistent data), so the
 * record and the blocks are always saved together: a crash or rollback returns both to the same moment.
 *
 * <p>A position whose original block is back is removed. A position someone else changed (a player broke, placed or
 * replaced the block) is given up for good: it stays as a small "released" marker so the plugin never touches it again,
 * exactly like the released lines of the old journal.
 *
 * <p>Old journals ({@code blocks.jsonl}, up to 1.24.1) are moved chunk by chunk when the chunk loads, see
 * {@link LegacyJournal}. All methods run on the server thread.
 */
public final class TrackedBlocks {
    public record Key(UUID world, int x, int y, int z) { }
    public record ChunkId(UUID world, int x, int z) {
        public static ChunkId of(Key key) {
            return new ChunkId(key.world(), key.x() >> 4, key.z() >> 4);
        }
    }
    /** A tracked position; {@code released} positions are never touched again. */
    public record Entry(Key key, String original, String previous, String expected, boolean released) {
        public boolean accepts(String blockData) {
            return !released && (expected.equals(blockData) || previous.equals(blockData));
        }
        public boolean winterBlock(String blockData) {
            return accepts(blockData) && !original.equals(blockData);
        }
    }
    /** One planned change; {@code release} gives the position up instead. */
    public record Request(Key key, String current, String next, boolean release) { }
    /** Where a chunk's record lives: the chunk's persistent data on a server, a map in tests. */
    public interface Storage {
        byte[] read(ChunkId chunk);
        /** {@code data == null} removes the record. */
        void write(ChunkId chunk, byte[] data);
    }
    /** The current block data at a position of a loaded chunk (used by the migration). */
    public interface Blocks {
        String at(int x, int y, int z);
    }

    static final int FORMAT = 1;
    private static final int ID_BITS = 21, ID_MASK = (1 << ID_BITS) - 1;
    private static final long RELEASED = 1L << 63;

    /** Sorted positions of one chunk and their packed states. */
    static final class Records {
        int[] pos = new int[0];
        long[] val = new long[0];
        int n, active;
        /** Session in which old journal lines were merged into this chunk; null when the chunk never had any. */
        UUID migrated;
        /** The stored record could not be read: the chunk is left alone and the stored bytes are never overwritten. */
        boolean broken;
        int find(int p) {
            return Arrays.binarySearch(pos, 0, n, p);
        }
        void insert(int at, int p, long v) {
            if (n == pos.length) {
                int grown = Math.max(8, n + (n >> 1));
                pos = Arrays.copyOf(pos, grown);
                val = Arrays.copyOf(val, grown);
            }
            System.arraycopy(pos, at, pos, at + 1, n - at);
            System.arraycopy(val, at, val, at + 1, n - at);
            pos[at] = p;
            val[at] = v;
            n++;
        }
        void removeAt(int at) {
            System.arraycopy(pos, at + 1, pos, at, n - at - 1);
            System.arraycopy(val, at + 1, val, at, n - at - 1);
            n--;
            if (pos.length > 32 && n < pos.length / 4) {
                pos = Arrays.copyOf(pos, n * 2);
                val = Arrays.copyOf(val, n * 2);
            }
        }
    }

    private final Storage storage;
    private final LegacyJournal legacy;
    private final UUID session;
    private final Consumer<String> warn;
    private final List<String> palette = new ArrayList<>();
    private final Map<String, Integer> ids = new HashMap<>();
    private final Map<ChunkId, Records> chunks = new HashMap<>();
    private final Set<ChunkId> dirty = new LinkedHashSet<>();
    private int active, released, migratedPositions;

    /**
     * @param legacy  the old journal to migrate from, or null
     * @param session id of this server run (the same for a plugin reload in the same JVM); a chunk whose stored record
     *                carries another run's migration mark has been saved with it, so its old lines can go
     */
    public TrackedBlocks(Storage storage, LegacyJournal legacy, UUID session, Consumer<String> warn) {
        this.storage = storage;
        this.legacy = legacy;
        this.session = session;
        this.warn = warn == null ? message -> { } : warn;
    }

    // ---- palette ---------------------------------------------------------------------------------------------------
    private int id(String state) {
        Integer known = ids.get(state);
        if (known != null) return known;
        if (palette.size() > ID_MASK) throw new IllegalStateException("Too many different block states tracked");
        palette.add(state);
        ids.put(state, palette.size() - 1);
        return palette.size() - 1;
    }
    private static long pack(int original, int previous, int expected) {
        return original | (long)previous << ID_BITS | (long)expected << (2 * ID_BITS);
    }
    private static int original(long v) { return (int)(v & ID_MASK); }
    private static int previous(long v) { return (int)(v >>> ID_BITS & ID_MASK); }
    private static int expected(long v) { return (int)(v >>> (2 * ID_BITS) & ID_MASK); }
    private static boolean isReleased(long v) { return (v & RELEASED) != 0; }

    static int local(Key key) {
        return key.y() << 8 | (key.z() & 15) << 4 | key.x() & 15;
    }
    private static Key key(ChunkId chunk, int p) {
        return new Key(chunk.world(), (chunk.x() << 4) + (p & 15), p >> 8, (chunk.z() << 4) + (p >> 4 & 15));
    }
    private Entry entry(Key key, long v) {
        if (isReleased(v)) {
            String original = palette.get(original(v));
            return new Entry(key, original, original, original, true);
        }
        return new Entry(key, palette.get(original(v)), palette.get(previous(v)), palette.get(expected(v)), false);
    }

    // ---- chunk life cycle -------------------------------------------------------------------------------------------
    public boolean loaded(ChunkId chunk) {
        return chunks.containsKey(chunk);
    }
    /** Reads the chunk's record (and merges old journal lines for it); does nothing when it is already loaded. */
    public void load(ChunkId chunk, Blocks blocks) {
        if (chunks.containsKey(chunk)) return;
        Records r = new Records();
        byte[] data = storage.read(chunk);
        if (data != null) {
            try {
                decode(data, r);
            } catch (IOException | RuntimeException failure) {
                r = new Records();
                r.broken = true;
                warn.accept("Winter record of chunk " + chunk.x() + "," + chunk.z() + " could not be read (" + failure.getMessage()
                        + "); the plugin leaves this chunk alone and keeps the stored data unchanged");
            }
        }
        chunks.put(chunk, r);
        count(r, +1);
        if (legacy != null && !r.broken && legacy.has(chunk)) {
            if (r.migrated == null) {
                merge(chunk, r, blocks);
                r.migrated = session;
                dirty.add(chunk);
            } else if (!r.migrated.equals(session)) {
                // This record was read back from the saved chunk in a later run: the merge is saved with the world.
                legacy.drop(chunk);
            }
        }
        commit();
    }
    private void merge(ChunkId chunk, Records r, Blocks blocks) {
        for (LegacyJournal.Line line : legacy.lines(chunk)) {
            Key key = line.key();
            int p = local(key);
            int at = r.find(p);
            if (at >= 0) continue;
            if (line.released()) {
                r.insert(-at - 1, p, RELEASED | id(line.original()));
                released++;
                continue;
            }
            String current = blocks.at(key.x(), key.y(), key.z());
            // The old journal may be several changes ahead of the saved world; its history names every state it made.
            if (current == null || current.equals(line.original()) || !line.accepts(current)) continue;
            int c = id(current);
            r.insert(-at - 1, p, pack(id(line.original()), c, c));
            r.active++;
            active++;
            migratedPositions++;
        }
    }
    /** Writes the chunk's record if it changed and forgets the chunk. */
    public void unload(ChunkId chunk) {
        if (dirty.remove(chunk)) write(chunk);
        Records r = chunks.remove(chunk);
        if (r != null) count(r, -1);
    }
    public void unloadWorld(UUID world) {
        for (ChunkId chunk : List.copyOf(chunks.keySet())) if (chunk.world().equals(world)) unload(chunk);
    }
    private void count(Records r, int sign) {
        active += sign * r.active;
        released += sign * (r.n - r.active);
    }

    // ---- queries ----------------------------------------------------------------------------------------------------
    public Entry get(Key key) {
        Records r = chunks.get(ChunkId.of(key));
        if (r == null) return null;
        int at = r.find(local(key));
        return at < 0 ? null : entry(key, r.val[at]);
    }
    /** Positions whose original block still has to come back (loaded chunks). */
    public int size() {
        return active;
    }
    /** All records held in memory, given-up positions included. */
    public int held() {
        return active + released;
    }
    public int loadedChunks() {
        return chunks.size();
    }
    /** Old journal positions still waiting for their chunk. */
    public int legacyPending() {
        return legacy == null ? 0 : legacy.size();
    }
    /** Old journal positions moved into chunk records in this run. */
    public int migrated() {
        return migratedPositions;
    }
    /** Loaded chunks with at least one tracked position. */
    public List<ChunkId> chunksWithRecords() {
        List<ChunkId> out = new ArrayList<>();
        for (var e : chunks.entrySet()) if (e.getValue().n > 0) out.add(e.getKey());
        return out;
    }
    public int count(ChunkId chunk) {
        Records r = chunks.get(chunk);
        return r == null ? 0 : r.n;
    }
    public Key keyAt(ChunkId chunk, int index) {
        Records r = chunks.get(chunk);
        return r == null || index < 0 || index >= r.n ? null : key(chunk, r.pos[index]);
    }

    // ---- changes ----------------------------------------------------------------------------------------------------
    /** Records a planned change and stores it; null when the position is not the plugin's to change. */
    public Entry intent(Key key, String current, String next) {
        Entry out = change(key, current, next);
        commit();
        return out;
    }
    /** Several changes, stored together. Each result is the new entry or null (refused, or a release). */
    public List<Entry> apply(List<Request> requests) {
        List<Entry> out = new ArrayList<>(requests.size());
        for (Request r : requests) {
            if (r.release()) {
                releaseOne(r.key());
                out.add(null);
            } else out.add(change(r.key(), r.current(), r.next()));
        }
        commit();
        return out;
    }
    private Entry change(Key key, String current, String next) {
        ChunkId chunk = ChunkId.of(key);
        Records r = chunks.get(chunk);
        if (r == null || r.broken) return null;
        int p = local(key), at = r.find(p);
        int original;
        if (at >= 0) {
            long v = r.val[at];
            if (isReleased(v) || !entry(key, v).accepts(current)) return null;
            original = original(v);
        } else original = id(current);
        long v = pack(original, id(current), id(next));
        if (at >= 0) r.val[at] = v;
        else {
            r.insert(-at - 1, p, v);
            r.active++;
            active++;
        }
        dirty.add(chunk);
        return entry(key, v);
    }
    /** Gives a position up for good (someone else changed it). */
    public void release(Key key) {
        releaseOne(key);
        commit();
    }
    private void releaseOne(Key key) {
        ChunkId chunk = ChunkId.of(key);
        Records r = chunks.get(chunk);
        if (r == null || r.broken) return;
        int at = r.find(local(key));
        if (at < 0 || isReleased(r.val[at])) return;
        r.val[at] = RELEASED | original(r.val[at]);
        r.active--;
        active--;
        released++;
        dirty.add(chunk);
    }
    /** The original block is back: the position is removed (stored with the next {@link #commit}). */
    public void forget(Key key) {
        ChunkId chunk = ChunkId.of(key);
        Records r = chunks.get(chunk);
        if (r == null || r.broken) return;
        int at = r.find(local(key));
        if (at < 0 || isReleased(r.val[at])) return;
        r.removeAt(at);
        r.active--;
        active--;
        dirty.add(chunk);
    }
    /** Stores every changed chunk record. */
    public void commit() {
        if (dirty.isEmpty()) return;
        List<ChunkId> changed = List.copyOf(dirty);
        dirty.clear();
        for (ChunkId chunk : changed) write(chunk);
    }
    private void write(ChunkId chunk) {
        Records r = chunks.get(chunk);
        if (r == null || r.broken) return;
        // The migration mark stays with the chunk: it keeps a later run from merging the same old lines twice.
        boolean marked = r.migrated != null;
        storage.write(chunk, r.n == 0 && !marked ? null : encode(r, marked));
    }

    // ---- format -----------------------------------------------------------------------------------------------------
    /** version, migration mark, palette of this chunk, then per position: local position and three palette indexes. */
    byte[] encode(Records r, boolean marked) {
        Map<Integer, Integer> local = new HashMap<>();
        List<String> strings = new ArrayList<>();
        for (int i = 0; i < r.n; i++) {
            long v = r.val[i];
            for (int id : new int[]{original(v), previous(v), expected(v)}) {
                if (isReleased(v) && id != original(v)) continue;
                if (local.putIfAbsent(id, strings.size()) == null) strings.add(palette.get(id));
            }
        }
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(32 + r.n * 6 + strings.size() * 24);
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeByte(FORMAT);
            UUID mark = marked ? r.migrated : new UUID(0, 0);
            out.writeLong(mark.getMostSignificantBits());
            out.writeLong(mark.getLeastSignificantBits());
            varint(out, strings.size());
            for (String s : strings) out.writeUTF(s);
            varint(out, r.n);
            int last = Integer.MIN_VALUE;
            for (int i = 0; i < r.n; i++) {
                long v = r.val[i];
                // Positions ascend: store the distance to the previous one.
                varint(out, i == 0 ? zigzag(r.pos[i]) : r.pos[i] - last);
                last = r.pos[i];
                if (isReleased(v)) {
                    varint(out, 0);
                    varint(out, local.get(original(v)));
                } else {
                    // 1 = previous and expected equal (the settled case), 2 = all three given
                    boolean settled = previous(v) == expected(v);
                    varint(out, settled ? 1 : 2);
                    varint(out, local.get(original(v)));
                    varint(out, local.get(expected(v)));
                    if (!settled) varint(out, local.get(previous(v)));
                }
            }
            CRC32 crc = new CRC32();
            crc.update(bytes.toByteArray());
            out.writeInt((int)crc.getValue());
            return bytes.toByteArray();
        } catch (IOException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
    void decode(byte[] data, Records r) throws IOException {
        if (data.length < 4) throw new IOException("record too short");
        CRC32 crc = new CRC32();
        crc.update(data, 0, data.length - 4);
        int stored = (data[data.length - 4] & 255) << 24 | (data[data.length - 3] & 255) << 16 | (data[data.length - 2] & 255) << 8 | data[data.length - 1] & 255;
        if ((int)crc.getValue() != stored) throw new IOException("checksum mismatch");
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(data, 0, data.length - 4));
        int format = in.readUnsignedByte();
        if (format != FORMAT) throw new IOException("unsupported record format " + format);
        UUID mark = new UUID(in.readLong(), in.readLong());
        r.migrated = mark.getMostSignificantBits() == 0 && mark.getLeastSignificantBits() == 0 ? null : mark;
        int strings = varint(in);
        if (strings < 0 || strings > 1 << 16) throw new IOException("bad palette size");
        int[] global = new int[strings];
        for (int i = 0; i < strings; i++) global[i] = id(in.readUTF());
        int n = varint(in);
        if (n < 0 || n > 1 << 20) throw new IOException("bad record count");
        r.pos = new int[n];
        r.val = new long[n];
        int last = 0;
        for (int i = 0; i < n; i++) {
            int p = i == 0 ? unzigzag(varint(in)) : last + varint(in);
            if (i > 0 && p <= last) throw new IOException("positions out of order");
            last = p;
            int kind = varint(in);
            long v;
            if (kind == 0) v = RELEASED | global[index(in, strings)];
            else if (kind == 1) {
                int o = global[index(in, strings)], e = global[index(in, strings)];
                v = pack(o, e, e);
                r.active++;
            } else if (kind == 2) {
                int o = global[index(in, strings)], e = global[index(in, strings)], pr = global[index(in, strings)];
                v = pack(o, pr, e);
                r.active++;
            } else throw new IOException("bad entry kind " + kind);
            r.pos[i] = p;
            r.val[i] = v;
        }
        r.n = n;
        if (in.available() != 0) throw new IOException("trailing bytes");
    }
    private static int index(DataInputStream in, int size) throws IOException {
        int i = varint(in);
        if (i < 0 || i >= size) throw new IOException("bad palette index");
        return i;
    }
    private static int zigzag(int v) { return v << 1 ^ v >> 31; }
    private static int unzigzag(int v) { return v >>> 1 ^ -(v & 1); }
    private static void varint(DataOutputStream out, int v) throws IOException {
        while ((v & ~0x7F) != 0) {
            out.writeByte(v & 0x7F | 0x80);
            v >>>= 7;
        }
        out.writeByte(v);
    }
    private static int varint(DataInputStream in) throws IOException {
        int v = 0;
        for (int shift = 0; shift < 35; shift += 7) {
            int b = in.readUnsignedByte();
            v |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) return v;
        }
        throw new IOException("varint too long");
    }
}
