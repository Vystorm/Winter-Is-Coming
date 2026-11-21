package at.vystorm.winter.world;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Which blocks stop wind-blown snow, where the sheltered surfaces are, the candidate filter and the amount. */
class ShelterTest {
    private static final WindRay.Wind WEST = new WindRay.Wind(20, 270, 12);

    @Test void givenMaterials_whenClassified_thenOnlyAirSmallPlantsAndOwnSnowLetSnowThrough() {
        for (String open : List.of("AIR", "CAVE_AIR", "VOID_AIR", "SHORT_GRASS", "FERN", "POPPY", "PINK_PETALS"))
            assertFalse(Shelter.blocksRay(open, false, true), open);
        assertFalse(Shelter.blocksRay("SNOW", true, true), "the plugin's own snow");
        assertTrue(Shelter.blocksRay("SNOW", false, true), "snow that is not the plugin's");
        for (String closed : List.of("STONE", "GLASS", "GLASS_PANE", "WHITE_STAINED_GLASS_PANE", "IRON_BARS", "OAK_FENCE", "COBBLESTONE_WALL",
                "OAK_SLAB", "OAK_STAIRS", "OAK_DOOR", "IRON_DOOR", "OAK_TRAPDOOR", "WATER", "TORCH", "TALL_GRASS", "SNOW_BLOCK", "CHEST"))
            assertTrue(Shelter.blocksRay(closed, true, true), closed);
        assertTrue(Shelter.blocksRay("OAK_LEAVES", true, true));
        assertFalse(Shelter.blocksRay("OAK_LEAVES", true, false), "leaves-block: false");
        assertFalse(Shelter.blocksRay("CHERRY_LEAVES", true, false));
    }

    @Test void givenARoofOnPosts_whenItsColumnsAreSearched_thenTheGroundBelowIsTheOnlySurface() {
        SceneWorld s = SceneWorld.roof(5, 3);
        int edge = SceneWorld.roofEdge(3);
        assertEquals(List.of(1), Shelter.surfaces(s, edge, 10, 12));
        assertEquals(List.of(), Shelter.surfaces(s, edge - 1, 10, 12), "open column: never a sheltered surface");
        assertEquals(List.of(), Shelter.surfaces(s, 13, 8, 12), "a post column has no surface below the roof");
        // the search goes max-height + 1 blocks down from the roof: clearance 5 needs max-height 4
        assertEquals(List.of(), Shelter.surfaces(s, edge, 10, 3));
        assertEquals(List.of(1), Shelter.surfaces(s, edge, 10, 4));
    }

    @Test void givenTwoFloorsUnderOneRoof_whenSearched_thenBothSurfacesComeTopDown() {
        SceneWorld s = new SceneWorld(20, 12, 20).ground();
        s.fill(5, 4, 5, 10, 4, 10, SceneWorld.PLANKS);   // a floor at y = 4
        s.fill(5, 8, 5, 10, 8, 10, SceneWorld.PLANKS);   // the roof at y = 8
        assertEquals(List.of(5, 1), Shelter.surfaces(s, 5, 7, 12));
    }

    @Test void givenOpenGroundWithDeepOwnSnow_whenSearched_thenTheOpenSnowIsNoShelteredSurface() {
        SceneWorld s = new SceneWorld(10, 8, 10).ground();
        s.set(4, 1, 4, SceneWorld.SNOW);
        s.set(4, 2, 4, SceneWorld.SNOW);
        assertEquals(List.of(), Shelter.surfaces(s, 4, 4, 12));
        assertEquals(2, s.rawHeight(4, 4));
        assertEquals(0, s.height(4, 4));
    }

    @Test void givenOwnSnowOnOpenGroundUpwind_whenTheRoofIsLow_thenTheRaysStillSeeTheGround() {
        // 1.5 blocks of plugin snow on the open ground in front of a roof of clearance 2 must not hide the sky
        SceneWorld s = SceneWorld.roof(2, 3);
        int edge = SceneWorld.roofEdge(3);
        int before = WindRay.free(s, edge, 1, 10, WEST);
        for (int z = 0; z < s.d; z++) for (int x = 0; x < edge; x++) { s.set(x, 1, z, SceneWorld.SNOW); s.set(x, 2, z, SceneWorld.SNOW); }
        assertTrue(before >= 6);
        assertEquals(before, WindRay.free(s, edge, 1, 10, WEST));
        assertTrue(Shelter.candidate(s, edge, 10, WEST), "the filter sees the lower ground below the snow");
    }

    @Test void givenTheFilter_whenOpenFlatGround_thenNothingIsTraced() {
        SceneWorld s = new SceneWorld(30, 8, 30).ground();
        assertFalse(Shelter.candidate(s, 15, 15, WEST));
        SceneWorld roof = SceneWorld.roof(5, 3);
        assertTrue(Shelter.candidate(roof, SceneWorld.roofEdge(3), 10, WEST));
    }

    /** Per sheltered cell the target with and without the filter: the filter may withhold, never add. */
    private static Map<String, Integer> snow(SceneWorld s, WindRay.Wind wind, boolean filter) {
        Map<String, Integer> out = new HashMap<>();
        for (int z = 0; z < s.d; z++) for (int x = 0; x < s.w; x++) {
            if (filter && !Shelter.candidate(s, x, z, wind)) continue;
            for (int y : Shelter.surfaces(s, x, z, wind.maxHeight())) {
                int t = Shelter.target(s, x, y, z, wind, 12, 4);
                if (t > 0) out.put(x + "," + y + "," + z, t);
            }
        }
        return out;
    }

    @Test void givenTheStudysScenes_whenFilteredOrNot_thenTheFilterNeverAddsAndLosesNothingThere() {
        for (SceneWorld s : List.of(SceneWorld.roof(3, 3), SceneWorld.roof(5, 3), SceneWorld.roof(8, 3), SceneWorld.porch(), SceneWorld.trees(), SceneWorld.houses()))
            for (int from : new int[]{0, 45, 90, 180, 270, 300}) {
                WindRay.Wind wind = new WindRay.Wind(20, from, 12);
                Map<String, Integer> all = snow(s, wind, false), filtered = snow(s, wind, true);
                for (var e : filtered.entrySet()) assertEquals(all.get(e.getKey()), e.getValue(), "the filter added " + e.getKey());
                assertEquals(all, filtered, "the filter withheld snow in a study scene, wind from " + from);
            }
    }

    /**
     * Middle rows (z 8..12) under a roof of this clearance open on every side, wind from the west, after the full amount
     * rule ran to rest: layers from the windward edge, cut after the first empty cell (every later cell is checked to
     * be empty too).
     */
    private static List<String> settledRows(int clearance, int normalDepth) {
        SceneWorld s = SceneWorld.roof(clearance, 6);
        int edge = SceneWorld.roofEdge(6);
        s.settle(WEST, normalDepth, 4, false, 30);
        List<String> rows = new java.util.ArrayList<>();
        for (int z = 8; z <= 12; z++) {
            int[] r = s.row(edge, 1, z, 6);
            int end = 0;
            while (end < r.length && r[end] > 0) end++;
            for (int i = end; i < r.length; i++) assertEquals(0, r[i], "h=" + clearance + " z=" + z + " snow after an empty cell: " + java.util.Arrays.toString(r));
            StringBuilder b = new StringBuilder("[");
            for (int i = 0; i <= Math.min(end, r.length - 1); i++) b.append(i == 0 ? "" : " ").append(r[i]);
            rows.add(b.append(']').toString());
        }
        return rows;
    }

    /**
     * Look of 2026-11-21 ("drift line"): replaces the pins of the regular staircase ([4 2 1 0] at clearance 3, [4 3 1 0]
     * at 5, [4 3 2 1 0] at 8 in every row). Every row now has its own drift line, so the rows of one roof differ; these
     * are the exact rows of the middle rows z 8..12 of this scene (fixed coordinates, stable hashes), pinned so any change
     * of the hashes or the profile shows. The properties of all rows are in the tests below.
     */
    @Test void givenRoofsOfSeveralClearances_whenTheAmountRuleComesToRest_thenTheRowsAreTheDriftLinesOfTheseCoordinates() {
        assertEquals(PINNED_FULL, pinnedRows(12));
        assertEquals(PINNED_FULL, pinnedRows(8), "depth 8 and 12 both exceed max-layers 4: the same rows");
        assertEquals(PINNED_LOW, pinnedRows(3));
    }
    private static String pinnedRows(int depth) {
        StringBuilder b = new StringBuilder();
        for (int h : new int[]{2, 3, 5, 8, 12}) b.append(b.length() == 0 ? "" : "; ").append("h").append(h).append(' ').append(String.join(" ", settledRows(h, depth)));
        return b.toString();
    }
    static final String PINNED_FULL = "h2 [1 0] [1 0] [2 1 0] [2 1 0] [1 0]; h3 [4 3 1 0] [4 3 2 0] [4 2 0] [4 2 0] [4 3 1 0]; "
            + "h5 [4 3 2 1 0] [4 3 2 1 0] [4 3 1 0] [4 2 1 0] [4 2 0]; h8 [4 3 2 1 0] [4 4 3 2 1 0] [4 3 2 1 0] [4 2 1 0] [4 2 1 0]; "
            + "h12 [4 3 2 2 1 0] [4 4 3 2 1 0] [4 3 2 2 1 0] [4 2 2 1 0] [4 2 1 1 0]",
        PINNED_LOW = "h2 [1 0] [1 0] [2 1 0] [2 1 0] [1 0]; h3 [3 2 1 0] [3 2 1 0] [3 2 0] [3 1 0] [3 2 0]; "
            + "h5 [3 2 1 0] [3 2 1 0] [3 2 1 0] [3 1 0] [3 1 0]; h8 [3 3 2 1 0] [3 3 2 1 0] [3 2 2 1 0] [3 2 1 0] [3 1 1 0]; "
            + "h12 [3 2 2 1 0] [3 3 2 2 1 0] [3 2 2 1 0] [3 2 1 1 0] [3 1 1 1 0]";

    /**
     * Every clearance and frost depth: the layers never grow going inward under one roof, every cell the single ray
     * reaches has snow, past the reach the snow falls by at most two layers per cell (no ledge where the drift ends:
     * replaces "each soft cell at most half of the cell before", which the drift line's plateaus break on purpose) and
     * nothing lies from reach + 2 on (the reach of a row lies between the table reach and table reach + 2 cells).
     */
    @Test void givenAnyClearanceAndDepth_whenAtRest_thenTheRowNeverGrowsInwardAndFadesWithinTwoCells() {
        for (int h = 2; h <= 12; h++) for (int depth = 1; depth <= 12; depth++) {
            SceneWorld s = SceneWorld.roof(h, 6);
            int edge = SceneWorld.roofEdge(6), reach = WindRay.singleRayReach(h, 20);
            s.settle(WEST, depth, 4, false, 30);
            for (int z = 8; z <= 12; z++) {
                int[] r = s.row(edge, 1, z, 7);
                String where = "h=" + h + " depth=" + depth + " z=" + z + " " + java.util.Arrays.toString(r);
                for (int d = 1; d < r.length; d++) assertTrue(r[d] <= r[d - 1], "grows inward: " + where);
                for (int d = 0; d < reach; d++) assertTrue(r[d] > 0, "no snow within the reach: " + where);
                for (int d = Math.max(1, reach); d < r.length; d++) assertTrue(r[d - 1] - r[d] <= 2, "ledge past the reach: " + where);
                for (int d = reach + 2; d < r.length; d++) assertEquals(0, r[d], "snow from reach + 2 on: " + where);
                if (h == 2) for (int l : r) assertTrue(l <= Shelter.LOW_CEILING_LAYERS, "under a two-high opening: " + where);
            }
        }
    }

    /** One middle row of a straight edge at x = ax (wind from the west), the rule's fixed point worked out along the row. */
    private static int[] syntheticRow(double[] shares, int clearance, int depth, int ax, int az) {
        return syntheticRow(shares, clearance, depth, 4, ax, az);
    }
    private static int[] syntheticRow(double[] shares, int clearance, int depth, int maxLayers, int ax, int az) {
        int[] out = new int[shares.length];
        int up = 0;
        Shelter.Drift drift = Shelter.Drift.of(ax, az, clearance, WEST, maxLayers);
        for (int d = 0; d < shares.length; d++) {
            out[d] = Shelter.layers(shares[d], depth, maxLayers, true, clearance == 2, drift, d, up);
            up = out[d];
        }
        return out;
    }
    /** Free rays of 8 per cell from the windward edge of a middle row (the suite's roofs, wind from the west). */
    private static final Map<Integer, double[]> SHARES = Map.of(3, new double[]{1, 0, 0, 0, 0, 0, 0}, 5, new double[]{1, 0.75, 0, 0, 0, 0, 0},
            8, new double[]{1, 1, 0.625, 0, 0, 0, 0}, 12, new double[]{1, 1, 1, 0.625, 0.25, 0.125, 0.125});
    /**
     * Properties over 4000 rows along many edges: the reach of a row lies between the table reach and table reach + 2
     * cells; the mean layers per cell band stay near the old staircase (within 0.75 layers of the old rows; at
     * clearance 12 up to 1.0 more: the old taper ended the drift before its own rays did, rays 8 8 8 5 2 of 8); neighbouring
     * rows differ often enough that no staircase forms and agree often enough to form lobes rather than flicker (look of
     * 2026-11-21, second pass: 15 .. 35 % identical, the study's target about 20 .. 30 %; the first pass had 40 .. 63 %);
     * no row has a drop of more than two layers.
     */
    @Test void givenManyEdges_whenRowsAreLaidOut_thenReachMeanAndVariationStayInTheirBands() {
        Map<Integer, double[]> old = Map.of(3, new double[]{4, 2, 1, 0, 0}, 5, new double[]{4, 3, 1, 0, 0}, 8, new double[]{4, 3, 2, 1, 0}, 12, new double[]{4, 3, 2, 1, 1});
        for (int h : new int[]{3, 5, 8, 12}) {
            int table = WindRay.singleRayReach(h, 20), n = 4000, same = 0;
            double[] mean = new double[7];
            for (int i = 0; i < n; i++) {
                int ax = -5000 + (i % 37) * 263, az = -3000 + i * 7;
                int[] a = syntheticRow(SHARES.get(h), h, 12, ax, az), b = syntheticRow(SHARES.get(h), h, 12, ax, az + 1);
                if (java.util.Arrays.equals(a, b)) same++;
                int reach = 0;
                while (reach < a.length && a[reach] > 0) reach++;
                String where = "h=" + h + " at " + ax + "," + az + " " + java.util.Arrays.toString(a);
                assertTrue(reach >= table && reach <= table + 2, "reach " + reach + " outside table " + table + " .. +2: " + where);
                for (int d = 1; d < a.length; d++) assertTrue(a[d - 1] - a[d] <= 2 && a[d] <= a[d - 1], "drop: " + where);
                for (int d = 0; d < 7; d++) mean[d] += a[d] / (double)n;
            }
            for (int d = 0; d < 5; d++) {
                String where = "h=" + h + " mean of cell " + d + " " + java.util.Arrays.toString(mean);
                assertTrue(mean[d] >= old.get(h)[d] - 0.75, "less snow than the old rows: " + where);
                assertTrue(mean[d] <= old.get(h)[d] + (h >= 12 ? 1.0 : 0.75), "more snow than the old rows: " + where);
            }
            assertTrue(same <= n * 0.35, "h=" + h + ": " + same + " of " + n + " rows like their neighbour (staircase)");
            assertTrue(same >= n * 0.15, "h=" + h + ": " + same + " of " + n + " rows like their neighbour (flicker)");
        }
    }

    /**
     * No staircase in the middle of a roof: on 3000 roofs of ten neighbouring rows along a straight edge, at least 95 %
     * have no three neighbouring rows that look the same (the first pass had three in a row on 70 .. 94 % of the roofs;
     * the rule breaks every third equal row of a run, judged on a virtual straight roof, so the rest are rows whose rays
     * differ from that roof's).
     */
    @Test void givenManyRoofs_whenRowsAreLaidOut_thenHardlyAnyHasThreeEqualRowsInARow() {
        for (int h : new int[]{3, 5, 8, 12}) {
            int roofs = 3000, ok = 0;
            for (int i = 0; i < roofs; i++) {
                int ax = -5000 + (i % 37) * 263, az = -3000 + i * 70;
                int run = 1, best = 1;
                int[] prev = null;
                for (int j = 0; j < 10; j++) {
                    int[] r = syntheticRow(SHARES.get(h), h, 12, ax, az + j);
                    if (prev != null) { run = java.util.Arrays.equals(r, prev) ? run + 1 : 1; best = Math.max(best, run); }
                    prev = r;
                }
                if (best <= 2) ok++;
            }
            assertTrue(ok >= roofs * 0.95, "h=" + h + ": only " + ok + " of " + roofs + " roofs without three equal rows in a row");
        }
    }

    /**
     * Sideways drops: neighbouring rows along a straight edge never differ by more than two layers in a cell, for every
     * clearance, frost depth and max-layers (a lobe boundary is no ledge either; the drift is lifted by its neighbours'
     * lines, and the rest of the rule only takes minima and maxima of them with the same rays).
     */
    @Test void givenNeighbouringRows_whenLaidOut_thenNoCellDiffersByMoreThanTwoLayersSideways() {
        for (int maxLayers : new int[]{4, 8}) for (int h = 2; h <= 12; h++) for (int depth : new int[]{2, 3, 5, 8, 12}) {
            double[] shares = DriftLines.virtualShares(h, WEST, 0);
            int[] prev = null;
            for (int i = 0; i < 1500; i++) {
                int az = -2000 + i;
                int[] r = syntheticRow(shares, h, depth, maxLayers, 37, az);
                if (prev != null) for (int d = 0; d < r.length; d++)
                    assertTrue(Math.abs(r[d] - prev[d]) <= 2, "h=" + h + " depth=" + depth + " max-layers=" + maxLayers + " z=" + az + ": "
                            + java.util.Arrays.toString(prev) + " -> " + java.util.Arrays.toString(r));
                prev = r;
            }
        }
    }

    /**
     * Monotonic in frost and deterministic: for every edge position and distance the profile never shrinks when the
     * frost (full) grows, and the same position gives the same drift line every time (no random numbers: hashes of
     * the position only, so restarts and passes agree).
     */
    @Test void givenADriftLine_whenTheFrostGrowsOrItIsAskedAgain_thenItNeverShrinksAndIsTheSame() {
        for (int i = 0; i < 3000; i++) {
            int ax = -40000 + i * 977, az = 25000 - i * 131;
            for (int clearance = 2; clearance <= 12; clearance++) {
                Shelter.Drift d = Shelter.Drift.of(ax, az, clearance, 20);
                assertEquals(d, Shelter.Drift.of(ax, az, clearance, 20));
                for (double dist = 0; dist <= 9; dist += 0.5) for (int full = 1; full < 8; full++)
                    assertTrue(d.profile(full + 1, dist) >= d.profile(full, dist), "profile shrinks with more frost at " + ax + "," + az + " " + d + " dist " + dist);
                assertEquals(0, d.profile(8, WindRay.singleRayReach(clearance, 20) + 2), "nothing at reach + 2: " + d);
            }
        }
    }

    @Test void givenSnowAtRest_whenTheFrostFallsOrThaws_thenCreepCellsShrinkWithTheirNeighbourAndTheResultIsTheSameAsFresh() {
        for (int h : new int[]{3, 5, 8, 12}) {
            SceneWorld grown = SceneWorld.roof(h, 6), fresh = SceneWorld.roof(h, 6);
            int edge = SceneWorld.roofEdge(6);
            grown.settle(WEST, 12, 4, false, 30);
            grown.settle(WEST, 3, 4, false, 30);
            fresh.settle(WEST, 3, 4, false, 30);
            for (int z = 2; z <= 18; z++) assertArrayEquals(fresh.row(0, 1, z, 30), grown.row(0, 1, z, 30), "h=" + h + " z=" + z + ": less frost leaves the rows of a fresh start");
            grown.settle(WEST, 0, 4, false, 30);
            for (int z = 0; z < grown.d; z++) for (int x = 0; x < grown.w; x++) assertEquals(0, grown.snowLayers(x, 1, z), "thaw left snow at " + x + "," + z + " h=" + h);
        }
    }

    @Test void givenTheStudysScenesAtRest_whenFilteredOrNot_thenTheFilterNeverAdds() {
        List<java.util.function.Supplier<SceneWorld>> scenes = List.of(() -> SceneWorld.roof(3, 3), () -> SceneWorld.roof(8, 3), SceneWorld::porch, SceneWorld::trees, SceneWorld::houses);
        for (var scene : scenes)
            for (int from : new int[]{0, 90, 270, 300}) {
                WindRay.Wind wind = new WindRay.Wind(20, from, 12);
                SceneWorld all = scene.get(), filtered = scene.get();
                all.settle(wind, 12, 4, false, 30);
                filtered.settle(wind, 12, 4, true, 30);
                for (int z = 0; z < all.d; z++) for (int x = 0; x < all.w; x++) for (int y = 1; y < all.h; y++)
                    assertEquals(all.snowLayers(x, y, z), filtered.snowLayers(x, y, z), "filter changed the snow at " + x + "," + y + "," + z + " wind " + from);
            }
    }

    @Test void givenClosedHousesWithDoorsAndGlass_whenTraced_thenNothingInside() {
        SceneWorld s = SceneWorld.houses();
        // house 2 has a glass window and a door opening on the leeward side; close the opening with a door
        s.fill(34, 1, 8, 34, 2, 8, SceneWorld.DOOR);
        boolean[] sky = s.skyConnected();
        for (int from = 0; from < 360; from += 15) {
            WindRay.Wind wind = new WindRay.Wind(20, from, 12);
            for (SceneWorld.Surface c : s.sheltered()) if (c.x() > 26 && c.x() < 34 && c.z() > 5 && c.z() < 13) {
                assertFalse(s.open(sky, c.x(), c.y(), c.z()));
                assertEquals(0, Shelter.target(s, c.x(), c.y(), c.z(), wind, 12, 8), "snow inside at " + c + " wind " + from);
            }
        }
    }

    @Test void givenACanopy_whenLeavesBlock_thenOnlyTheWindwardRimGetsSnow() {
        SceneWorld s = SceneWorld.trees();
        Map<String, Integer> block = snow(s, WEST, false);
        // the oak's canopy covers x 6..10 around its trunk at x 8: with leaves blocking only the two west columns
        for (String k : block.keySet()) {
            String[] p = k.split(",");
            int x = Integer.parseInt(p[0]), z = Integer.parseInt(p[2]);
            if (x <= 11 && z >= 7 && z <= 9) assertTrue(x <= 7, "snow deep under the oak at " + k);
        }
        assertTrue(block.keySet().stream().anyMatch(k -> k.startsWith("6,1,")));
        s.leavesPass = true;
        Map<String, Integer> pass = snow(s, WEST, false);
        assertTrue(pass.size() > block.size(), "leaves-block false lets snow further in");
    }

    /** A drift line that keeps full depth over many cells (the profile out of the way of the other rules). */
    private static final Shelter.Drift FLAT = new Shelter.Drift(12, 13.5, 1, 0);
    /** The straight staircase of the old rule as a drift line: full - floor(full x d / 4), reach 3. */
    private static final Shelter.Drift LINE4 = new Shelter.Drift(3, 4, 1, 0);

    /**
     * The share rule with the profile out of the way: the share scales {@code full} = min(depth, max-layers, room
     * above, headroom), nothing below 25 %, at least one layer above it.
     */
    @Test void givenShares_whenTurnedIntoLayers_thenTheFloorTheCapAndTheBlockAboveHold() {
        assertEquals(0, Shelter.layers(0.2, 12, 8, true, false, FLAT, 0, 0), "below 25% nothing");
        assertEquals(0, Shelter.layers(1, 0, 8, true, false, FLAT, 0, 0), "no snow on the open ground, none under the roof");
        assertEquals(0, Shelter.layers(1, 0, 8, true, false, FLAT, 0, 8), "no snow on the open ground: no creep either");
        assertEquals(2, Shelter.layers(0.25, 12, 8, true, false, FLAT, 0, 0), "a quarter of free rays: a quarter of full (8)");
        assertEquals(1, Shelter.layers(0.25, 1, 8, true, false, FLAT, 0, 0), "at least one layer");
        assertEquals(4, Shelter.layers(1, 12, 4, true, false, FLAT, 0, 0), "max-layers");
        assertEquals(8, Shelter.layers(1, 12, 8, true, false, FLAT, 0, 0));
        assertEquals(7, Shelter.layers(1, 12, 8, false, false, FLAT, 0, 0), "never touches the block above");
        assertEquals(5, Shelter.layers(0.75, 8, 8, false, false, FLAT, 0, 0), "0.75 x 7, rounded");
        for (Shelter.Drift d : List.of(FLAT, LINE4, Shelter.Drift.of(3, 9, 8, 20))) for (double dist = 0; dist < 8; dist += 0.5)
            assertTrue(Shelter.layers(1, 12, 8, false, false, d, dist, 8) <= 7, "the block above, " + d + " at " + dist);
    }

    @Test void givenCellsDeeperUnderTheRoof_whenTurnedIntoLayers_thenTheDriftLineDecidesButAtLeastOneWhereRaysReach() {
        assertEquals(4, Shelter.layers(1, 12, 4, true, false, LINE4, 0, 0), "the first cell is full");
        assertEquals(3, Shelter.layers(1, 12, 4, true, false, LINE4, 1, 0));
        assertEquals(2, Shelter.layers(1, 12, 4, true, false, LINE4, 2, 0));
        assertEquals(1, Shelter.layers(1, 12, 4, true, false, LINE4, 3, 0));
        assertEquals(1, Shelter.layers(1, 12, 4, true, false, LINE4, 6, 0), "a cell the rays reach keeps one layer");
        assertEquals(2, Shelter.layers(0.5, 12, 4, true, false, LINE4, 1, 0), "the share still counts when it is the smaller");
        assertEquals(3, Shelter.layers(1, 12, 4, true, false, LINE4, 1.9, 0), "a diagonal distance counts by whole cells");
    }

    @Test void givenSnowInTheShelteredNeighbourUpwind_whenNoRayReachesTheCell_thenLessThanItCreepsInAsFarAsTheDriftLine() {
        assertEquals(3, Shelter.layers(0, 12, 4, true, false, FLAT, 1, 4), "one layer less than the neighbour");
        assertEquals(2, Shelter.layers(0, 12, 4, true, false, FLAT, 1, 3));
        assertEquals(0, Shelter.layers(0, 12, 4, true, false, FLAT, 1, 1), "one layer creeps no further");
        assertEquals(0, Shelter.layers(0.1, 12, 4, true, false, FLAT, 1, 0), "no rays, no neighbour: nothing");
        assertEquals(1, Shelter.layers(0, 12, 4, true, false, LINE4, 3, 4), "the drift line caps the creep");
        assertEquals(0, Shelter.layers(0, 12, 4, true, false, LINE4, 5, 4), "past the drift line nothing creeps");
        assertEquals(1, Shelter.layers(1, 12, 4, true, false, LINE4, 3, 4), "rays reach the cell: the drift line still caps");
        assertEquals(3, Shelter.layers(0.25, 12, 4, true, false, FLAT, 1, 4), "a weakly lit cell after a full one: no ledge (round(0.25 x 4) = 1 alone)");
        assertEquals(4, Shelter.layers(0, 12, 4, true, false, FLAT, 1, 8), "never more than full (max-layers)");
        assertEquals(1, Shelter.layers(0, 1, 4, true, false, FLAT, 1, 8), "never more than the normal depth");
        assertEquals(2, Shelter.layers(0, 12, 8, true, true, FLAT, 1, 8), "never more than the headroom allows");
        // strictly less than the neighbour: a chain ends, and two cells can never hold each other up
        for (int up = 1; up <= 8; up++) for (Shelter.Drift d : List.of(FLAT, LINE4)) for (int dist = 0; dist < 8; dist++)
            assertTrue(Shelter.layers(0, 12, 8, true, false, d, dist, up) < up);
    }

    /**
     * Headroom: snow of n layers has a collision height of (n - 1) / 8 block (the top layer is walked through) and a
     * standing player is 1.8 blocks high. Under an opening of two free blocks a player still walks in while
     * (n - 1) / 8 + 1.8 <= 2, i.e. n <= 2.6: 2 layers (1.925) fit, 3 layers (2.05) close the doorway.
     */
    @Test void givenAnOpeningOnlyTwoHigh_whenTurnedIntoLayers_thenAtMostTwoLayersSoAStandingPlayerStillWalksIn() {
        int largest = 0;
        for (int n = 1; n <= 8; n++) if ((n - 1) / 8.0 + 1.8 <= 2.0) largest = n;
        assertEquals(2, largest, "collision arithmetic");
        assertEquals(largest, Shelter.LOW_CEILING_LAYERS);
        assertEquals(2, Shelter.layers(1, 12, 8, true, true, FLAT, 0, 0));
        assertEquals(2, Shelter.layers(1, 12, 4, true, true, FLAT, 0, 0));
        assertEquals(1, Shelter.layers(1, 12, 4, true, true, new Shelter.Drift(1, 1.5, 1, 0), 1, 0), "the drift line also works under a low opening");
        assertEquals(1, Shelter.layers(1, 1, 4, true, true, FLAT, 0, 0));
        // every drift line, distance and neighbour: never more than 2 under a low opening
        for (int i = 0; i < 500; i++) for (double dist = 0; dist < 6; dist += 0.5) for (int up = 0; up <= 8; up++)
            assertTrue(Shelter.layers(i % 2, 12, 8, true, true, Shelter.Drift.of(i * 37, -i * 11, 2, 20), dist, up) <= 2);
        // three free blocks: (8 - 1) / 8 + 1.8 = 2.675 <= 3, so the full 8 are walkable and only max-layers caps
        assertEquals(4, Shelter.layers(1, 12, 4, true, false, FLAT, 0, 0));
    }

    @Test void givenTheRoomAboveACell_whenTargeted_thenOnlyAnOpeningTwoHighIsALowCeiling() {
        WindRay.Wind wind = new WindRay.Wind(20, 270, 12);
        for (int h = 2; h <= 4; h++) {
            SceneWorld s = SceneWorld.roof(h, 3);
            int edge = SceneWorld.roofEdge(3);
            int t = Shelter.target(s, edge, 1, 10, wind, 12, 8);
            if (h == 2) assertEquals(2, t, "clearance 2");
            else assertTrue(t > 2, "clearance " + h + ": " + t);
        }
    }

    @Test void givenARoofOfClearanceOne_whenTheSnowWouldFillTheCell_thenItStopsBelowTheRoof() {
        SceneWorld s = SceneWorld.roof(1, 3);
        int edge = SceneWorld.roofEdge(3);
        int t = Shelter.target(s, edge, 1, 10, new WindRay.Wind(40, 270, 12), 12, 8);
        assertTrue(t <= 7, "layers " + t);
    }

    @Test void givenAPorchOpenToTheWind_whenAPlayerClosesIt_thenTheReCheckFindsNoSnowLeftAndKnowsWhichCellsToAsk() {
        SceneWorld s = SceneWorld.porch();
        Map<String, Integer> open = new HashMap<>();
        for (int z = 7; z <= 14; z++) for (int x = 12; x <= 15; x++) for (int y : Shelter.surfaces(s, x, z, 12)) {
            int t = Shelter.target(s, x, y, z, WEST, 12, 4);
            if (t > 0) open.put(x + "," + y + "," + z, t);
        }
        assertFalse(open.isEmpty(), "the open porch has snow");
        // close the open west side with a wall one block in front of the porch (the snow cells stay air below the roof)
        for (int z = 6; z <= 15; z++) for (int y = 1; y <= 5; y++) s.set(11, y, z, SceneWorld.PLANKS);
        for (String k : open.keySet()) {
            String[] p = k.split(",");
            int x = Integer.parseInt(p[0]), y = Integer.parseInt(p[1]), z = Integer.parseInt(p[2]);
            assertTrue(Shelter.mayShade(11, y, z, x, y, z, WEST), "the wall block in front names the porch cell " + k);
        }
        for (String k : open.keySet()) {
            String[] p = k.split(",");
            assertEquals(0, Shelter.target(s, Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2]), WEST, 12, 4), "snow left in the closed porch at " + k);
        }
    }

    @Test void givenARoof_whenTheCellsUnderItAreCounted_thenTheFirstShelteredCellIsZero() {
        SceneWorld s = SceneWorld.roof(5, 3);
        int edge = SceneWorld.roofEdge(3);
        for (int d = 0; d < 3; d++) assertEquals(d, Shelter.under(s, edge + d, 1, 10, WEST));
        WindRay.Wind low = new WindRay.Wind(20, 270, 3);
        assertEquals(WindRay.horizontalReach(low), Shelter.under(s, edge + 9, 1, 10, low), "no open column within the reach");
        assertEquals(0, Shelter.under(s, edge + 10, 1, 10, new WindRay.Wind(20, 90, 12)), "wind from the east: the east edge is the first cell");
        s.unloaded = (x, z) -> x < edge;
        assertEquals(WindRay.horizontalReach(WEST), Shelter.under(s, edge, 1, 10, WEST), "an unloaded column counts as roof");
    }

    /** Side rims: the distance takes the cone's outer directions (+-45°, diagonal steps x 1.41), so the rim open to the side is "at the edge". */
    @Test void givenARoofOpenOnItsSides_whenTheDistanceIsAsked_thenTheConesOuterDirectionsCount() {
        SceneWorld s = SceneWorld.roof(5, 3);   // plate x 10..20, z 5..15
        assertEquals(0, Shelter.distance(s, 16, 1, 5, WEST, Shelter.under(s, 16, 1, 5, WEST)), 1e-9, "north rim: open to the north-west");
        assertEquals(0, Shelter.distance(s, 16, 1, 15, WEST, Shelter.under(s, 16, 1, 15, WEST)), 1e-9, "south rim: open to the south-west");
        assertEquals(1.41, Shelter.distance(s, 16, 1, 6, WEST, Shelter.under(s, 16, 1, 6, WEST)), 1e-9, "one diagonal step in");
        assertEquals(6, Shelter.distance(s, 16, 1, 10, WEST, Shelter.under(s, 16, 1, 10, WEST)), 1e-9, "middle row: straight upwind is nearer");
        assertArrayEquals(new int[]{10, 10}, Shelter.anchor(16, 10, WEST, 6), "the row's first sheltered cell");
    }

    @Test void givenNeighboursUpwind_whenAskedForCreep_thenOnlyShelteredOwnSnowAtTheSameLevelAcrossAFaceCounts() {
        SceneWorld s = SceneWorld.roof(3, 3);
        int edge = SceneWorld.roofEdge(3);
        s.snow(edge, 1, 10, 4);
        assertEquals(4, Shelter.upwindLayers(s, edge + 1, 1, 10, WEST));
        assertEquals(0, Shelter.upwindLayers(s, edge + 1, 1, 11, WEST), "a cell beside it, not upwind");
        assertEquals(4, Shelter.upwindLayers(s, edge + 1, 1, 10, new WindRay.Wind(20, 300, 12)), "the dominant axis of a west-north-west wind");
        assertEquals(0, Shelter.upwindLayers(s, edge + 1, 2, 10, WEST), "another level");
        s.snow(edge - 1, 1, 10, 8);
        assertEquals(0, Shelter.upwindLayers(s, edge, 1, 10, WEST), "open ground in front of the roof never creeps in");
        s.set(edge, 1, 10, SceneWorld.DOOR);
        assertEquals(0, Shelter.upwindLayers(s, edge + 1, 1, 10, WEST), "a door cell");
        s.set(edge, 1, 10, SceneWorld.PLANT);
        assertEquals(0, Shelter.upwindLayers(s, edge + 1, 1, 10, WEST), "a cell without snow");
        assertArrayEquals(new int[]{-1, 0}, Shelter.upwindStep(WEST));
        SceneWorld corner = SceneWorld.roof(3, 3);
        corner.snow(edge, 1, 9, 8);
        for (int from = 270; from <= 360; from += 5)
            assertEquals(0, Shelter.upwindLayers(corner, edge + 1, 1, 10, new WindRay.Wind(20, from, 12)), "snow across the corner, wind " + from);
        assertArrayEquals(new int[]{0, -1}, Shelter.upwindStep(new WindRay.Wind(20, 0, 12)), "wind from the north: upwind is north (-z)");
    }

    @Test void givenTheFilter_whenItsOffsetsAreWorkedOut_thenEachColumnOnceNearestFirstAndTheSameAnswers() {
        int[] o = Shelter.filterOffsets(WEST);
        java.util.Set<Long> seen = new java.util.HashSet<>();
        for (int i = 0; i < o.length; i += 2) assertTrue(seen.add((long)o[i] << 32 | (o[i + 1] & 0xFFFFFFFFL)), "offset twice");
        assertTrue(o.length / 2 < 5 * WindRay.horizontalReach(WEST), "fewer reads than five per distance: " + o.length / 2);
        assertArrayEquals(new int[]{-1, 1}, new int[]{o[0], o[1]});
        // the old loop (five directions per distance, trigonometry per column) gives the same answer everywhere
        for (SceneWorld s : List.of(SceneWorld.roof(3, 3), SceneWorld.porch(), SceneWorld.trees(), SceneWorld.houses()))
            for (int from : new int[]{0, 45, 90, 200, 270, 300}) {
                WindRay.Wind wind = new WindRay.Wind(20, from, 12);
                for (int z = 0; z < s.d; z++) for (int x = 0; x < s.w; x++) assertEquals(oldCandidate(s, x, z, wind), Shelter.candidate(s, x, z, wind), x + "," + z + " wind " + from);
            }
    }
    private static boolean oldCandidate(SceneWorld w, int x, int z, WindRay.Wind wind) {
        int here = w.rawHeight(x, z), reach = WindRay.horizontalReach(wind);
        for (int d = 1; d <= reach; d++) for (double offset : new double[]{-45, -22.5, 0, 22.5, 45}) {
            double az = StrictMath.toRadians(wind.from() + offset);
            int cx = x + (int)Math.round(StrictMath.sin(az) * d), cz = z + (int)Math.round(-StrictMath.cos(az) * d);
            if ((cx == x && cz == z) || !w.loaded(cx, cz)) continue;
            int up = w.rawHeight(cx, cz);
            if (up <= here - 2) return true;
            if (up == here - 1 && w.height(cx, cz) <= here - 2) return true;
        }
        return false;
    }

    /** The same porch with the full amount rule (creep included) run to rest before and after it is closed. */
    @Test void givenAPorchAtRest_whenClosed_thenTheRuleRunToRestAgainLeavesNoSnowAndTheHouseStaysEmpty() {
        SceneWorld s = SceneWorld.porch();
        s.settle(WEST, 12, 4, false, 30);
        int before = 0;
        for (int z = 7; z <= 14; z++) for (int x = 12; x <= 15; x++) for (int y = 1; y <= 4; y++) before += s.snowLayers(x, y, z);
        assertTrue(before > 0, "the open porch has snow");
        for (int z = 6; z <= 15; z++) for (int y = 1; y <= 5; y++) s.set(11, y, z, SceneWorld.PLANKS);
        int passes = s.settle(WEST, 12, 4, false, 30);
        for (int z = 7; z <= 14; z++) for (int x = 12; x <= 15; x++) for (int y = 1; y <= 4; y++) assertEquals(0, s.snowLayers(x, y, z), "snow left in the closed porch at " + x + "," + y + "," + z);
        assertTrue(passes > 1);
        for (int z = 7; z <= 14; z++) for (int x = 17; x <= 23; x++) assertEquals(0, s.snowLayers(x, 1, z), "snow in the house behind the door");
    }

    /** Soundness of the early re-check: a placed block that changes any block top's rays is always named by mayShade. */
    @Test void givenRandomPlacedBlocks_whenTheyChangeTheRaysOfABlockTop_thenMayShadeNamesThatBlockTop() {
        int[] rng = {0x5EED};
        int checked = 0;
        for (int from : new int[]{270, 0, 135}) {
            WindRay.Wind wind = new WindRay.Wind(20, from, 12);
            for (int scene = 0; scene < 4; scene++) for (int n = 0; n < 40; n++) {
                SceneWorld s = switch (scene) { case 0 -> SceneWorld.roof(3, 3); case 1 -> SceneWorld.roof(8, 3); case 2 -> SceneWorld.porch(); default -> SceneWorld.trees(); };
                List<SceneWorld.Surface> tops = s.sheltered();
                int[] before = new int[tops.size()];
                for (int i = 0; i < tops.size(); i++) before[i] = WindRay.free(s, tops.get(i).x(), tops.get(i).y(), tops.get(i).z(), wind);
                int px, py, pz;
                do {
                    px = (int)(WindRay.next(rng) * s.w);
                    py = 1 + (int)(WindRay.next(rng) * (s.h - 2));
                    pz = (int)(WindRay.next(rng) * s.d);
                } while (s.get(px, py, pz) != SceneWorld.AIR);
                s.set(px, py, pz, SceneWorld.SOLID);
                for (int i = 0; i < tops.size(); i++) {
                    SceneWorld.Surface c = tops.get(i);
                    if (c.x() == px && c.y() == py && c.z() == pz) continue;   // built on the snow itself: released directly
                    if (WindRay.free(s, c.x(), c.y(), c.z(), wind) != before[i]) {
                        checked++;
                        assertTrue(Shelter.mayShade(px, py, pz, c.x(), c.y(), c.z(), wind), "block " + px + "," + py + "," + pz + " changes " + c + " wind " + from);
                    }
                }
            }
        }
        assertTrue(checked > 20, "the placements changed some rays: " + checked);
    }

    @Test void givenABlockDownwindOrFarAway_whenAsked_thenNoReCheck() {
        assertTrue(Shelter.mayShade(4, 3, 10, 5, 1, 10, WEST), "west of the snow and above it");
        assertFalse(Shelter.mayShade(8, 3, 10, 5, 1, 10, WEST), "east of the snow: downwind");
        assertFalse(Shelter.mayShade(4, 0, 10, 5, 1, 10, WEST), "below the snow");
        assertFalse(Shelter.mayShade(-20, 3, 10, 5, 1, 10, WEST), "too far for a ray that low");
        assertFalse(Shelter.mayShade(4, 15, 10, 5, 1, 10, WEST), "above max-height");
    }

    @Test void givenARayBudget_whenACandidateIsTraced_thenTheReadsStaySmall() {
        SceneWorld s = SceneWorld.roof(8, 3);
        int edge = SceneWorld.roofEdge(3);
        s.reads = 0;
        Shelter.target(s, edge + 1, 1, 10, WEST, 12, 4);
        assertTrue(s.reads < 8 * 12 * 2 * 9, "reads " + s.reads);
    }
}
