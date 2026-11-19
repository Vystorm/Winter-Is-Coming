package at.vystorm.winter.world;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/** 1.24.1: early winter starts as scattered thin snow on open ground; ramps and covered plants follow the base depth. */
class EarlySnowTest {
    private static final double[] FROST = {0.02, 0.08, 0.20, 0.50, 1.00};
    private static final int MAX = 12;
    private static final double DRIFT = 0.45;

    /** The flat target of 1.24.0, kept here to show what full winter looked like. */
    private static int oldFlat(double intensity, int x, int z, double drift) {
        if (intensity <= 0) return 0;
        double field = 0.75 * noiseOf(x, z, 12) + 0.25 * noiseOf(x, z, 5);
        double depth = 1 - drift * (1 - field);
        return Math.max(0, Math.min(MAX, (int) Math.ceil(MAX * Math.min(1, intensity) * depth)));
    }
    private static double noiseOf(int x, int z, int size) {
        return SnowDepth.noise(x, z, size);
    }

    private static double share(double intensity, double drift) {
        int covered = 0, all = 0;
        for (int x = -60; x < 60; x++) for (int z = -60; z < 60; z++, all++) if (SnowDepth.target(MAX, intensity, x, z, drift, 1) > 0) covered++;
        return covered / (double) all;
    }

    /** The rule of the first 1.24.1 draft: scattered first layer from exact depth 1, no growing cover. */
    private static int previousRule(double intensity, int x, int z, double drift, double edge) {
        if (intensity <= 0 || edge <= 0) return 0;
        double field = 0.75 * SnowDepth.noise(x, z, 12) + 0.25 * SnowDepth.noise(x, z, 5);
        double depth = 1 - drift * (1 - field);
        return Math.max(0, Math.min(MAX, SnowDepth.layersFor(MAX * Math.min(1, intensity) * depth * edge, x, z)));
    }

    @Test void coverGrowsOverTheFirstThirdOfTheSeason() {
        double[][] shares = {{0.02, 0.009}, {0.05, 0.055}, {0.08, 0.133}, {0.15, 0.394}, {0.25, 0.802}, {0.35, 1}, {1.0, 1}};
        for (double[] s : shares) {
            assertEquals(s[1], SnowDepth.coverage(s[0]), 0.002, "smoothstep at " + s[0]);
            assertEquals(s[1], share(s[0], 0), 0.02, "flat columns covered at " + s[0]);
        }
        assertEquals(0, SnowDepth.coverage(0));
        // drifts thin the cover while it is small, never thicken it
        for (double i : new double[]{0.02, 0.05, 0.08, 0.15, 0.25}) assertTrue(share(i, DRIFT) <= share(i, 0) + 0.005);
        assertEquals(1, share(0.35, DRIFT), 1e-9);
        for (int x = -60; x < 60; x++) for (int z = -60; z < 60; z++) {
            assertTrue(SnowDepth.target(MAX, 0.08, x, z, 0, 1) <= 1, "8 %: one layer where covered");
            assertEquals(6, SnowDepth.target(MAX, 0.50, x, z, 0, 1));
            assertEquals(12, SnowDepth.target(MAX, 1.00, x, z, 0, 1));
        }
    }

    @Test void coverOnlyGrowsAndMatchesThePreviousRuleFromFullCoverOn() {
        for (int x = -50; x < 50; x++) for (int z = -50; z < 50; z++) {
            boolean covered = false;
            for (int step = 1; step <= 100; step++) {
                double i = step / 100.0;
                int t = SnowDepth.target(MAX, i, x, z, DRIFT, 1), previous = previousRule(i, x, z, DRIFT, 1);
                assertTrue(t > 0 || !covered, "a covered column stays covered: " + x + "/" + z + " at " + i);
                covered = t > 0;
                assertTrue(t <= previous, "never deeper than the previous rule");
                if (i >= SnowDepth.FULL_COVER) {
                    assertEquals(previous, t, "identical from 35 % on at " + i);
                    for (double edge : new double[]{0.2, 0.6}) assertEquals(previousRule(i, x, z, DRIFT, edge), SnowDepth.target(MAX, i, x, z, DRIFT, edge));
                }
            }
        }
    }

    @Test void leavesRoofsRampsAndPlantsNeverRunAhead() {
        // leaves, roofs (stairs, slabs) and plants use the target of their own column; ramps add only on covered columns
        for (double i : new double[]{0.02, 0.05, 0.08, 0.15, 0.25}) {
            int ramps = 0, all = 0, flat = 0;
            for (int x = -60; x < 60; x++) for (int z = -60; z < 60; z++, all++) {
                int base = SnowDepth.target(MAX, i, x, z, DRIFT, 1);
                if (base > 0) flat++;
                for (int d = 1; d <= 3; d++) assertEquals(base > 0, SnowDepth.withRamp(base, d, i) > 0);
                if (SnowDepth.withRamp(base, 1, i) > 0) ramps++;
                assertEquals(base > 0, SnowDepth.coversPlant(base));
            }
            assertEquals(flat, ramps, "ramp cells covered exactly where the base is");
            assertTrue(flat / (double) all <= SnowDepth.coverage(i) + 0.02, "never ahead of the flat share at " + i);
        }
    }

    @Test void firstLayerIsScatteredNotInRows() {
        // covered columns at 15 % frost: neighbours along x and z are about as often covered as random columns
        int pairsX = 0, pairsZ = 0, covered = 0, n = 64;
        boolean[][] c = new boolean[n][n];
        for (int x = 0; x < n; x++) for (int z = 0; z < n; z++) if (c[x][z] = SnowDepth.target(MAX, 0.15, x, z, 0, 1) > 0) covered++;
        for (int x = 0; x < n - 1; x++) for (int z = 0; z < n; z++) { if (c[x][z] && c[x + 1][z]) pairsX++; if (c[z][x] && c[z][x + 1]) pairsZ++; }
        double p = covered / (double) (n * n), expected = p * p * n * (n - 1);
        assertTrue(pairsX < expected * 1.3 && pairsZ < expected * 1.3, "no runs: " + pairsX + "/" + pairsZ + " vs " + expected);
    }

    @Test void targetsGrowMonotonicallyAndFullWinterIsUnchanged() {
        for (int x = -40; x < 40; x++) for (int z = -40; z < 40; z++) {
            int previous = 0;
            for (int step = 0; step <= 200; step++) {
                double i = step / 200.0;
                int t = SnowDepth.target(MAX, i, x, z, DRIFT, 1);
                assertTrue(t >= previous, "monotonic at " + x + "/" + z + " " + i);
                previous = t;
                for (int d = 0; d <= 3; d++) {
                    int ramp = SnowDepth.withRamp(t, d, i);
                    assertTrue(ramp >= t);
                    if (step > 0) assertTrue(ramp >= SnowDepth.withRamp(SnowDepth.target(MAX, (step - 1) / 200.0, x, z, DRIFT, 1), d, (step - 1) / 200.0), "ramp grows");
                }
            }
            assertEquals(oldFlat(1, x, z, DRIFT), SnowDepth.target(MAX, 1, x, z, DRIFT, 1), "full winter as in 1.24.0");
            for (int d = 1; d <= 3; d++) {
                int base = SnowDepth.target(MAX, 1, x, z, DRIFT, 1);
                assertEquals(base + 8 - 2 * d, SnowDepth.withRamp(base, d, 1), "full winter ramp as in 1.24.0");
            }
        }
    }

    @Test void rampNeverExceedsTheRule() {
        for (double i : FROST) for (int base = 0; base <= MAX; base++) for (int d = 0; d <= 4; d++) {
            int total = SnowDepth.withRamp(base, d, i), extra = total - base;
            int rule = d < 1 || d > 3 ? 0 : Math.min((int) Math.round((8 - 2 * d) * i), base);
            assertEquals(rule, extra, "extra = min(round((8 - 2d) * frost), base) at " + i + " base " + base + " d " + d);
            assertTrue(total <= 2 * base, "a ramp at most doubles its base");
            if (base == 0) assertEquals(0, total, "no ramp on bare ground");
        }
    }

    @Test void flatSnowComesNoLaterThanSpecialSpots() {
        // cells of the same column: flat, in front of a step (ramp), stair/slab (same base), plant (covered from one layer)
        for (double i : FROST) for (int x = -30; x < 30; x++) for (int z = -30; z < 30; z++) {
            int flat = SnowDepth.target(MAX, i, x, z, DRIFT, 1);
            int stair = flat;
            for (int d = 1; d <= 3; d++) {
                int ramp = SnowDepth.withRamp(flat, d, i);
                if (flat == 0) assertEquals(0, ramp, "no ramp before the flat ground has snow");
                assertTrue(ramp <= 2 * flat);
            }
            assertEquals(flat > 0, SnowDepth.coversPlant(flat), "a plant is covered together with the bare ground");
            assertEquals(flat, stair);
        }
        assertTrue(SnowDepth.coversPlant(1));
        assertFalse(SnowDepth.coversPlant(0));
        assertEquals(1, SnowDepth.FIRST_LAYERS);
    }

    @Test void flatRampStairAndDriftTargetsAtTypicalFrost() {
        // a fixed column, printed as a table: flat (no drift), ramp at 1 block, stair, drift cell (default drifts)
        int x = 7, z = -3;
        int[][] expected = new int[FROST.length][];
        for (int k = 0; k < FROST.length; k++) {
            double i = FROST[k];
            int flat = SnowDepth.target(MAX, i, x, z, 0, 1);
            int drift = SnowDepth.target(MAX, i, x, z, DRIFT, 1);
            expected[k] = new int[]{flat, SnowDepth.withRamp(flat, 1, i), flat, drift, SnowDepth.withRamp(drift, 1, i)};
        }
        // 20 %: bare or flat 3 and ramp 3 + min(round(1.2), 3) = 4; 50 %: 6 and 9; 100 %: 12 and 18
        assertTrue(expected[2][0] == 0 && expected[2][1] == 0 || expected[2][0] == 3 && expected[2][1] == 4);
        assertArrayEquals(new int[]{6, 9, 6}, java.util.Arrays.copyOf(expected[3], 3));
        assertArrayEquals(new int[]{12, 18, 12}, java.util.Arrays.copyOf(expected[4], 3));
        for (int k = 0; k < 2; k++) {
            assertTrue(expected[k][0] <= 1 && expected[k][1] == expected[k][0], "2 % and 8 %: no ramp extra, at most one layer");
            assertTrue(expected[k][3] <= 1 && expected[k][4] == expected[k][3]);
        }
    }

    /** Statistic for a budgeted pass: adjacent covered pairs along x and z after the first steps of every chunk. */
    private static int[] pairs(java.util.function.IntBinaryOperator order, int steps) {
        int n = 4 * 16;
        boolean[][] c = new boolean[n][n];
        for (int cx = 0; cx < 4; cx++) for (int cz = 0; cz < 4; cz++) for (int s = 0; s < steps; s++) {
            int column = order.applyAsInt(cx * 4 + cz, s);
            c[cx * 16 + (column & 15)][cz * 16 + (column >> 4)] = true;
        }
        int px = 0, pz = 0;
        for (int a = 0; a < n - 1; a++) for (int b = 0; b < n; b++) { if (c[a][b] && c[a + 1][b]) px++; if (c[b][a] && c[b][a + 1]) pz++; }
        return new int[]{px, pz};
    }

    @Test void budgetedPassLeavesNoRows() {
        int steps = 64;   // a quarter of each chunk
        double p = steps / 256.0, expected = p * p * 64 * 63;   // about 252 pairs per axis when scattered
        int[] old = pairs((chunk, s) -> s % 256, steps);
        assertTrue(old[0] > expected * 3, "1.24.0 walked rows: " + old[0] + " pairs along x");
        int[] now = pairs((chunk, s) -> SnowDepth.column(chunk % 4, chunk / 4, s), steps);
        assertTrue(now[0] < expected * 1.5 && now[1] < expected * 1.5, "scattered: " + now[0] + "/" + now[1] + " vs " + expected);
        // every column still comes once per 256 steps
        for (int cx = -3; cx < 3; cx++) for (int cz = -3; cz < 3; cz++) {
            java.util.Set<Integer> seen = new java.util.HashSet<>();
            for (int s = 0; s < 256; s++) seen.add(SnowDepth.column(cx, cz, s + 1000));
            assertEquals(256, seen.size());
        }
    }
}
