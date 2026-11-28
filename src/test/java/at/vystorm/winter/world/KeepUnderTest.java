package at.vystorm.winter.world;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import at.vystorm.winter.WinterPlugin;
import at.vystorm.winter.storage.TrackedBlocks;
import at.vystorm.winter.storage.TrackedBlocks.ChunkId;
import at.vystorm.winter.storage.TrackedBlocks.Key;
import java.util.*;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.junit.jupiter.api.Test;

/**
 * A block placed on the plugin's snow (1.28.0, {@code build-mode.under-blocks.enabled}). Before: the snow under it
 * stayed tracked, the thaw took it away and the block was left floating one block above the ground. Now the snow (or
 * one ice block) right under a placed block is given up at the place event, so the thaw keeps it and the block stays
 * where the builder put it. The world is a map of block texts; the thaw follows the reconcile rules of
 * {@link WinterTerrain} as in {@link WinterEditedTest}.
 */
class KeepUnderTest {
    static final UUID WORLD = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
    static final String AIR = "minecraft:air", STONE = "minecraft:stone", PLANKS = "minecraft:oak_planks", WATER = "minecraft:water[level=0]", ICE = "minecraft:ice";
    static String snow(int layers) { return "minecraft:snow[layers=" + layers + "]"; }

    final Map<Key, String> blocks = new HashMap<>();
    final WinterEditedTest.MapStorage storage = new WinterEditedTest.MapStorage();
    final TrackedBlocks journal = new TrackedBlocks(storage, null, UUID.randomUUID(), m -> { });
    final World world = mock(World.class);
    final WinterTerrain terrain = new WinterTerrain(mock(WinterPlugin.class), journal, null, null);
    {
        when(world.getUID()).thenReturn(WORLD);
        when(world.getMinHeight()).thenReturn(-64);
        when(world.getMaxHeight()).thenReturn(320);
        journal.load(new ChunkId(WORLD, 0, 0), (x, y, z) -> at(key(x, y, z)));
    }

    static Key key(int x, int y, int z) { return new Key(WORLD, x, y, z); }
    String at(Key k) { return blocks.getOrDefault(k, AIR); }

    static Material material(String text) {
        if (text.startsWith("minecraft:snow[")) return Material.SNOW;
        return switch (text) {
            case ICE -> Material.ICE;
            case STONE -> Material.STONE;
            case PLANKS -> Material.OAK_PLANKS;
            case WATER -> Material.WATER;
            default -> Material.AIR;
        };
    }

    /** A block of the map world: type and data are read when asked, so later changes show. */
    Block block(int x, int y, int z) {
        Block b = mock(Block.class);
        Key k = key(x, y, z);
        when(b.getWorld()).thenReturn(world);
        when(b.getX()).thenReturn(x);
        when(b.getY()).thenReturn(y);
        when(b.getZ()).thenReturn(z);
        when(b.getType()).thenAnswer(i -> material(at(k)));
        when(b.getBlockData()).thenAnswer(i -> {
            BlockData d = mock(BlockData.class);
            when(d.getAsString()).thenReturn(at(k));
            return d;
        });
        when(b.getRelative(any(BlockFace.class))).thenAnswer(i -> {
            BlockFace f = i.getArgument(0);
            return block(x + f.getModX(), y + f.getModY(), z + f.getModZ());
        });
        return b;
    }

    void winter(Key k, String next) {
        assertNotNull(journal.intent(k, at(k), next));
        blocks.put(k, next);
    }

    /** Every tracked position gets its original back unless it was given up (a full thaw). */
    void thaw() {
        for (ChunkId c : journal.chunksWithRecords()) {
            List<Key> keys = new ArrayList<>();
            for (int i = 0; i < journal.count(c); i++) keys.add(journal.keyAt(c, i));
            for (Key k : keys) {
                TrackedBlocks.Entry e = journal.get(k);
                if (e == null || e.released()) continue;
                if (!e.accepts(at(k))) { journal.release(k); continue; }
                blocks.put(k, e.original());
                journal.forget(k);
            }
        }
        journal.commit();
    }

    /** Ground at y 64, the plugin's snow of 12 layers (8 + 4) at 65 and 66, a builder's plank on top at 67. */
    void deepSnowAndAPlank() {
        blocks.put(key(3, 64, 3), STONE);
        winter(key(3, 65, 3), snow(8));
        winter(key(3, 66, 3), snow(4));
        blocks.put(key(3, 67, 3), PLANKS);
    }

    @Test void before_thePlankFloatsAfterTheThaw() {
        deepSnowAndAPlank();
        thaw();
        assertEquals(PLANKS, at(key(3, 67, 3)));
        assertEquals(AIR, at(key(3, 66, 3)), "the old behaviour: two blocks of air under the plank");
        assertEquals(AIR, at(key(3, 65, 3)));
    }

    @Test void givenAPlankPlacedOnThePluginsSnow_whenKeptUnder_thenTheThawKeepsTheSnowAndThePlankStays() {
        deepSnowAndAPlank();
        assertEquals(2, terrain.keepUnder(block(3, 67, 3)));
        assertTrue(journal.get(key(3, 66, 3)).released());
        assertTrue(journal.get(key(3, 65, 3)).released());
        thaw();
        assertEquals(PLANKS, at(key(3, 67, 3)), "the plank is where the builder put it");
        assertEquals(snow(4), at(key(3, 66, 3)), "it still stands on the snow");
        assertEquals(snow(8), at(key(3, 65, 3)));
        assertNull(journal.intent(key(3, 66, 3), snow(4), snow(5)), "the plugin never changes that snow again (no new layers either)");
    }

    @Test void givenABlockOnIce_whenKeptUnder_thenOnlyThatOneIceBlockStays() {
        blocks.put(key(5, 62, 5), WATER);
        winter(key(5, 63, 5), ICE);
        winter(key(6, 63, 5), ICE);
        blocks.put(key(5, 64, 5), PLANKS);
        assertEquals(1, terrain.keepUnder(block(5, 64, 5)));
        thaw();
        assertEquals(ICE, at(key(5, 63, 5)), "the ice under the plank stays");
        assertEquals(AIR, at(key(6, 63, 5)), "the rest of the ice melts as before (original air in this model)");
    }

    @Test void givenSnowAround_whenABlockIsPlacedNextToItOrOnIt_thenNoNeighbourChangesOrIsGivenUp() {
        for (int x = 2; x <= 4; x++) for (int z = 2; z <= 4; z++) {
            blocks.put(key(x, 64, z), STONE);
            winter(key(x, 65, z), snow(3));
        }
        Map<Key, String> before = new HashMap<>(blocks);
        // beside the snow: a plank on the stone at 5/65/3, next to the snow at 4/65/3
        blocks.put(key(5, 64, 3), STONE);
        blocks.put(key(5, 65, 3), PLANKS);
        assertEquals(0, terrain.keepUnder(block(5, 65, 3)));
        // on the snow in the middle: only the snow under it is given up
        blocks.put(key(3, 66, 3), PLANKS);
        assertEquals(1, terrain.keepUnder(block(3, 66, 3)));
        for (int x = 2; x <= 4; x++) for (int z = 2; z <= 4; z++) {
            Key k = key(x, 65, z);
            assertEquals(before.get(k), at(k), "the world is never changed by the rule " + k);
            if (x == 3 && z == 3) assertTrue(journal.get(k).released(), "under the plank");
            else assertFalse(journal.get(k).released(), "a neighbour stays the plugin's snow " + k);
        }
        thaw();
        assertEquals(snow(3), at(key(3, 65, 3)));
        assertEquals(AIR, at(key(4, 65, 3)), "the neighbours melt with the normal thaw, not at the place event");
    }

    @Test void givenABlockOnGroundOrNaturalSnow_whenPlaced_thenNothingIsGivenUp() {
        blocks.put(key(1, 64, 1), STONE);
        blocks.put(key(1, 65, 1), PLANKS);
        assertEquals(0, terrain.keepUnder(block(1, 65, 1)));
        blocks.put(key(2, 64, 2), snow(3));   // vanilla snow, not the plugin's
        blocks.put(key(2, 65, 2), PLANKS);
        assertEquals(0, terrain.keepUnder(block(2, 65, 2)));
        assertTrue(journal.chunksWithRecords().isEmpty());
    }
}
