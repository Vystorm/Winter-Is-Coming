package at.vystorm.winter.storage;

import static org.junit.jupiter.api.Assertions.*;

import at.vystorm.winter.storage.TrackedBlocks.ChunkId;
import at.vystorm.winter.storage.TrackedBlocks.Key;
import at.vystorm.winter.storage.TrackedBlocksTest.MapStorage;
import com.google.gson.Gson;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Winter and thaw on a model world with the plugin's order of work (record the change at the end of a tick, change the
 * block at the start of the next), saves at random moments, crashes (back to the last save of blocks and chunk records
 * together), clean restarts and a migration from an old journal that is ahead of the saved world. After the thaw every
 * block must be its original again; natural snow and positions players took over stay untouched.
 */
class TrackingCrashTest {
    static final UUID WORLD = TrackedBlocksTest.WORLD;
    static final int GROUND = 64, SIZE = 40;
    @TempDir Path dir;

    /** Blocks and chunk records as one unit: what a chunk save writes and a crash goes back to. */
    static final class World {
        final Map<Key, String> blocks = new HashMap<>();
        MapStorage storage = new MapStorage();
        World copy() {
            World w = new World();
            w.blocks.putAll(blocks);
            w.storage = storage.copy();
            return w;
        }
        String at(int x, int y, int z) {
            return blocks.getOrDefault(new Key(WORLD, x, y, z), "minecraft:air");
        }
    }
    record Op(Key key, String before, String after) { }

    final class Server {
        World disk, live;
        TrackedBlocks store;
        LegacyJournal legacy;
        List<Op> pending = List.of();
        List<TrackedBlocks.Entry> recorded = List.of();
        final Path journal;
        final Random random;
        Server(World disk, Path journal, Random random) {
            this.disk = disk;
            this.journal = journal;
            this.random = random;
        }
        void start() throws Exception {
            live = disk.copy();
            legacy = LegacyJournal.open(journal);
            store = new TrackedBlocks(live.storage, legacy, UUID.randomUUID(), message -> fail(message));
            for (int cx = 0; cx * 16 < SIZE; cx++) for (int cz = 0; cz * 16 < SIZE; cz++) store.load(new ChunkId(WORLD, cx, cz), live::at);
            pending = List.of();
        }
        void save() {
            disk = live.copy();
        }
        void stop() throws Exception {
            tick(Map::of);
            store.commit();
            if (legacy != null) legacy.close();
            save();
        }
        void crash() throws Exception {
            // The old journal's background save may or may not have finished; both files are whole either way.
            if (legacy != null && random.nextBoolean()) legacy.saveLater();
            live = null;
        }
        /** One tick: apply what the last tick recorded, then record the planned changes. */
        void tick(java.util.function.Supplier<Map<Key, String>> planner) {
            for (int i = 0; i < pending.size(); i++) {
                Op op = pending.get(i);
                var entry = recorded.get(i);
                if (entry == null) continue;
                if (!live.at(op.key.x(), op.key.y(), op.key.z()).equals(op.before)) {
                    store.release(op.key);
                    continue;
                }
                set(op.key, op.after);
                if (op.after.equals(entry.original())) store.forget(op.key);
            }
            store.commit();
            List<Op> ops = new ArrayList<>();
            List<TrackedBlocks.Request> requests = new ArrayList<>();
            Map<Key, String> plan = planner.get();
            for (var e : plan.entrySet()) {
                String before = live.at(e.getKey().x(), e.getKey().y(), e.getKey().z());
                ops.add(new Op(e.getKey(), before, e.getValue()));
                requests.add(new TrackedBlocks.Request(e.getKey(), before, e.getValue(), false));
            }
            pending = ops;
            recorded = store.apply(requests);
            if (legacy != null && random.nextInt(20) == 0) legacy.saveLater();
        }
        void set(Key k, String state) {
            if (state.equals("minecraft:air")) live.blocks.remove(k);
            else live.blocks.put(k, state);
        }
        boolean owned(int x, int y, int z) {
            var e = store.get(new Key(WORLD, x, y, z));
            return e != null && e.winterBlock(live.at(x, y, z));
        }
        /** Next snow layer per column towards its depth, on a random part of the columns (scattered like the pass). */
        Map<Key, String> winterPlan(int share) {
            Map<Key, String> plan = new HashMap<>();
            for (int x = 0; x < SIZE; x++) for (int z = 0; z < SIZE; z++) {
                if (random.nextInt(100) >= share) continue;
                int depth = 4 + Math.floorMod(x * 7 + z * 13, 9);
                for (int off = 0; off < 2; off++) {
                    int y = GROUND + 1 + off, want = Math.min(8, depth - off * 8);
                    if (want <= 0) break;
                    String b = live.at(x, y, z);
                    int layers = layers(b);
                    if (layers > 0 && !owned(x, y, z)) break; // natural snow: never adopted
                    if (layers == 0 && !b.equals("minecraft:air") && !b.equals("minecraft:short_grass")) break;
                    if (layers < want) {
                        plan.put(new Key(WORLD, x, y, z), TrackedBlocksTest.snow(layers + 1));
                        break;
                    }
                    if (layers < 8) break;
                }
            }
            return plan;
        }
        /** The thaw: one layer down per visit, the top block first; mismatches are given up. */
        Map<Key, String> thawPlan(int share) {
            Map<Key, String> plan = new HashMap<>();
            for (ChunkId chunk : store.chunksWithRecords()) for (int i = 0; i < store.count(chunk); i++) {
                if (random.nextInt(100) >= share) continue;
                Key k = store.keyAt(chunk, i);
                var e = store.get(k);
                if (e.released()) continue;
                String current = live.at(k.x(), k.y(), k.z());
                if (!e.accepts(current)) {
                    store.release(k);
                    continue;
                }
                if (current.equals(e.original())) {
                    store.forget(k);
                    continue;
                }
                if (owned(k.x(), k.y() + 1, k.z()) && layers(live.at(k.x(), k.y() + 1, k.z())) > 0) continue;
                int layers = layers(current);
                plan.put(k, layers > 1 ? TrackedBlocksTest.snow(layers - 1) : e.original());
            }
            store.commit();
            return plan;
        }
    }
    static int layers(String state) {
        if (!state.startsWith("minecraft:snow[layers=")) return 0;
        return Integer.parseInt(state.substring("minecraft:snow[layers=".length(), state.length() - 1));
    }

    /** Grass ground, some short grass, a few natural snow layers and a block a player placed. */
    static World terrain() {
        World w = new World();
        for (int x = 0; x < SIZE; x++) for (int z = 0; z < SIZE; z++) {
            w.blocks.put(new Key(WORLD, x, GROUND, z), "minecraft:grass_block");
            if ((x * 31 + z * 17) % 5 == 0) w.blocks.put(new Key(WORLD, x, GROUND + 1, z), "minecraft:short_grass");
        }
        for (int i = 0; i < 6; i++) w.blocks.put(new Key(WORLD, 3 + i * 5, GROUND + 1, 7), "minecraft:snow[layers=3]");
        return w;
    }

    @Test
    void winterAndThawWithCrashesRestoreEveryBlock() throws Exception {
        for (int seed = 1; seed <= 25; seed++) {
            Random random = new Random(seed);
            World before = terrain();
            Server server = new Server(before.copy(), dir.resolve("none-" + seed + ".jsonl"), random);
            server.start();
            for (int t = 0; t < 140; t++) {
                server.tick(() -> server.winterPlan(35));
                if (random.nextInt(15) == 0) server.save();
                if (random.nextInt(40) == 0) {
                    server.crash();
                    server.start();
                }
            }
            for (int t = 0; t < 60; t++) server.tick(() -> server.winterPlan(100));
            server.tick(Map::of);
            assertTrue(server.store.size() > SIZE * SIZE, "full winter tracks every column, seed " + seed);
            server.stop();
            server.start();
            for (int t = 0; t < 400 && (server.store.size() > 0 || !server.pending.isEmpty()); t++) {
                server.tick(() -> server.thawPlan(40));
                if (random.nextInt(10) == 0) server.save();
                if (random.nextInt(30) == 0) {
                    server.crash();
                    server.start();
                }
            }
            server.stop();
            server.start();
            for (int t = 0; t < 50; t++) server.tick(() -> server.thawPlan(100));
            assertEquals(before.blocks, server.live.blocks, "every block back after the thaw, seed " + seed);
            assertEquals(0, server.store.size(), "tracked count back to zero, seed " + seed);
            assertTrue(server.live.storage.data.isEmpty(), "no chunk keeps a record, seed " + seed);
        }
    }

    // ---- migration from an old journal ------------------------------------------------------------------------------

    /** Writes the journal the old plugin would have written for this winter; some positions run ahead of the world. */
    static void oldJournal(Path path, World world, World winter, Random random, Set<Key> lagging) throws Exception {
        Gson gson = new Gson();
        StringBuilder out = new StringBuilder();
        for (var e : winter.blocks.entrySet()) {
            Key k = e.getKey();
            String original = world.blocks.getOrDefault(k, "minecraft:air");
            if (original.equals(e.getValue())) continue;
            int layers = layers(e.getValue());
            List<String> states = new ArrayList<>();
            states.add(original);
            if (layers > 0) for (int l = 1; l <= layers; l++) states.add(TrackedBlocksTest.snow(l));
            else states.add(e.getValue());
            // a few positions: the journal went two changes further than the saved world
            boolean ahead = layers > 0 && layers < 7 && random.nextInt(8) == 0;
            if (ahead) {
                states.add(TrackedBlocksTest.snow(layers + 1));
                states.add(TrackedBlocksTest.snow(layers + 2));
                lagging.add(k);
            }
            Set<String> history = new LinkedHashSet<>();
            for (int i = 1; i < states.size(); i++) {
                history.add(states.get(i - 1));
                history.add(states.get(i));
                LegacyJournal.Line line = new LegacyJournal.Line(1, k, original, states.get(i - 1), states.get(i), false, Set.copyOf(history));
                String json = gson.toJson(line);
                out.append(json).append('\t').append(LegacyJournal.checksum(json)).append('\n');
            }
        }
        Files.writeString(path, out, StandardCharsets.UTF_8);
    }

    @Test
    void oldJournalMigratesWithCrashesAndThawRestoresEveryBlock() throws Exception {
        for (int seed = 1; seed <= 15; seed++) {
            Random random = new Random(100 + seed);
            World before = terrain();
            // The old plugin's winter: every column snowy, one column taken over by a player.
            World winter = before.copy();
            for (int x = 0; x < SIZE; x++) for (int z = 0; z < SIZE; z++) {
                Key k = new Key(WORLD, x, GROUND + 1, z);
                if (layers(winter.blocks.getOrDefault(k, "")) > 0) continue;
                int depth = 2 + Math.floorMod(x * 5 + z * 3, 12);
                winter.blocks.put(k, TrackedBlocksTest.snow(Math.min(8, depth)));
                if (depth > 8) winter.blocks.put(new Key(WORLD, x, GROUND + 2, z), TrackedBlocksTest.snow(depth - 8));
            }
            Path path = dir.resolve("blocks-" + seed + ".jsonl");
            Set<Key> lagging = new HashSet<>();
            oldJournal(path, before, winter, random, lagging);
            // a player replaced the snow of one column with stone: the old journal released that position
            Key taken = new Key(WORLD, 20, GROUND + 1, 20);
            winter.blocks.put(taken, "minecraft:stone");
            Gson gson = new Gson();
            String json = gson.toJson(new LegacyJournal.Line(1, taken, before.blocks.getOrDefault(taken, "minecraft:air"), TrackedBlocksTest.snow(1), TrackedBlocksTest.snow(1), true, Set.of()));
            Files.writeString(path, json + '\t' + LegacyJournal.checksum(json) + '\n', java.nio.file.StandardOpenOption.APPEND);
            // a torn last line and a half-written compaction file of an earlier crash
            Files.writeString(path, "{\"schema\":1,\"key\":{\"wor", java.nio.file.StandardOpenOption.APPEND);
            Files.writeString(path.resolveSibling(path.getFileName() + ".tmp"), "garbage from a crash");
            byte[] original = Files.readAllBytes(path);
            int oldPositions = (int)Files.readAllLines(path).stream().filter(l -> l.contains("\t")).map(l -> new Gson().fromJson(l.substring(0, l.lastIndexOf('\t')), LegacyJournal.Line.class).key()).distinct().count();
            assertFalse(lagging.isEmpty());

            Server server = new Server(winter.copy(), path, random);
            server.start();
            assertEquals(oldPositions, server.legacy.size() + 0);
            assertArrayEquals(original, Files.readAllBytes(path.resolveSibling(path.getFileName() + LegacyJournal.BACKUP_SUFFIX)), "backup of the old journal");
            int migrated = server.store.migrated();
            assertTrue(migrated >= SIZE * SIZE, "migrated " + migrated);
            assertTrue(server.store.get(taken).released(), "the player's block stays given up");
            // Some thaw, then a crash before anything was saved: the old journal must still hold everything.
            for (int t = 0; t < 5; t++) server.tick(() -> server.thawPlan(30));
            server.crash();
            server.start();
            assertEquals(oldPositions, server.legacy.size(), "nothing leaves the old journal before its chunk was saved and read back");
            for (int t = 0; t < 10; t++) server.tick(() -> server.thawPlan(30));
            server.save();
            for (int t = 0; t < 10; t++) server.tick(() -> server.thawPlan(30));
            server.crash();
            server.start();
            // the saved chunks carry the earlier run's mark: their old lines go now
            assertEquals(0, server.legacy == null ? 0 : server.store.legacyPending());
            for (int t = 0; t < 400 && (server.store.size() > 0 || !server.pending.isEmpty()); t++) {
                server.tick(() -> server.thawPlan(40));
                if (random.nextInt(10) == 0) server.save();
                if (random.nextInt(25) == 0) {
                    server.crash();
                    server.start();
                }
            }
            server.stop();
            server.start();
            for (int t = 0; t < 50; t++) server.tick(() -> server.thawPlan(100));
            World expected = before.copy();
            expected.blocks.put(taken, "minecraft:stone");
            assertEquals(expected.blocks, server.live.blocks, "every original block back after migration and thaw, seed " + seed);
            assertEquals(0, server.store.size());
            server.stop();
            assertEquals(0, Files.size(path), "old journal empty after every chunk was saved with its record");
            assertArrayEquals(original, Files.readAllBytes(path.resolveSibling(path.getFileName() + LegacyJournal.BACKUP_SUFFIX)));
        }
    }
}
