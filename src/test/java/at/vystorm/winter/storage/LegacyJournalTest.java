package at.vystorm.winter.storage;

import static org.junit.jupiter.api.Assertions.*;

import at.vystorm.winter.storage.TrackedBlocks.ChunkId;
import at.vystorm.winter.storage.TrackedBlocks.Key;
import at.vystorm.winter.storage.TrackedBlocksTest.MapStorage;
import com.google.gson.Gson;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Reading and compacting the journal of 1.24.1, with a real journal of a 1.24.1 test server run. */
class LegacyJournalTest {
    @TempDir Path dir;

    /** All lines of four chunks (two with sea ice) from a 1.24.1 proof run: 5720 lines, 1024 positions. */
    private Path realJournal() throws IOException {
        Path path = dir.resolve("blocks.jsonl");
        try (InputStream in = new GZIPInputStream(Objects.requireNonNull(getClass().getResourceAsStream("/old-journal/blocks-1.24.1-sample.jsonl.gz")))) {
            Files.write(path, in.readAllBytes());
        }
        return path;
    }
    /** The last line per position, read independently of the class under test. */
    private static Map<Key, LegacyJournal.Line> lastLines(Path path) throws IOException {
        Map<Key, LegacyJournal.Line> last = new LinkedHashMap<>();
        Gson gson = new Gson();
        for (String l : Files.readAllLines(path, StandardCharsets.UTF_8)) if (l.contains("\t")) {
            LegacyJournal.Line line = gson.fromJson(l.substring(0, l.lastIndexOf('\t')), LegacyJournal.Line.class);
            last.put(line.key(), line);
        }
        return last;
    }

    @Test
    void realOldJournalMigratesEveryOriginalAndThawsBack() throws Exception {
        Path path = realJournal();
        Map<Key, LegacyJournal.Line> last = lastLines(path);
        assertEquals(1024, last.size());
        for (boolean lagging : new boolean[]{false, true}) {
            // The saved world: the last state the journal set, or (lagging) the state before it, as after a crash.
            Map<Key, String> world = new HashMap<>();
            for (var line : last.values()) world.put(line.key(), lagging ? line.previous() : line.expected());
            Files.deleteIfExists(path.resolveSibling("blocks.jsonl" + LegacyJournal.BACKUP_SUFFIX));
            LegacyJournal legacy = LegacyJournal.open(path);
            assertEquals(1024, legacy.size());
            MapStorage storage = new MapStorage();
            TrackedBlocks blocks = new TrackedBlocks(storage, legacy, UUID.randomUUID(), message -> fail(message));
            Set<ChunkId> chunks = new HashSet<>();
            for (Key k : last.keySet()) chunks.add(ChunkId.of(k));
            assertEquals(4, chunks.size());
            UUID worldId = last.keySet().iterator().next().world();
            for (ChunkId c : chunks) blocks.load(c, (x, y, z) -> world.getOrDefault(new Key(worldId, x, y, z), "minecraft:air"));
            int toRestore = 0;
            for (var line : last.values()) {
                String now = world.get(line.key());
                var entry = blocks.get(line.key());
                if (line.released()) assertTrue(entry.released());
                else if (now.equals(line.original())) assertNull(entry, "nothing to restore at " + line.key());
                else {
                    toRestore++;
                    assertNotNull(entry, "migrated " + line.key());
                    assertEquals(line.original(), entry.original(), "original block of " + line.key());
                    assertTrue(entry.winterBlock(now));
                }
            }
            assertTrue(toRestore > 900, "positions with winter blocks: " + toRestore);
            assertEquals(toRestore, blocks.size());
            assertTrue(last.values().stream().anyMatch(l -> l.expected().equals("minecraft:ice")), "the sample has sea ice");
            // Thaw straight back: every position returns to the original the old journal recorded first.
            for (ChunkId c : chunks) for (int i = blocks.count(c) - 1; i >= 0; i--) {
                Key k = blocks.keyAt(c, i);
                var e = blocks.get(k);
                if (e.released()) continue;
                assertNotNull(blocks.intent(k, world.get(k), e.original()));
                world.put(k, e.original());
                blocks.forget(k);
            }
            blocks.commit();
            assertEquals(0, blocks.size());
            for (var line : last.values()) if (!line.released()) assertEquals(line.original(), world.get(line.key()));
            legacy.close();
        }
    }

    @Test
    void compactionKeepsOnlyWaitingChunksInTheOldFormat() throws Exception {
        Path path = realJournal();
        long before = Files.size(path);
        Map<Key, LegacyJournal.Line> last = lastLines(path);
        LegacyJournal legacy = LegacyJournal.open(path);
        ChunkId gone = ChunkId.of(last.keySet().iterator().next());
        int left = legacy.size() - legacy.lines(gone).size();
        legacy.drop(gone);
        legacy.saveLater();
        legacy.close();
        Map<Key, LegacyJournal.Line> after = lastLines(path);
        assertEquals(left, after.size());
        for (var e : after.entrySet()) {
            assertNotEquals(gone, ChunkId.of(e.getKey()));
            LegacyJournal.Line old = last.get(e.getKey());
            assertEquals(old.original(), e.getValue().original());
            assertEquals(old.expected(), e.getValue().expected());
            assertEquals(old.history(), e.getValue().history());
        }
        assertTrue(Files.size(path) < before / 3, "one line per position instead of one per change: " + Files.size(path) + " of " + before);
        assertEquals(before, Files.size(path.resolveSibling("blocks.jsonl" + LegacyJournal.BACKUP_SUFFIX)), "backup untouched");
        assertFalse(Files.exists(path.resolveSibling("blocks.jsonl.tmp")));
        // The compacted file reads like the original, checksums included.
        LegacyJournal again = LegacyJournal.open(path);
        assertEquals(left, again.size());
        again.close();
    }

    @Test
    void torn_tail_is_ignored_and_empty_journal_is_none() throws Exception {
        Path path = dir.resolve("blocks.jsonl");
        Files.writeString(path, "");
        assertNull(LegacyJournal.open(path));
        Path real = realJournal();
        Files.writeString(real, "{\"schema\":1,\"key\":", java.nio.file.StandardOpenOption.APPEND);
        LegacyJournal legacy = LegacyJournal.open(real);
        assertEquals(1024, legacy.size());
        legacy.close();
    }

    @Test
    void checksumCorruptionFailsClosedWithoutChangingTheFile() throws Exception {
        Path path = realJournal();
        String valid = Files.readString(path);
        int at = valid.lastIndexOf("minecraft:snow");
        String corrupt = valid.substring(0, at) + "minecraft:snoe" + valid.substring(at + "minecraft:snow".length());
        Files.writeString(path, corrupt);
        assertThrows(IOException.class, () -> LegacyJournal.open(path));
        assertEquals(corrupt, Files.readString(path));
    }

    @Test
    void futureSchemaFailsClosed() throws Exception {
        Path path = dir.resolve("blocks.jsonl");
        String json = new Gson().toJson(new LegacyJournal.Line(2, new Key(UUID.randomUUID(), 1, 2, 3), "minecraft:stone", "minecraft:stone", "minecraft:snow", false, Set.of()));
        Files.writeString(path, json + '\t' + LegacyJournal.checksum(json) + '\n');
        assertThrows(IOException.class, () -> LegacyJournal.open(path));
    }
}
