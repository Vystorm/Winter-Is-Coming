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
    /**
     * The cells of one chunk that a bulk edit wrote without block events (Core's BlocksEditedEvent, 1.27.0). States are
     * ids of the edit's own state table; {@link #text} turns one into block data text and is only asked for cells the
     * plugin has a record or a memory for.
     */
    public interface EditedCells {
        UUID world();
        int chunkX();
        int chunkZ();
        /** The cells went back to their state before an earlier edit. */
        boolean undo();
        UUID edit();
        int size();
        int x(int i);
        int y(int i);
        int z(int i);
        int afterId(int i);
        String text(int stateId);
    }
    /** What {@link #edited} did: positions forgotten (original back), released, left to the next visit, tracked again. */
    public record EditOutcome(int forgotten, int released, int kept, int retracked) {
        public static final EditOutcome NONE = new EditOutcome(0, 0, 0, 0);
    }
    /** Positions per world that bulk edits took from the plugin and that an undo may give back (in memory only). */
    public static final int EDIT_MEMORY_LIMIT = 100_000;
    /**
     * An edit tool writes a chunk's cells over several ticks and announces the chunk once all are written, so a visit
     * of the terrain pass may give a written position up (or forget it) before the event. A record given up or
     * forgotten within this time before the event counts as taken by the edit.
     */
    public static final long RECENT_RELEASE_NANOS = 60_000_000_000L;
    static final int RECENT_RELEASE_LIMIT = 65_536;

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
    /** Positions of a loaded chunk whose original still has to come back (given-up markers not counted). */
    public int active(ChunkId chunk) {
        Records r = chunks.get(chunk);
        return r == null ? 0 : r.active;
    }
    /**
     * Index of the first record at or after {@code from} (wrapping round once) that is not a given-up marker, -1 when
     * the chunk has none. The terrain pass walks with it, so given-up markers cost no visit (1.27.0).
     */
    public int nextActive(ChunkId chunk, int from) {
        Records r = chunks.get(chunk);
        if (r == null || r.active == 0 || r.n == 0) return -1;
        int start = Math.floorMod(from, r.n);
        for (int i = 0; i < r.n; i++) {
            int at = start + i < r.n ? start + i : start + i - r.n;
            if (!isReleased(r.val[at])) return at;
        }
        return -1;
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
        recent(key, chunk, r.val[at]);
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
        recent(key, chunk, r.val[at]);
        r.removeAt(at);
        r.active--;
        active--;
        dirty.add(chunk);
    }
    // ---- bulk edits (1.27.0) ------------------------------------------------------------------------------------------
    /** A position's record before a bulk edit took it, kept for the undo of that edit. */
    private record Former(long val, UUID edit) { }
    /** One edit's remembered positions, in the order they were added. */
    private static final class EditList {
        final List<ChunkId> chunks = new ArrayList<>();
        final List<int[]> positions = new ArrayList<>();
        /** Positions of this list still remembered for this edit; the list goes when it reaches 0. */
        int live;
    }
    private final Map<ChunkId, Map<Integer, Former>> memory = new HashMap<>();
    /** Per world: the edits with remembered positions, oldest first. */
    private final Map<UUID, LinkedHashMap<UUID, EditList>> memoryEdits = new HashMap<>();
    private final Map<UUID, Integer> memorySize = new HashMap<>();
    private final Set<UUID> memoryWarned = new HashSet<>();
    private int rememberedTotal;

    /** Positions taken by bulk edits that an undo may still give back to the plugin. */
    public int remembered() {
        return rememberedTotal;
    }

    /** Records given up or forgotten lately, per chunk: position -> {value, time}; oldest out over the limit. */
    private final Map<ChunkId, Map<Integer, long[]>> recent = new HashMap<>();
    private final ArrayDeque<Object[]> recentOrder = new ArrayDeque<>();
    private int recentSize;
    private java.util.function.LongSupplier clock = System::nanoTime;

    /** The clock of {@link #RECENT_RELEASE_NANOS} (tests). */
    public void clock(java.util.function.LongSupplier value) {
        clock = Objects.requireNonNull(value, "value");
    }
    private long recentSeq;
    private void recent(Key key, ChunkId chunk, long val) {
        int p = local(key);
        long seq = ++recentSeq;
        long[] before = recent.computeIfAbsent(chunk, c -> new HashMap<>()).put(p, new long[]{val, clock.getAsLong(), seq});
        if (before == null) recentSize++;
        recentOrder.addLast(new Object[]{chunk, p, seq});
        // over the limit the oldest go first; an order entry whose position was taken or renewed since only goes
        while (recentOrder.size() > RECENT_RELEASE_LIMIT) {
            Object[] old = recentOrder.pollFirst();
            Map<Integer, long[]> m = recent.get((ChunkId) old[0]);
            long[] v = m == null ? null : m.get((Integer) old[1]);
            if (v == null || v[2] != (Long) old[2]) continue;
            m.remove((Integer) old[1]);
            recentSize--;
            if (m.isEmpty()) recent.remove((ChunkId) old[0]);
        }
    }

    /**
     * A bulk edit wrote cells of one loaded chunk without block events. Treated like a player's block place or break:
     * <ul>
     *   <li>the original block is back: the position is forgotten;</li>
     *   <li>a new edit (not an undo) wrote a block the record accepts as the plugin's (the same snow, the same ice):
     *       the position is given up, so the thaw never takes the player's block away;</li>
     *   <li>any other block: left to the next visit of the terrain pass, which gives the position up as today.</li>
     * </ul>
     * The record a new edit took is remembered (at most {@link #EDIT_MEMORY_LIMIT} positions per world, oldest edit
     * first out). An undo that brings back exactly the block the record expected (or the one before) tracks the
     * position again with that record, so the thaw restores the original. Positions given up before the edit stay
     * given up. The chunk record is stored once at the end. Cost: nothing for a chunk without records or memory,
     * otherwise one binary search per cell.
     */
    public EditOutcome edited(EditedCells cells) {
        ChunkId chunk = new ChunkId(cells.world(), cells.chunkX(), cells.chunkZ());
        Records r = chunks.get(chunk);
        if (r == null || r.broken) return EditOutcome.NONE;
        Map<Integer, Former> remembered = memory.get(chunk);
        boolean undo = cells.undo();
        // records the terrain pass gave up or forgot in this chunk shortly before the event (written cells, see RECENT_RELEASE_NANOS)
        Map<Integer, long[]> lately = undo ? null : recent.get(chunk);
        if (r.n == 0 && remembered == null && lately == null) return EditOutcome.NONE;
        if (undo ? remembered == null : r.active == 0 && lately == null) return EditOutcome.NONE;
        long now = clock.getAsLong();
        Map<Integer, Integer> palette = new HashMap<>();
        int forgotten = 0, releasedNow = 0, kept = 0, retracked = 0;
        int[] took = undo ? null : new int[Math.min(cells.size(), Math.max(r.active, 1))];
        long[] tookVal = undo ? null : new long[took.length];
        int took_n = 0;
        boolean changed = false;
        for (int i = 0, size = cells.size(); i < size; i++) {
            int p = cells.y(i) << 8 | (cells.z(i) & 15) << 4 | cells.x(i) & 15;
            if (undo) {
                Former former = remembered == null ? null : remembered.get(p);
                if (former != null) {
                    int after = palette(palette, cells, cells.afterId(i));
                    long fv = former.val();
                    if (after >= 0 && after != original(fv) && (after == expected(fv) || after == previous(fv))) {
                        int at = r.find(p);
                        if (at < 0) {
                            r.insert(-at - 1, p, fv);
                            r.active++;
                            active++;
                            changed = true;
                        } else if (isReleased(r.val[at])) {
                            r.val[at] = fv;
                            r.active++;
                            active++;
                            released--;
                            changed = true;
                        }
                        remembered.remove(p);
                        memoryRemoved(chunk, 1);
                        listLost(chunk.world(), former.edit(), 1);
                        retracked++;
                        continue;
                    }
                }
                continue;
            }
            if (r.active == 0 && lately == null) break;
            int at = r.find(p);
            if (at < 0 || isReleased(r.val[at])) {
                long[] gone = lately == null ? null : lately.remove(p);
                if (gone != null) {
                    recentSize--;
                    // given up or forgotten by a visit after this edit wrote the cell: the edit took it
                    if (now - gone[1] <= RECENT_RELEASE_NANOS) {
                        if (took_n == took.length) {
                            took = Arrays.copyOf(took, took.length * 2);
                            tookVal = Arrays.copyOf(tookVal, took.length);
                        }
                        took[took_n] = p;
                        tookVal[took_n++] = gone[0];
                    }
                }
                continue;
            }
            long v = r.val[at];
            int after = palette(palette, cells, cells.afterId(i));
            if (took_n == took.length) {
                took = Arrays.copyOf(took, took.length * 2);
                tookVal = Arrays.copyOf(tookVal, took.length);
            }
            took[took_n] = p;
            tookVal[took_n++] = v;
            if (after == original(v)) {
                r.removeAt(at);
                r.active--;
                active--;
                forgotten++;
                changed = true;
            } else if (after >= 0 && (after == expected(v) || after == previous(v))) {
                r.val[at] = RELEASED | original(v);
                r.active--;
                active--;
                released++;
                releasedNow++;
                changed = true;
            } else kept++;
        }
        if (lately != null && lately.isEmpty()) recent.remove(chunk);
        if (took_n > 0) remember(chunk, cells.edit(), Arrays.copyOf(took, took_n), Arrays.copyOf(tookVal, took_n));
        if (changed) {
            dirty.add(chunk);
            commit();
        }
        return new EditOutcome(forgotten, releasedNow, kept, retracked);
    }
    /** The plugin's palette id of an edit state, -1 when the plugin never tracked that state (it then matches nothing). */
    private int palette(Map<Integer, Integer> cache, EditedCells cells, int stateId) {
        Integer known = cache.get(stateId);
        if (known != null) return known;
        Integer id = ids.get(cells.text(stateId));
        int out = id == null ? -1 : id;
        cache.put(stateId, out);
        return out;
    }
    private void remember(ChunkId chunk, UUID edit, int[] positions, long[] values) {
        Map<Integer, Former> byPos = memory.computeIfAbsent(chunk, c -> new HashMap<>());
        LinkedHashMap<UUID, EditList> edits = memoryEdits.computeIfAbsent(chunk.world(), w -> new LinkedHashMap<>());
        EditList list = edits.computeIfAbsent(edit, e -> new EditList());
        list.chunks.add(chunk);
        list.positions.add(positions);
        int added = 0;
        for (int i = 0; i < positions.length; i++) {
            Former before = byPos.put(positions[i], new Former(values[i], edit));
            list.live++;
            if (before == null) added++;
            else listLost(chunk.world(), before.edit(), 1);
        }
        int size = memorySize.merge(chunk.world(), added, Integer::sum);
        rememberedTotal += added;
        // Over the limit: the oldest edits go first, whole; the newest edit always stays.
        while (size > EDIT_MEMORY_LIMIT && edits.size() > 1) {
            UUID oldest = edits.keySet().iterator().next();
            EditList gone = edits.remove(oldest);
            for (int c = 0; c < gone.chunks.size(); c++) {
                ChunkId at = gone.chunks.get(c);
                Map<Integer, Former> m = memory.get(at);
                if (m == null) continue;
                int dropped = 0;
                for (int p : gone.positions.get(c)) {
                    Former f = m.get(p);
                    if (f != null && f.edit().equals(oldest)) {
                        m.remove(p);
                        dropped++;
                    }
                }
                memoryRemoved(at, dropped);
            }
            size = memorySize.getOrDefault(chunk.world(), 0);
            if (memoryWarned.add(chunk.world()))
                warn.accept("Winter remembers the snow of at most " + EDIT_MEMORY_LIMIT + " positions per world that bulk edits"
                        + " replaced; the oldest edit was forgotten, so undoing it brings back snow that does not thaw");
        }
    }
    /** {@code count} positions of an edit's list are no longer remembered for it; an empty list is removed. */
    private void listLost(UUID world, UUID edit, int count) {
        LinkedHashMap<UUID, EditList> edits = memoryEdits.get(world);
        EditList list = edits == null ? null : edits.get(edit);
        if (list == null) return;
        list.live -= count;
        if (list.live <= 0) {
            edits.remove(edit);
            if (edits.isEmpty()) memoryEdits.remove(world);
        }
    }
    private void memoryRemoved(ChunkId chunk, int count) {
        if (count == 0) return;
        rememberedTotal -= count;
        memorySize.merge(chunk.world(), -count, Integer::sum);
        Map<Integer, Former> m = memory.get(chunk);
        if (m != null && m.isEmpty()) memory.remove(chunk);
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
