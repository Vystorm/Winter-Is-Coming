package at.vystorm.winter.storage;

import static org.junit.jupiter.api.Assertions.*;

import at.vystorm.winter.storage.TrackedBlocks.ChunkId;
import at.vystorm.winter.storage.TrackedBlocks.Key;
import java.util.*;
import org.junit.jupiter.api.Test;

/** The per-chunk records of 1.24.2: format, counting, release, restore and the limits of what one record stores. */
class TrackedBlocksTest {
    static final UUID WORLD = UUID.fromString("00000000-0000-0000-0000-000000000001");

    /** A chunk's persistent data: a map of byte arrays, copied in and out like the server does. */
    static final class MapStorage implements TrackedBlocks.Storage {
        final Map<ChunkId, byte[]> data = new HashMap<>();
        int writes;
        @Override public byte[] read(ChunkId chunk) {
            byte[] b = data.get(chunk);
            return b == null ? null : b.clone();
        }
        @Override public void write(ChunkId chunk, byte[] bytes) {
            writes++;
            if (bytes == null) data.remove(chunk);
            else data.put(chunk, bytes.clone());
        }
        MapStorage copy() {
            MapStorage c = new MapStorage();
            for (var e : data.entrySet()) c.data.put(e.getKey(), e.getValue().clone());
            return c;
        }
    }

    static Key key(int x, int y, int z) {
        return new Key(WORLD, x, y, z);
    }
    static ChunkId chunk(int cx, int cz) {
        return new ChunkId(WORLD, cx, cz);
    }
    static TrackedBlocks store(MapStorage storage) {
        return new TrackedBlocks(storage, null, UUID.randomUUID(), null);
    }
    static String snow(int layers) {
        return "minecraft:snow[layers=" + layers + "]";
    }

    @Test
    void recordsSurviveUnloadAndLoadExactly() {
        MapStorage storage = new MapStorage();
        TrackedBlocks blocks = store(storage);
        Random random = new Random(7);
        Map<Key, TrackedBlocks.Entry> expected = new HashMap<>();
        for (int cx = -2; cx <= 1; cx++) for (int cz = -1; cz <= 1; cz++) blocks.load(chunk(cx, cz), (x, y, z) -> "minecraft:air");
        for (int i = 0; i < 3000; i++) {
            Key k = key(random.nextInt(64) - 32, random.nextInt(400) - 64, random.nextInt(48) - 16);
            if (expected.containsKey(k)) continue;
            String original = random.nextBoolean() ? "minecraft:air" : "minecraft:short_grass";
            var first = blocks.intent(k, original, snow(1));
            assertNotNull(first);
            var second = random.nextBoolean() ? blocks.intent(k, snow(1), snow(2)) : first;
            assertNotNull(second);
            expected.put(k, second);
        }
        int released = 0;
        for (Key k : List.copyOf(expected.keySet())) if (random.nextInt(10) == 0) {
            blocks.release(k);
            expected.put(k, blocks.get(k));
            released++;
        }
        assertEquals(expected.size() - released, blocks.size());
        assertEquals(expected.size(), blocks.held());
        for (int cx = -2; cx <= 1; cx++) for (int cz = -1; cz <= 1; cz++) blocks.unload(chunk(cx, cz));
        assertEquals(0, blocks.size());
        assertEquals(0, blocks.held());
        TrackedBlocks again = store(storage);
        for (int cx = -2; cx <= 1; cx++) for (int cz = -1; cz <= 1; cz++) again.load(chunk(cx, cz), (x, y, z) -> "minecraft:air");
        assertEquals(expected.size() - released, again.size());
        for (var e : expected.entrySet()) assertEquals(e.getValue(), again.get(e.getKey()), "entry at " + e.getKey());
    }

    @Test
    void restoredPositionsLeaveMemoryCountAndStorage() {
        MapStorage storage = new MapStorage();
        TrackedBlocks blocks = store(storage);
        blocks.load(chunk(0, 0), (x, y, z) -> "minecraft:air");
        for (int x = 0; x < 16; x++) blocks.intent(key(x, 65, 3), "minecraft:air", snow(1));
        assertEquals(16, blocks.size());
        assertTrue(storage.data.containsKey(chunk(0, 0)));
        for (int x = 0; x < 16; x++) {
            assertNotNull(blocks.intent(key(x, 65, 3), snow(1), "minecraft:air"));
            blocks.forget(key(x, 65, 3));
            blocks.commit();
            assertEquals(15 - x, blocks.size(), "the count goes down as the thaw proceeds");
        }
        assertNull(blocks.get(key(3, 65, 3)));
        assertFalse(storage.data.containsKey(chunk(0, 0)), "an empty chunk keeps no record");
        // The next winter starts from the original block again.
        assertNotNull(blocks.intent(key(3, 65, 3), "minecraft:air", snow(1)));
        assertEquals("minecraft:air", blocks.get(key(3, 65, 3)).original());
    }

    @Test
    void releasedPositionsAreNeverTakenAgainAndAreNotCounted() {
        MapStorage storage = new MapStorage();
        TrackedBlocks blocks = store(storage);
        blocks.load(chunk(0, 0), (x, y, z) -> "minecraft:air");
        Key k = key(1, 64, 2);
        blocks.intent(k, "minecraft:stone", snow(1));
        blocks.release(k);
        assertEquals(0, blocks.size());
        assertEquals(1, blocks.held());
        assertNull(blocks.intent(k, snow(1), snow(2)), "a player's snow is not the plugin's");
        assertNull(blocks.intent(k, "minecraft:stone", snow(1)));
        blocks.forget(k);
        assertTrue(blocks.get(k).released(), "restoring never removes a released marker");
        blocks.unload(chunk(0, 0));
        TrackedBlocks again = store(storage);
        again.load(chunk(0, 0), (x, y, z) -> "minecraft:air");
        assertTrue(again.get(k).released());
        assertFalse(again.get(k).winterBlock(snow(1)));
    }

    @Test
    void refusesChangesOfBlocksThePluginDidNotMake() {
        TrackedBlocks blocks = store(new MapStorage());
        blocks.load(chunk(0, 0), (x, y, z) -> "minecraft:air");
        Key k = key(1, 64, 2);
        blocks.intent(k, "minecraft:air", snow(1));
        assertNull(blocks.intent(k, "minecraft:dirt", snow(2)));
        assertNull(blocks.intent(key(20, 64, 2), "minecraft:air", snow(1)), "chunk not loaded");
        assertEquals(1, blocks.size());
    }

    @Test
    void entryNeverGrowsWithTheLayers() {
        MapStorage storage = new MapStorage();
        TrackedBlocks blocks = store(storage);
        blocks.load(chunk(0, 0), (x, y, z) -> "minecraft:air");
        Key k = key(4, 70, 4);
        String current = "minecraft:short_grass";
        int sizeAfterFirst = -1;
        for (int round = 0; round < 3; round++) {
            for (int layer = 1; layer <= 8; layer++) {
                assertNotNull(blocks.intent(k, current, snow(layer)));
                current = snow(layer);
                int bytes = storage.data.get(chunk(0, 0)).length;
                if (sizeAfterFirst < 0) sizeAfterFirst = bytes;
                // previous != expected while a change waits: one index more, and the palette holds three states at most
                assertTrue(bytes <= sizeAfterFirst + 32, "record grew to " + bytes + " bytes");
            }
            for (int layer = 7; layer >= 1; layer--) {
                assertNotNull(blocks.intent(k, current, snow(layer)));
                current = snow(layer);
            }
        }
        var e = blocks.get(k);
        assertEquals("minecraft:short_grass", e.original());
        assertTrue(e.accepts(snow(1)));
        assertFalse(e.accepts(snow(5)), "only the last change is remembered, nothing older");
    }

    @Test
    void storedBytesPerPositionAreSmall() {
        MapStorage storage = new MapStorage();
        TrackedBlocks blocks = store(storage);
        blocks.load(chunk(0, 0), (x, y, z) -> "minecraft:air");
        // A full chunk at the default 12 layers: one full block and one block with 4 layers on every column.
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) {
            blocks.intent(key(x, -60, z), "minecraft:air", snow(8));
            blocks.intent(key(x, -60, z), snow(8), snow(8));
            blocks.intent(key(x, -59, z), "minecraft:air", snow(4));
            blocks.intent(key(x, -59, z), snow(4), snow(4));
        }
        int bytes = storage.data.get(chunk(0, 0)).length;
        assertEquals(512, blocks.size());
        assertTrue(bytes < 512 * 6, bytes + " bytes for 512 positions");
    }

    @Test
    void corruptRecordLeavesTheChunkAloneAndUntouched() {
        MapStorage storage = new MapStorage();
        TrackedBlocks blocks = store(storage);
        blocks.load(chunk(0, 0), (x, y, z) -> "minecraft:air");
        blocks.intent(key(1, 64, 1), "minecraft:air", snow(1));
        blocks.unload(chunk(0, 0));
        byte[] stored = storage.data.get(chunk(0, 0));
        stored[stored.length / 2] ^= 0x40;
        byte[] corrupt = stored.clone();
        List<String> warnings = new ArrayList<>();
        TrackedBlocks again = new TrackedBlocks(storage, null, UUID.randomUUID(), warnings::add);
        again.load(chunk(0, 0), (x, y, z) -> "minecraft:air");
        assertEquals(1, warnings.size());
        assertNull(again.get(key(1, 64, 1)));
        assertNull(again.intent(key(2, 64, 1), "minecraft:air", snow(1)), "no edits in a chunk whose record is unreadable");
        again.unload(chunk(0, 0));
        assertArrayEquals(corrupt, storage.data.get(chunk(0, 0)), "the stored bytes are kept for repair");
    }

    @Test
    void futureFormatIsRefused() {
        MapStorage storage = new MapStorage();
        TrackedBlocks blocks = store(storage);
        blocks.load(chunk(0, 0), (x, y, z) -> "minecraft:air");
        blocks.intent(key(1, 64, 1), "minecraft:air", snow(1));
        byte[] stored = storage.data.get(chunk(0, 0)).clone();
        stored[0] = 2;
        // fix the checksum so only the version differs
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        crc.update(stored, 0, stored.length - 4);
        int c = (int)crc.getValue();
        stored[stored.length - 4] = (byte)(c >>> 24);
        stored[stored.length - 3] = (byte)(c >>> 16);
        stored[stored.length - 2] = (byte)(c >>> 8);
        stored[stored.length - 1] = (byte)c;
        storage.data.put(chunk(0, 0), stored);
        List<String> warnings = new ArrayList<>();
        TrackedBlocks again = new TrackedBlocks(storage, null, UUID.randomUUID(), warnings::add);
        again.load(chunk(0, 0), (x, y, z) -> "minecraft:air");
        assertTrue(warnings.get(0).contains("unsupported record format 2"), warnings.toString());
    }

    @Test
    void negativeCoordinatesAndHeightsMapToTheRightChunk() {
        MapStorage storage = new MapStorage();
        TrackedBlocks blocks = store(storage);
        blocks.load(chunk(-1, -1), (x, y, z) -> "minecraft:air");
        Key k = key(-1, -64, -16);
        assertNotNull(blocks.intent(k, "minecraft:air", snow(1)));
        assertEquals(k, blocks.keyAt(chunk(-1, -1), 0));
        Key high = key(-16, 319, -1);
        assertNotNull(blocks.intent(high, "minecraft:air", snow(1)));
        blocks.unload(chunk(-1, -1));
        TrackedBlocks again = store(storage);
        again.load(chunk(-1, -1), (x, y, z) -> "minecraft:air");
        assertNotNull(again.get(k));
        assertNotNull(again.get(high));
        assertEquals(2, again.count(chunk(-1, -1)));
    }
}
