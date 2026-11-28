package at.vystorm.winter.world;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import Vystorm.vystorm_core.blocks.BlockWritesTesting;
import Vystorm.vystorm_core.blocks.EditCause;
import Vystorm.vystorm_core.blocks.EditedChunk;
import Vystorm.vystorm_core.blocks.StateTable;
import at.vystorm.winter.storage.TrackedBlocks;
import at.vystorm.winter.storage.TrackedBlocks.ChunkId;
import at.vystorm.winter.storage.TrackedBlocks.Key;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.junit.jupiter.api.Test;

/**
 * Bulk edits (Core's BlocksEditedEvent, 1.27.0): an edit that writes cells without block events must leave the
 * plugin's records as a player's block place or break would. A small world model stands for the server: a map of
 * block texts plus a thaw that follows the reconcile rules of {@link WinterTerrain} (a position whose block the
 * record does not accept is given up, the original back is forgotten, anything else gets its original back).
 */
class WinterEditedTest {
    static final UUID WORLD = UUID.fromString("00000000-0000-0000-0000-0000000000e1");
    static final String AIR = "minecraft:air", GRASS = "minecraft:short_grass", STONE = "minecraft:stone", PLANKS = "minecraft:oak_planks";
    static String snow(int layers) { return "minecraft:snow[layers=" + layers + "]"; }

    /** A chunk's persistent data, as in TrackedBlocksTest. */
    static final class MapStorage implements TrackedBlocks.Storage {
        final Map<ChunkId, byte[]> data = new HashMap<>();
        int writes;
        @Override public byte[] read(ChunkId chunk) { byte[] b = data.get(chunk); return b == null ? null : b.clone(); }
        @Override public void write(ChunkId chunk, byte[] bytes) {
            writes++;
            if (bytes == null) data.remove(chunk); else data.put(chunk, bytes.clone());
        }
    }

    /** Blocks by position; never set = air. */
    final Map<Key, String> world = new HashMap<>();
    final MapStorage storage = new MapStorage();
    final List<String> warnings = new ArrayList<>();
    final TrackedBlocks journal = new TrackedBlocks(storage, null, UUID.randomUUID(), warnings::add);
    final BlockWritesTesting.Detached core = BlockWritesTesting.detached(WinterEditedTest::data);
    final StateTable states = core.states();
    final World bukkitWorld = mock(World.class);
    { when(bukkitWorld.getUID()).thenReturn(WORLD); }

    /** A stand-in block state: only its text is used by the plugin and the state table. */
    static BlockData data(String text) {
        BlockData d = mock(BlockData.class, withSettings().stubOnly());
        when(d.getAsString()).thenReturn(text);
        when(d.clone()).thenReturn(d);
        return d;
    }
    static Key key(int x, int y, int z) { return new Key(WORLD, x, y, z); }
    String at(Key k) { return world.getOrDefault(k, AIR); }
    void load(int cx, int cz) { journal.load(new ChunkId(WORLD, cx, cz), (x, y, z) -> at(key(x, y, z))); }

    /** Winter lays its own block at a position (record first, then the block, as the terrain pass does). */
    void winter(Key k, String next) {
        assertNotNull(journal.intent(k, at(k), next), "winter may change " + k);
        world.put(k, next);
    }
    /** Many positions in one record write (the terrain pass stores a tick's changes together). */
    void winterAll(List<Key> keys, String next) {
        List<TrackedBlocks.Request> requests = new ArrayList<>();
        for (Key k : keys) requests.add(new TrackedBlocks.Request(k, at(k), next, false));
        for (TrackedBlocks.Entry e : journal.apply(requests)) assertNotNull(e);
        for (Key k : keys) world.put(k, next);
    }
    /** One bulk edit chunk: writes the cells into the world model and builds the event's chunk like VystormBuild does. */
    EditedChunk edit(UUID id, EditCause cause, Map<Key, String> cells) {
        Key first = cells.keySet().iterator().next();
        EditedChunk.Builder b = EditedChunk.builder(id, null, cause, bukkitWorld, first.x() >> 4, first.z() >> 4).states(states);
        for (var e : cells.entrySet()) {
            Key k = e.getKey();
            b.add(k.x(), k.y(), k.z(), states.id(at(k)), states.id(e.getValue()));
            world.put(k, e.getValue());
        }
        return b.build();
    }
    TrackedBlocks.EditOutcome handle(EditedChunk chunk) {
        return journal.edited(EditAware.cells(chunk, states));
    }
    /** One reconcile visit per tracked position until nothing is left to restore (a full thaw of the loaded chunks). */
    void thaw() {
        for (int round = 0; round < 10; round++) {
            boolean changed = false;
            for (ChunkId c : journal.chunksWithRecords()) {
                List<Key> keys = new ArrayList<>();
                for (int i = 0; i < journal.count(c); i++) keys.add(journal.keyAt(c, i));
                for (Key k : keys) {
                    TrackedBlocks.Entry e = journal.get(k);
                    if (e == null || e.released()) continue;
                    String current = at(k);
                    if (!e.accepts(current)) { journal.release(k); changed = true; }
                    else if (current.equals(e.original())) { journal.forget(k); journal.commit(); changed = true; }
                    else {
                        assertNotNull(journal.intent(k, current, e.original()));
                        world.put(k, e.original());
                        journal.forget(k);
                        journal.commit();
                        changed = true;
                    }
                }
            }
            if (!changed) return;
        }
    }
    /** A reconcile visit of one position, as the terrain pass makes it: a block the record does not accept is given up. */
    void reconcile(Key k) {
        TrackedBlocks.Entry e = journal.get(k);
        if (e != null && !e.released() && !e.accepts(at(k))) journal.release(k);
    }

    @Test
    void anEditThatWritesWinterBlockExactlyReleasesThePositionSoTheThawKeepsIt() {
        load(0, 0);
        Key k = key(3, 64, 4);
        winter(k, snow(1));
        winter(k, snow(2));
        var out = handle(edit(UUID.randomUUID(), EditCause.EDIT, Map.of(k, snow(2))));
        assertEquals(1, out.released());
        assertTrue(journal.get(k).released(), "a foreign block that looks like the plugin's is given up");
        thaw();
        assertEquals(snow(2), at(k), "the player's snow stays after the thaw");
    }

    @Test
    void anEditThatWritesTheBlockBeforeWinterChangedItIsReleasedToo() {
        load(0, 0);
        Key k = key(1, 64, 1);
        winter(k, snow(1));
        // Winter planned the next layer (recorded, not yet in the world): the record accepts layer 1 and layer 2.
        assertNotNull(journal.intent(k, snow(1), snow(2)));
        handle(edit(UUID.randomUUID(), EditCause.EDIT, Map.of(k, snow(1))));
        assertTrue(journal.get(k).released());
    }

    @Test
    void anEditThatWritesTheOriginalForgetsThePosition() {
        load(0, 0);
        Key k = key(5, 64, 5);
        world.put(k, GRASS);
        winter(k, snow(1));
        var out = handle(edit(UUID.randomUUID(), EditCause.EDIT, Map.of(k, GRASS)));
        assertEquals(1, out.forgotten());
        assertNull(journal.get(k), "the original is back: nothing left to restore");
        assertEquals(0, journal.size());
    }

    @Test
    void anEditWithAForeignBlockIsLeftToTheNextReconcileVisit() {
        load(0, 0);
        Key k = key(2, 64, 9);
        winter(k, snow(3));
        var out = handle(edit(UUID.randomUUID(), EditCause.EDIT, Map.of(k, STONE)));
        assertEquals(1, out.kept());
        TrackedBlocks.Entry e = journal.get(k);
        assertNotNull(e);
        assertFalse(e.released(), "the handler leaves it, as the plan says");
        assertFalse(e.winterBlock(STONE), "but the plugin never treats the stone as its own");
        thaw();
        assertEquals(STONE, at(k), "the thaw keeps the edit");
        assertTrue(journal.get(k).released(), "released by the reconcile visit as today");
    }

    @Test
    void undoAfterTheReconcileReleasedThePositionTracksTheSnowAgainAndTheThawRestoresTheOriginal() {
        load(0, 0);
        Key k = key(7, 64, 7);
        world.put(k, GRASS);
        winter(k, snow(1));
        winter(k, snow(2));
        handle(edit(UUID.randomUUID(), EditCause.EDIT, Map.of(k, PLANKS)));
        reconcile(k);
        assertTrue(journal.get(k).released());
        var out = handle(edit(UUID.randomUUID(), EditCause.UNDO, Map.of(k, snow(2))));
        assertEquals(1, out.retracked());
        TrackedBlocks.Entry e = journal.get(k);
        assertFalse(e.released());
        assertEquals(GRASS, e.original());
        assertTrue(e.winterBlock(snow(2)));
        thaw();
        assertEquals(GRASS, at(k), "the original comes back after edit, undo and thaw");
        assertNull(journal.get(k));
    }

    /**
     * VystormBuild writes a chunk's cells over several ticks and announces the chunk when all are written; the terrain
     * pass may visit a written cell before the event (found by the proof suite's edit.winter: 38 of 1004 cells of the
     * undone quarter kept their snow after the thaw).
     */
    @Test
    void aVisitBetweenTheWriteAndTheEventDoesNotLoseThePositionForTheUndo() {
        load(0, 0);
        Key planks = key(3, 64, 3), grass = key(4, 64, 3);
        world.put(grass, GRASS);
        winter(planks, snow(6));
        winter(grass, snow(5));
        long[] now = {1_000_000_000L};
        journal.clock(() -> now[0]);
        // the edit writes both cells; the pass visits them before the event: one is given up, one is forgotten
        world.put(planks, PLANKS);
        reconcile(planks);
        world.put(grass, GRASS);
        journal.forget(grass);
        journal.commit();
        assertTrue(journal.get(planks).released());
        assertNull(journal.get(grass));
        now[0] += 2_000_000_000L;
        EditedChunk e = EditedChunk.builder(UUID.randomUUID(), null, EditCause.EDIT, bukkitWorld, 0, 0).states(states)
                .add(3, 64, 3, states.id(snow(6)), states.id(PLANKS)).add(4, 64, 3, states.id(snow(5)), states.id(GRASS)).build();
        handle(e);
        assertEquals(2, journal.remembered());
        var out = handle(edit(UUID.randomUUID(), EditCause.UNDO, Map.of(planks, snow(6), grass, snow(5))));
        assertEquals(2, out.retracked());
        thaw();
        assertEquals(AIR, at(planks));
        assertEquals(GRASS, at(grass));
    }

    @Test
    void anOldReleaseIsNotTakenForAnEdit() {
        load(0, 0);
        Key k = key(5, 64, 5);
        winter(k, snow(3));
        long[] now = {0};
        journal.clock(() -> now[0]);
        // a player changed the block long before the edit: that release is not the edit's
        world.put(k, PLANKS);
        reconcile(k);
        now[0] += TrackedBlocks.RECENT_RELEASE_NANOS + 1;
        handle(edit(UUID.randomUUID(), EditCause.EDIT, Map.of(k, STONE)));
        assertEquals(0, journal.remembered());
        handle(edit(UUID.randomUUID(), EditCause.UNDO, Map.of(k, snow(3))));
        assertTrue(journal.get(k).released(), "the player's position stays given up");
    }

    @Test
    void undoOfAnEditThatWasReleasedOrForgottenTracksTheSnowAgain() {
        load(0, 0);
        Key released = key(1, 70, 1), forgotten = key(2, 70, 2);
        winter(released, snow(4));
        world.put(forgotten, GRASS);
        winter(forgotten, snow(1));
        handle(edit(UUID.randomUUID(), EditCause.EDIT, Map.of(released, snow(4), forgotten, GRASS)));
        assertTrue(journal.get(released).released());
        assertNull(journal.get(forgotten));
        var out = handle(edit(UUID.randomUUID(), EditCause.UNDO, Map.of(released, snow(4), forgotten, snow(1))));
        assertEquals(2, out.retracked());
        thaw();
        assertEquals(AIR, at(released));
        assertEquals(GRASS, at(forgotten));
        assertEquals(0, journal.held());
    }

    @Test
    void redoAfterUndoGivesThePositionUpAgain() {
        load(0, 0);
        Key k = key(4, 64, 4);
        winter(k, snow(2));
        handle(edit(UUID.randomUUID(), EditCause.EDIT, Map.of(k, snow(2))));
        handle(edit(UUID.randomUUID(), EditCause.UNDO, Map.of(k, snow(2))));
        assertFalse(journal.get(k).released());
        handle(edit(UUID.randomUUID(), EditCause.REDO, Map.of(k, snow(2))));
        assertTrue(journal.get(k).released());
        thaw();
        assertEquals(snow(2), at(k));
    }

    @Test
    void undoOfALaterEditKeepsTheMemoryOfTheFirstEdit() {
        load(0, 0);
        Key k = key(9, 64, 9);
        winter(k, snow(2));
        handle(edit(UUID.randomUUID(), EditCause.EDIT, Map.of(k, STONE)));
        reconcile(k);
        handle(edit(UUID.randomUUID(), EditCause.EDIT, Map.of(k, PLANKS)));
        handle(edit(UUID.randomUUID(), EditCause.UNDO, Map.of(k, STONE)));
        assertTrue(journal.get(k).released(), "stone is not the plugin's: nothing tracked yet");
        handle(edit(UUID.randomUUID(), EditCause.UNDO, Map.of(k, snow(2))));
        assertFalse(journal.get(k).released(), "the undo of the first edit brings back the plugin's snow");
        thaw();
        assertEquals(AIR, at(k));
    }

    @Test
    void anUndoWithoutAMemoryAndForeignBlocksChangesNothing() {
        load(0, 0);
        Key tracked = key(3, 64, 3), other = key(4, 64, 3);
        winter(tracked, snow(1));
        int writes = storage.writes;
        var out = handle(edit(UUID.randomUUID(), EditCause.UNDO, Map.of(other, STONE)));
        assertEquals(0, out.retracked() + out.released() + out.forgotten());
        assertEquals(writes, storage.writes, "nothing changed, nothing stored");
        assertFalse(journal.get(tracked).released());
    }

    @Test
    void releasedPositionsStayReleasedAndAChunkWithoutRecordsIsNotTouched() {
        load(0, 0);
        load(1, 0);
        Key k = key(6, 64, 6);
        winter(k, snow(1));
        journal.release(k);
        int writes = storage.writes;
        handle(edit(UUID.randomUUID(), EditCause.EDIT, Map.of(k, snow(1))));
        assertTrue(journal.get(k).released());
        var out = handle(edit(UUID.randomUUID(), EditCause.EDIT, Map.of(key(20, 64, 3), snow(1))));
        assertEquals(TrackedBlocks.EditOutcome.NONE, out);
        assertEquals(writes, storage.writes);
    }

    @Test
    void aChunkWhoseRecordIsNotLoadedIsLeftAlone() {
        Key k = key(1, 64, 1);
        var out = handle(edit(UUID.randomUUID(), EditCause.EDIT, Map.of(k, STONE)));
        assertEquals(TrackedBlocks.EditOutcome.NONE, out);
        assertEquals(0, storage.writes);
    }

    @Test
    void theRecordIsStoredOnceForTheWholeChunkAndSurvivesAReload() {
        load(0, 0);
        Map<Key, String> cells = new LinkedHashMap<>();
        for (int x = 0; x < 16; x++) {
            Key k = key(x, 64, 0);
            winter(k, snow(2));
            cells.put(k, snow(2));
        }
        int writes = storage.writes;
        handle(edit(UUID.randomUUID(), EditCause.EDIT, cells));
        assertEquals(writes + 1, storage.writes, "one write of the chunk record per event");
        ChunkId c = new ChunkId(WORLD, 0, 0);
        journal.unload(c);
        load(0, 0);
        for (Key k : cells.keySet()) assertTrue(journal.get(k).released());
    }

    @Test
    void moreThanTheLimitOfRememberedPositionsDropsTheOldestEditFirstAndWarnsOnce() {
        int chunksPerEdit = 10, perChunk = 4096;  // 40,960 positions per edit: the third edit goes over 100,000
        for (int e = 0; e < 3; e++) {
            UUID id = UUID.randomUUID();
            for (int c = 0; c < chunksPerEdit; c++) {
                int cx = e * chunksPerEdit + c;
                load(cx, 0);
                Map<Key, String> cells = new LinkedHashMap<>();
                for (int i = 0; i < perChunk; i++) cells.put(key((cx << 4) + (i & 15), 64 + (i >> 8), (i >> 4) & 15), STONE);
                winterAll(List.copyOf(cells.keySet()), snow(1));
                handle(edit(id, EditCause.EDIT, cells));
            }
        }
        assertTrue(journal.remembered() <= TrackedBlocks.EDIT_MEMORY_LIMIT, "remembered " + journal.remembered());
        assertEquals(2L * chunksPerEdit * perChunk, journal.remembered(), "the oldest edit's list went first, whole");
        assertEquals(1, warnings.stream().filter(w -> w.contains("bulk edits")).count(), warnings.toString());
        // The first edit's cells are not remembered any more: its undo after a reconcile cannot track the snow again.
        Key first = key(0, 64, 0), last = key(((3 * chunksPerEdit - 1) << 4), 64, 0);
        reconcile(first);
        reconcile(last);
        handle(edit(UUID.randomUUID(), EditCause.UNDO, Map.of(first, snow(1))));
        handle(edit(UUID.randomUUID(), EditCause.UNDO, Map.of(last, snow(1))));
        assertTrue(journal.get(first).released());
        assertFalse(journal.get(last).released());
    }

    /** Cost on the server thread: a 1M-cell edit over 256 chunks that all hold plugin records, and an edit over bare chunks. */
    @Test
    void handlerCostForAMillionCells() {
        costRun(1000);  // warm-up on other chunks (class loading, JIT), then the measured run
        long[] t = costRun(0);
        int chunks = 256;
        long cells = chunks * 4096L;
        System.out.printf(Locale.ROOT, "BlocksEditedEvent handler, %d chunks x 4096 cells: bare chunks %.3f ms (%.1f us per chunk), "
                        + "256 records per chunk %.2f ms (%.1f us per chunk, %.1f ns per cell), undo %.2f ms (%.1f us per chunk)%n",
                chunks, t[0] / 1e6, t[0] / 1e3 / chunks, t[1] / 1e6, t[1] / 1e3 / chunks, t[1] / (double) cells, t[2] / 1e6, t[2] / 1e3 / chunks);
        // Generous bounds for a busy machine; the numbers above are the measurement.
        assertTrue(t[0] / 1e6 < 25, "bare chunks must cost next to nothing");
        assertTrue(t[1] / 1e6 < 1000 && t[2] / 1e6 < 1000);
    }
    /** One 1M-cell edit, its undo and an edit over bare chunks, starting at chunk x {@code offset}; nanoseconds of each. */
    private long[] costRun(int offset) {
        int chunks = 256, side = 16;
        int remembered = journal.remembered(), size = journal.size();
        List<EditedChunk> full = new ArrayList<>(), bare = new ArrayList<>(), undo = new ArrayList<>();
        UUID id = UUID.randomUUID();
        for (int c = 0; c < chunks; c++) {
            int cx = offset + c % side, cz = c / side;
            load(cx, cz);
            load(cx + 100, cz);
            EditedChunk.Builder b = EditedChunk.builder(id, null, EditCause.EDIT, bukkitWorld, cx, cz).states(states);
            EditedChunk.Builder u = EditedChunk.builder(UUID.randomUUID(), null, EditCause.UNDO, bukkitWorld, cx, cz).states(states);
            EditedChunk.Builder n = EditedChunk.builder(id, null, EditCause.EDIT, bukkitWorld, cx + 100, cz).states(states);
            int stone = states.id(STONE), snow1 = states.id(snow(1)), air = states.id(AIR);
            List<Key> ground = new ArrayList<>();
            for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) ground.add(key((cx << 4) + x, 64, (cz << 4) + z));
            // the plugin's snow on the lowest layer of the box: 256 records per chunk
            winterAll(ground, snow(1));
            for (int y = 64; y < 80; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                int wx = (cx << 4) + x, wz = (cz << 4) + z;
                b.add(wx, y, wz, y == 64 ? snow1 : air, stone);
                u.add(wx, y, wz, stone, y == 64 ? snow1 : air);
                n.add(((cx + 100) << 4) + x, y, wz, air, stone);
            }
            full.add(b.build());
            bare.add(n.build());
            undo.add(u.build());
        }
        long t0 = System.nanoTime();
        for (EditedChunk e : bare) handle(e);
        long t1 = System.nanoTime();
        for (EditedChunk e : full) handle(e);
        long t2 = System.nanoTime();
        assertEquals(remembered + chunks * 256, journal.remembered());
        for (EditedChunk e : undo) handle(e);
        long t3 = System.nanoTime();
        assertEquals(remembered, journal.remembered(), "the undo used every remembered position");
        assertEquals(size + chunks * 256, journal.size(), "every snow cell is tracked after the undo");
        return new long[]{t1 - t0, t2 - t1, t3 - t2};
    }

    /** With an older Core the plugin never loads a class that names Core's block write types. */
    @Test
    void onlyTheEditAwareClassNamesCoresBlockWriteTypes() throws IOException {
        Path classes = Path.of(EditAware.class.getProtectionDomain().getCodeSource().getLocation().getPath().replaceFirst("^/([A-Za-z]:)", "$1"));
        List<String> naming = new ArrayList<>();
        try (Stream<Path> files = Files.walk(classes)) {
            for (Path f : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".class"))::iterator) {
                String text = new String(Files.readAllBytes(f), java.nio.charset.StandardCharsets.ISO_8859_1);
                if (text.contains("Vystorm/vystorm_core/blocks/")) naming.add(classes.relativize(f).toString().replace('\\', '/'));
            }
        }
        assertFalse(naming.isEmpty(), "the scan found the handler");
        // EditPermits (1.28.0) asks Core's build check for snow-free building, also only behind CoreApi.has("edit-ask")
        for (String n : naming) assertTrue(n.startsWith("at/vystorm/winter/world/EditAware") || n.equals("at/vystorm/winter/EditPermits.class"),
                "only EditAware and EditPermits may name the block write types: " + naming);
    }

    @Test
    void withoutTheCapabilityNothingIsRegisteredAndAFailureIsReturned() {
        assertNull(at.vystorm.winter.WinterPlugin.editAware(false, () -> fail("must not run without the capability")));
        boolean[] ran = {false};
        assertNull(at.vystorm.winter.WinterPlugin.editAware(true, () -> ran[0] = true));
        assertTrue(ran[0]);
        Throwable refused = at.vystorm.winter.WinterPlugin.editAware(true, () -> { throw new NoClassDefFoundError("Vystorm/vystorm_core/blocks/BlocksEditedEvent"); });
        assertInstanceOf(NoClassDefFoundError.class, refused);
    }
}
