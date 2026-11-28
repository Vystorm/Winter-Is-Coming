package at.vystorm.winter.world;

import static org.junit.jupiter.api.Assertions.*;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.Inflater;
import org.junit.jupiter.api.Test;

/**
 * The smooth snow cover (1.27.0). The fixtures in {@code snow-cover/} were written by the design study's Node model
 * (tools winter-snow-cover, port/fixtures.cjs) on a synthetic border strip with a house, a roof on posts and a tree, and
 * on cuts of a real village and a real mountain slope: the Java rule must give the same layers for every column.
 */
class SnowCoverTest {
    /** Codes of the study's terrain files. */
    static final int AIR = 0, PLANT = 11, NOCOLL = 20;
    static final Set<String> EXCLUDED = Set.of("desert", "badlands", "eroded_badlands", "wooded_badlands");

    /** A terrain cut of the study: block classes, biome per column; outside the box the land goes on like its edge. */
    static final class Fixture implements SnowCover.Ground {
        final String name;
        final int x0, z0, w, d, yLo, h;
        final byte[] cls;
        final String[] biome;
        final int[] top;
        final List<double[]> expected = new ArrayList<>();
        Fixture(String resource) throws Exception {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(SnowCoverTest.class.getResourceAsStream(resource), StandardCharsets.UTF_8))) {
                String[] head = r.readLine().split(" ");
                name = head[0];
                x0 = Integer.parseInt(head[1]); z0 = Integer.parseInt(head[2]); w = Integer.parseInt(head[3]); d = Integer.parseInt(head[4]);
                yLo = Integer.parseInt(head[5]); h = Integer.parseInt(head[6]);
                byte[] packed = Base64.getDecoder().decode(r.readLine());
                Inflater inf = new Inflater();
                inf.setInput(packed);
                cls = new byte[w * d * h];
                int n = 0;
                while (n < cls.length) n += inf.inflate(cls, n, cls.length - n);
                inf.end();
                String[] names = r.readLine().split(",");
                String idx = r.readLine();
                biome = new String[w * d];
                for (int i = 0; i < w * d; i++) biome[i] = names[Character.digit(idx.charAt(i), 36)];
                top = new int[w * d];
                for (int z = 0; z < d; z++) for (int x = 0; x < w; x++) {
                    int t = yLo;
                    for (int y = yLo + h - 1; y >= yLo; y--) { int c = cls[((y - yLo) * d + z) * w + x]; if (c != AIR && c != PLANT && c != NOCOLL) { t = y; break; } }
                    top[z * w + x] = t;
                }
                for (String line; (line = r.readLine()) != null; ) {
                    if (line.isBlank()) continue;
                    String[] p = line.split(" ");
                    double[] v = new double[p.length];
                    for (int i = 0; i < p.length; i++) v[i] = Double.parseDouble(p[i]);
                    expected.add(v);
                }
            }
        }
        private int cx(int x) { return Math.max(0, Math.min(w - 1, x - x0)); }
        private int cz(int z) { return Math.max(0, Math.min(d - 1, z - z0)); }
        int code(int x, int y, int z) {
            if (y < yLo) return 1;
            if (y >= yLo + h) return AIR;
            return cls[((y - yLo) * d + cz(z)) * w + cx(x)];
        }
        @Override public int height(int x, int z) { return top[cz(z) * w + cx(x)]; }
        @Override public boolean room(int x, int y, int z) { int c = code(x, y, z); return c == AIR || c == PLANT; }
        @Override public double biomeShare(int cellX, int cellZ) {
            String b = biome[cz(cellZ * 4 + 2) * w + cx(cellX * 4 + 2)];
            return EXCLUDED.contains(b) ? 0 : SnowCover.BiomeTable.DEFAULT.share(b, 0.8, 0.4);
        }
    }

    private static SnowCover.Input input(double frost, double edge) {
        return new SnowCover.Input(12, frost, 0.45, edge, true, 0);
    }

    @Test void givenTheStudysFixtures_whenTheJavaRuleRuns_thenEveryColumnHasTheModelsLayers() throws Exception {
        for (String f : new String[]{"strip", "village", "mountain"}) {
            Fixture fx = new Fixture("/snow-cover/" + f + ".txt");
            int checked = 0, wrong = 0;
            String first = null;
            for (double[] e : fx.expected) {
                int x = (int)e[1], z = (int)e[2], root = (int)e[3];
                SnowCover.Result r = SnowCover.target(fx, x, z, root, input(e[0], e[4]));
                checked++;
                if (r.layers() != (int)e[5] || r.base() != (int)e[6]) {
                    wrong++;
                    if (first == null) first = String.format("%s frost %s at %d,%d: java %d/%d, model %d/%d", f, e[0], x, z, r.layers(), r.base(), (int)e[5], (int)e[6]);
                }
            }
            assertTrue(checked > 1000, f + ": the fixture has columns");
            assertEquals(0, wrong, first);
        }
    }

    @Test void givenTheSamePosition_whenAskedTwice_thenTheAnswerIsTheSame() throws Exception {
        Fixture fx = new Fixture("/snow-cover/village.txt");
        for (double[] e : fx.expected.subList(0, 500)) {
            SnowCover.Result a = SnowCover.target(fx, (int)e[1], (int)e[2], (int)e[3], input(e[0], e[4]));
            SnowCover.Result b = SnowCover.target(fx, (int)e[1], (int)e[2], (int)e[3], input(e[0], e[4]));
            assertEquals(a, b);
        }
        assertEquals(SnowCover.hash32(17, -4, 99), SnowCover.hash32(17, -4, 99));
        assertNotEquals(SnowCover.driftField(5, 5, 1), SnowCover.driftField(5, 5, 2), "the world seed changes the drift");
    }

    @Test void givenRisingFrost_whenTheCoverIsWorkedOut_thenNoColumnEverLosesSnow() throws Exception {
        for (String f : new String[]{"strip", "village", "mountain"}) {
            Fixture fx = new Fixture("/snow-cover/" + f + ".txt");
            for (int z = fx.z0 + 8; z < fx.z0 + fx.d - 8; z++) for (int x = fx.x0 + 8; x < fx.x0 + fx.w - 8; x++) {
                int root = fx.height(x, z) + 1, prev = 0, prevBase = 0;
                for (int step = 0; step <= 120; step++) {
                    SnowCover.Result r = SnowCover.target(fx, x, z, root, input(step / 100.0, 1));
                    assertTrue(r.layers() >= prev && r.base() >= prevBase, f + " " + x + "," + z + " at " + step + "%");
                    prev = r.layers();
                    prevBase = r.base();
                }
                assertEquals(SnowCover.Result.NONE, SnowCover.target(fx, x, z, root, input(0, 1)), "no frost, no snow");
            }
        }
    }

    /** Flat ground with one straight step of one block at x = 0 (higher to the east), plains everywhere. */
    static SnowCover.Ground step(int height) {
        return new SnowCover.Ground() {
            @Override public int height(int x, int z) { return x >= 0 ? 64 + height : 64; }
            @Override public boolean room(int x, int y, int z) { return y > height(x, z); }
            @Override public double biomeShare(int cellX, int cellZ) { return 0.7; }
        };
    }

    @Test void givenAStep_whenFullWinter_thenTheSnowSurfaceRampsUpInStepsOfAtMostTwoLayers() {
        for (int stepHeight : new int[]{1, 2, 5}) {
            SnowCover.Ground g = step(stepHeight);
            for (int z = -20; z <= 20; z += 7) {
                int prev = Integer.MIN_VALUE, prevX = 0;
                for (int x = -12; x <= -1; x++) {
                    int layers = SnowCover.target(g, x, z, 65, input(1, 1)).layers();
                    int surface = 8 * 65 + layers;
                    if (prev != Integer.MIN_VALUE) assertTrue(surface - prev >= -1 && surface - prev <= 2, "rises gently towards the step: " + prevX + " -> " + x + " at z " + z);
                    prev = surface;
                    prevX = x;
                }
                int foot = SnowCover.target(g, -1, z, 65, input(1, 1)).layers(), flat = SnowCover.target(g, -12, z, 65, input(1, 1)).layers();
                assertTrue(foot >= flat + 4, "a drift at the foot of the step");
                assertTrue(foot <= 2 * flat, "never more than double");
            }
        }
    }

    @Test void givenFlatGroundOfOneBiome_whenFullWinter_thenNeighboursDifferByAtMostOneLayer() {
        SnowCover.Ground flat = step(0);
        for (int z = -60; z <= 60; z++) for (int x = -60; x <= 60; x++) {
            int a = SnowCover.target(flat, x, z, 65, input(1, 1)).layers(), b = SnowCover.target(flat, x + 1, z, 65, input(1, 1)).layers();
            assertTrue(Math.abs(a - b) <= 1, x + "," + z + ": " + a + " vs " + b);
        }
    }

    @Test void givenABiomeBorder_whenFullWinter_thenTheDepthChangesGentlyOverManyBlocks() {
        SnowCover.Ground border = new SnowCover.Ground() {
            @Override public int height(int x, int z) { return 64; }
            @Override public boolean room(int x, int y, int z) { return true; }
            @Override public double biomeShare(int cellX, int cellZ) { return cellX < 0 ? 1.0 : 0.4; }
        };
        int prev = SnowCover.target(border, -30, 0, 65, input(1, 1)).layers();
        for (int x = -29; x <= 30; x++) {
            int now = SnowCover.target(border, x, 0, 65, input(1, 1)).layers();
            assertTrue(Math.abs(now - prev) <= 1, "no step at the border: " + x);
            prev = now;
        }
        assertTrue(SnowCover.target(border, -30, 0, 65, input(1, 1)).layers() >= 11);
        assertTrue(SnowCover.target(border, 30, 0, 65, input(1, 1)).layers() <= 6);
    }

    @Test void givenARoofBesideTheColumn_whenFullWinter_thenTheOpenSnowThinsTowardsTheRoofLine() {
        // a roof 4 blocks over the ground east of x = 0, room under it
        SnowCover.Ground roof = new SnowCover.Ground() {
            @Override public int height(int x, int z) { return x >= 0 ? 68 : 64; }
            @Override public boolean room(int x, int y, int z) { return x < 0 || y < 68; }
            @Override public double biomeShare(int cellX, int cellZ) { return 1; }
        };
        assertEquals(6, SnowCover.target(roof, -1, 0, 65, input(1, 1)).layers(), "4 + 2.5 at the roof line");
        assertEquals(9, SnowCover.target(roof, -2, 0, 65, input(1, 1)).layers());
        assertTrue(SnowCover.target(roof, -8, 0, 65, input(1, 1)).layers() >= 11, "no taper beyond 5 blocks");
        // a wall instead (no room under it): a drift against it
        assertTrue(SnowCover.target(step(4), -1, 0, 65, input(1, 1)).layers() > 12 * 0.7);
    }

    @Test void givenUnknownNeighbours_whenTheirChunksAreNotLoaded_thenTheyCountAsLevelAndNothingIsRead() {
        SnowCover.Ground half = new SnowCover.Ground() {
            @Override public int height(int x, int z) { return x >= 0 ? Integer.MIN_VALUE : 64; }
            @Override public boolean room(int x, int y, int z) { if (x >= 0) throw new AssertionError("read in an unloaded chunk"); return true; }
            @Override public double biomeShare(int cellX, int cellZ) { return cellX * 4 + 2 >= 0 ? Double.NaN : 0.7; }
        };
        SnowCover.Ground level = step(0);
        assertEquals(SnowCover.target(level, -3, 0, 65, input(1, 1)).layers(), SnowCover.target(half, -3, 0, 65, input(1, 1)).layers());
    }

    @Test void givenEveryVanillaOverworldBiome_whenLookedUp_thenTheTableHasAShareAndUnknownBiomesFallBack() {
        String[] vanilla = {"badlands", "bamboo_jungle", "beach", "birch_forest", "cherry_grove", "cold_ocean", "dark_forest", "deep_cold_ocean", "deep_dark",
                "deep_frozen_ocean", "deep_lukewarm_ocean", "deep_ocean", "desert", "dripstone_caves", "eroded_badlands", "flower_forest", "forest",
                "frozen_ocean", "frozen_peaks", "frozen_river", "grove", "ice_spikes", "jagged_peaks", "jungle", "lukewarm_ocean", "lush_caves",
                "mangrove_swamp", "meadow", "mushroom_fields", "ocean", "old_growth_birch_forest", "old_growth_pine_taiga", "old_growth_spruce_taiga",
                "pale_garden", "plains", "river", "savanna", "savanna_plateau", "snowy_beach", "snowy_plains", "snowy_slopes", "snowy_taiga",
                "sparse_jungle", "stony_peaks", "stony_shore", "sunflower_plains", "swamp", "taiga", "warm_ocean", "windswept_forest",
                "windswept_gravelly_hills", "windswept_hills", "windswept_savanna", "wooded_badlands"};
        SnowCover.BiomeTable t = SnowCover.BiomeTable.DEFAULT;
        for (String b : vanilla) {
            assertTrue(t.known(b), b);
            double v = t.share(b, 0.5, 0.5);
            assertTrue(v >= 0 && v <= 1, b);
        }
        assertTrue(t.share("snowy_slopes", 0, 0) > t.share("taiga", 0, 0));
        assertTrue(t.share("taiga", 0, 0) > t.share("plains", 0, 0));
        assertTrue(t.share("plains", 0, 0) > t.share("beach", 0, 0));
        assertTrue(t.share("beach", 0, 0) > t.share("savanna", 0, 0));
        assertEquals(0, t.share("desert", 2, 0));
        // unknown (data pack) biomes: by temperature, no rain = a dusting
        assertFalse(t.known("alpine_tundra"));
        assertEquals(1.0, t.share("alpine_tundra", -0.5, 0.5), 1e-9);
        assertEquals(0.69, t.share("warm_meadow", 0.8, 0.4), 1e-9);
        assertEquals(0.2, t.share("red_dunes", 2.0, 0), 1e-9);
    }

    @Test void givenTheAdminsKeys_whenLookedUp_thenExactNamesBeatPatternsAndPatternsBeatTheTable() {
        SnowCover.BiomeTable t = new SnowCover.BiomeTable(Map.of("plains", 0.3, "*_taiga", 0.6, "old_growth_*_taiga", 0.9, "*", 0.1));
        assertEquals(0.3, t.share("plains", 0.8, 0.4));
        assertEquals(0.6, t.share("snowy_taiga", 0, 0.4));
        assertEquals(0.9, t.share("old_growth_pine_taiga", 0.3, 0.8), "the longer pattern wins");
        assertEquals(0.1, t.share("swamp", 0.8, 0.9), "the catch-all pattern beats the built-in table");
        assertTrue(SnowCover.BiomeTable.matches("*ocean*", "deep_cold_ocean"));
        assertFalse(SnowCover.BiomeTable.matches("*_taiga", "taiga"));
        assertThrows(IllegalArgumentException.class, () -> new SnowCover.BiomeTable(Map.of("plains", 1.5)));
        assertThrows(IllegalArgumentException.class, () -> new SnowCover.BiomeTable(Map.of("plains", -0.1)));
    }

    @Test void givenTheModeKey_whenParsed_thenSmoothAndClassicAreAccepted() {
        assertEquals(SnowCover.Mode.SMOOTH, SnowCover.Mode.parse(null));
        assertEquals(SnowCover.Mode.SMOOTH, SnowCover.Mode.parse(" Smooth "));
        assertEquals(SnowCover.Mode.CLASSIC, SnowCover.Mode.parse("classic"));
        assertThrows(IllegalArgumentException.class, () -> SnowCover.Mode.parse("soft"));
    }

    @Test void givenLaterSeasonInputs_whenLocalWinterOrAZoneChange_thenTheDepthFollowsThem() {
        SnowCover.Ground flat = step(0);
        int full = SnowCover.target(flat, 3, 3, 65, input(1, 1)).layers();
        int half = SnowCover.target(flat, 3, 3, 65, new SnowCover.Input(12, 1, 0.45, 1, true, 0, 0.5, 1)).layers();
        int zone = SnowCover.target(flat, 3, 3, 65, new SnowCover.Input(12, 1, 0.45, 1, true, 0, 1, 1.4)).layers();
        assertTrue(half < full && zone > full, full + " / " + half + " / " + zone);
    }
}
