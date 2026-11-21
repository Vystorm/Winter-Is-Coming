package at.vystorm.winter.world;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The server adapter of wind-blown snow over an in-memory source: heights, own snow, plants, support, caching. */
class ShelterViewTest {
    /** Blocks by position; everything else is air above y = 0 and stone at y <= 0. */
    private static final class Fake implements ShelterView.Source {
        final Map<String, String> blocks = new HashMap<>();
        final Set<String> own = new HashSet<>(), noSnow = new HashSet<>();
        final Set<String> unloadedChunks = new HashSet<>();
        int materialReads;
        void put(int x, int y, int z, String m) { blocks.put(x + "," + y + "," + z, m); }
        public boolean chunkLoaded(int cx, int cz) { return !unloadedChunks.contains(cx + "," + cz); }
        public int heightmap(int x, int z) {
            for (int y = 40; y > -64; y--) {
                String m = blocks.getOrDefault(x + "," + y + "," + z, y <= 0 ? "STONE" : "AIR");
                // the server's motion-blocking heightmap: neither air nor small plants
                if (!m.equals("AIR") && !Shelter.SMALL_PLANTS.contains(m)) return y;
            }
            return -64;
        }
        public String material(int x, int y, int z) {
            materialReads++;
            return blocks.getOrDefault(x + "," + y + "," + z, y <= 0 ? "STONE" : "AIR");
        }
        public boolean own(int x, int y, int z) { return own.contains(x + "," + y + "," + z); }
        public boolean takesSnow(int x, int y, int z) { return !noSnow.contains(x + "," + y + "," + z) && !material(x, y, z).equals("AIR"); }
        public int minY() { return -64; }
        final Map<String, Integer> layers = new HashMap<>();
        public int layers(int x, int y, int z) { return layers.getOrDefault(x + "," + y + "," + z, 8); }
    }

    @Test void givenSnowBlocks_whenTheirLayersAreRead_thenOnlyThePluginsOwnSnowCounts() {
        Fake f = new Fake();
        f.put(0, 1, 0, "SNOW"); f.own.add("0,1,0"); f.layers.put("0,1,0", 3);
        f.put(1, 1, 0, "SNOW"); f.layers.put("1,1,0", 5);   // vanilla snow
        f.put(2, 1, 0, "OAK_DOOR"); f.own.add("2,1,0");
        ShelterView v = new ShelterView(f, true, true);
        assertEquals(3, v.snowLayers(0, 1, 0));
        assertEquals(0, v.snowLayers(1, 1, 0), "snow that is not the plugin's");
        assertEquals(0, v.snowLayers(2, 1, 0), "no snow block");
        assertEquals(0, v.snowLayers(3, 1, 0), "air");
    }

    @Test void givenOwnSnowOnOpenGround_whenTheHeightIsRead_thenItIsTheGroundBelow() {
        Fake f = new Fake();
        f.put(0, 1, 0, "SNOW"); f.put(0, 2, 0, "SNOW");
        f.own.add("0,1,0"); f.own.add("0,2,0");
        f.put(1, 1, 1, "SNOW");   // vanilla snow: not the plugin's
        ShelterView v = new ShelterView(f, true, true);
        assertEquals(2, v.rawHeight(0, 0));
        assertEquals(0, v.height(0, 0));
        assertEquals(1, v.height(1, 1));
        assertFalse(v.blocks(0, 1, 0), "own snow lets rays through");
        assertTrue(v.blocks(1, 1, 1), "other snow stops them");
    }

    @Test void givenCells_whenAskedForSurfaces_thenAirPlantsAndOwnSnowOnSupportCount() {
        Fake f = new Fake();
        f.put(0, 1, 0, "SHORT_GRASS");
        f.put(1, 1, 0, "SNOW"); f.own.add("1,1,0");
        f.put(2, 1, 0, "SNOW");                            // not the plugin's
        f.put(3, 1, 0, "SNOW"); f.own.add("3,1,0");        // own snow on top of it: never a second sheltered layer
        f.put(3, 2, 0, "AIR");
        f.noSnow.add("4,0,0");
        ShelterView plants = new ShelterView(f, true, true), noPlants = new ShelterView(f, true, false);
        assertTrue(plants.surface(0, 1, 0));
        assertFalse(noPlants.surface(0, 1, 0), "snow.cover-small-plants: false");
        assertTrue(plants.surface(1, 1, 0));
        assertFalse(plants.surface(2, 1, 0));
        assertFalse(plants.surface(3, 2, 0), "never on snow");
        assertFalse(plants.surface(4, 1, 0), "ground that takes no snow");
        assertTrue(plants.surface(5, 1, 0), "air on stone");
        assertFalse(plants.surface(5, 0, 0), "inside stone");
        assertTrue(plants.room(0, 1, 0));
        assertFalse(plants.room(5, 0, 0));
    }

    @Test void givenLeaves_whenTheSwitchSaysPass_thenRaysGoThrough() {
        Fake f = new Fake();
        f.put(0, 3, 0, "OAK_LEAVES");
        assertTrue(new ShelterView(f, true, true).blocks(0, 3, 0));
        assertFalse(new ShelterView(f, false, true).blocks(0, 3, 0));
    }

    @Test void givenAnUnloadedChunk_whenAsked_thenItIsNotLoaded() {
        Fake f = new Fake();
        f.unloadedChunks.add("-1,0");
        ShelterView v = new ShelterView(f, true, true);
        assertFalse(v.loaded(-1, 5));
        assertTrue(v.loaded(0, 5));
        assertTrue(v.loaded(15, 15));
        assertFalse(v.loaded(-16, 0));
    }

    @Test void givenOneEvaluation_whenTheSameBlocksAreRead_thenTheyAreReadOnceUntilReset() {
        Fake f = new Fake();
        ShelterView v = new ShelterView(f, true, true);
        for (int i = 0; i < 8; i++) { v.blocks(3, 2, 3); v.height(3, 3); }
        int reads = f.materialReads;
        assertTrue(reads <= 3, "reads " + reads);
        f.put(3, 2, 3, "STONE");
        assertFalse(v.blocks(3, 2, 3), "cached within one evaluation");
        v.reset();
        assertTrue(v.blocks(3, 2, 3), "read again after a reset");
        assertTrue(v.reads() > 0);
    }

    @Test void givenARoofOverOpenSnow_whenTheConeIsTraced_thenTheAdapterAgreesWithTheModel() {
        // a 9x9 roof of clearance 3 on the plain stone world, wind from the west: the edge column gets its rays
        Fake f = new Fake();
        for (int x = 0; x < 9; x++) for (int z = 0; z < 9; z++) f.put(x, 4, z, "OAK_PLANKS");
        for (int x = -10; x < 0; x++) for (int z = -5; z < 14; z++) { f.put(x, 1, z, "SNOW"); f.own.add(x + ",1," + z); }
        ShelterView v = new ShelterView(f, true, true);
        WindRay.Wind wind = new WindRay.Wind(20, 270, 12);
        assertTrue(Shelter.candidate(v, 0, 4, wind));
        assertEquals(java.util.List.of(1), Shelter.surfaces(v, 0, 4, 12));
        assertEquals(8, WindRay.free(v, 0, 1, 4, wind));
        assertEquals(0, WindRay.free(v, 2, 1, 4, wind));
    }
}
