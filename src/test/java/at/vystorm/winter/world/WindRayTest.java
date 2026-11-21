package at.vystorm.winter.world;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The geometry of wind-blown snow against the design study (tools winter-slant): closed rooms get nothing at any wind,
 * point sampling would leak (guard against "simplifying"), and the reach under roofs matches the study's table.
 * Expected numbers come from the study's Node model (lib.js, proof.js, reach.js with the recommended cone).
 */
class WindRayTest {
    private static final double[] FULL_ANGLES = {5, 10, 15, 20, 25, 30, 35, 40, 45};
    private static final double[] MID_ANGLES = {10, 20, 30, 45};

    /** Leaks (rays from closed surfaces that reached the sky) over a sweep of angles and every wind direction in 5° steps. */
    private static long leaks(SceneWorld s, double[] angles, int maxRise, boolean cone) {
        boolean[] sky = s.skyConnected();
        long leaks = 0;
        List<SceneWorld.Surface> closed = s.sheltered().stream().filter(c -> !s.open(sky, c.x(), c.y(), c.z())).toList();
        assertFalse(closed.isEmpty(), "the scene has closed surfaces");
        for (double angle : angles) for (int az = 0; az < 360; az += 5) for (SceneWorld.Surface c : closed) {
            if (cone) leaks += WindRay.free(s, c.x(), c.y(), c.z(), new WindRay.Wind(angle, az, maxRise));
            else if (WindRay.ray(s, c.x() + 0.5, c.z() + 0.5, c.y(), angle, az, maxRise, WindRay.FLAKE_HALF_WIDTH)) leaks++;
        }
        return leaks;
    }
    private static int closedCount(SceneWorld s) {
        boolean[] sky = s.skyConnected();
        return (int)s.sheltered().stream().filter(c -> !s.open(sky, c.x(), c.y(), c.z())).count();
    }

    @Test void givenTheStudysAdversarialRooms_whenBuilt_thenTheyMatchTheModel() {
        // closed surface counts from out/proof.txt: the port builds the same scenes
        assertEquals(61, closedCount(SceneWorld.diamondRoom()));
        assertEquals(153, closedCount(SceneWorld.diagonalStrip()));
        assertEquals(113, closedCount(SceneWorld.lattice()));
        assertEquals(76, closedCount(SceneWorld.boxes(1)));
        assertEquals(54, closedCount(SceneWorld.boxes(2)));
    }

    @Test void givenRoomsTouchingOnlyAtEdges_whenTheWindBlowsFromAnyDirection_thenNoSnowGetsIn() {
        for (SceneWorld s : List.of(SceneWorld.diamondRoom(), SceneWorld.diagonalStrip(), SceneWorld.lattice())) {
            assertEquals(0, leaks(s, FULL_ANGLES, 24, true), "cone");
            assertEquals(0, leaks(s, FULL_ANGLES, 24, false), "single ray");
            assertEquals(0, leaks(s, FULL_ANGLES, 12, true), "cone at the default max-height");
        }
    }

    @Test void givenRandomClosedBoxes_whenTheWindBlowsFromAnyDirection_thenNoSnowGetsIn() {
        for (int seed = 1; seed <= 25; seed++) {
            SceneWorld s = SceneWorld.boxes(seed);
            if (closedCount(s) == 0) continue;
            assertEquals(0, leaks(s, MID_ANGLES, 24, true), "seed " + seed);
            assertEquals(0, leaks(s, MID_ANGLES, 24, false), "seed " + seed + " single ray");
        }
    }

    /**
     * The full amount rule (rays, taper, creep from the sheltered neighbour upwind, headroom) run to rest on a fresh
     * copy of the scene for every wind: snow layers in cells that are not face-connected to the open air.
     */
    static long settledInside(java.util.function.Supplier<SceneWorld> scene, double[] angles, int step, int maxRise) {
        long inside = 0;
        for (double angle : angles) for (int az = 0; az < 360; az += step) {
            SceneWorld s = scene.get();
            boolean[] sky = s.skyConnected();
            s.settle(new WindRay.Wind(angle, az, maxRise), 12, 8, false, 40);
            for (SceneWorld.Surface c : s.sheltered()) if (!s.open(sky, c.x(), c.y(), c.z())) inside += s.snowLayers(c.x(), c.y(), c.z());
            // snow on cells that were closed before it fell (the snow itself never counts as a wall)
            for (int z = 0; z < s.d; z++) for (int x = 0; x < s.w; x++) for (int y = 1; y < s.h; y++)
                if (s.snowLayers(x, y, z) > 0) assertTrue(s.open(sky, x, y, z), "snow in a closed cell " + x + "," + y + "," + z + " angle " + angle + " wind " + az);
        }
        return inside;
    }

    @Test void givenRoomsTouchingOnlyAtEdges_whenTheFullAmountRuleComesToRestInAnyWind_thenNoSnowInside() {
        assertEquals(0, settledInside(SceneWorld::diamondRoom, new double[]{10, 20, 30, 40}, 15, 24), "diamond room");
        assertEquals(0, settledInside(SceneWorld::diagonalStrip, new double[]{10, 20, 30, 40}, 15, 24), "diagonal strip");
        assertEquals(0, settledInside(SceneWorld::lattice, new double[]{10, 20, 30, 40}, 15, 24), "lattice");
        assertEquals(0, settledInside(SceneWorld::diamondRoomUnderRoof, new double[]{10, 20, 30, 40}, 15, 12), "diamond room under a wide roof (snow on every side of its corners)");
    }

    @Test void givenRandomClosedBoxes_whenTheFullAmountRuleComesToRest_thenNoSnowInside() {
        for (int seed = 1; seed <= 25; seed++) {
            int k = seed;
            if (closedCount(SceneWorld.boxes(k)) == 0) continue;
            assertEquals(0, settledInside(() -> SceneWorld.boxes(k), new double[]{20, 40}, 30, 24), "seed " + seed);
        }
    }

    /**
     * Creep guard: a roof open to the west with snow at its edge, and right behind the snowy cells a closed room whose
     * windward side is one wall of the given block (the creep would land in the next cell). Closed and open doors are
     * both OAK_DOOR (doors block open or closed), panes, bars, fences and trapdoors count as walls.
     */
    @Test void givenAClosedRoomRightBehindSnowyCells_whenItsWallIsAnyBlock_thenNoSnowCreepsThrough() {
        for (int wall : new int[]{SceneWorld.PLANKS, SceneWorld.DOOR, SceneWorld.OPEN_DOOR, SceneWorld.PANE, SceneWorld.GLASS, SceneWorld.BARS, SceneWorld.FENCE, SceneWorld.TRAPDOOR, SceneWorld.SLAB, SceneWorld.STAIRS})
            for (int gap = 0; gap <= 2; gap++) {
                int w = wall, g = gap;
                assertEquals(0, settledInside(() -> SceneWorld.creepGuard(w, g), new double[]{10, 20, 30, 40}, 15, 12), "wall " + SceneWorld.MATERIAL[wall] + " " + gap + " cells behind the edge");
                SceneWorld s = SceneWorld.creepGuard(w, g);
                s.settle(new WindRay.Wind(20, 270, 12), 12, 8, false, 40);
                assertTrue(s.snowLayers(SceneWorld.CREEP_EDGE, 1, 10) > 0, "the edge has snow: the guard is tested");
            }
    }

    @Test void givenLeavesThatLetRaysThrough_whenRoomsAreClosedByBlocks_thenStillNothingGetsIn() {
        SceneWorld s = SceneWorld.diamondRoom();
        s.leavesPass = true;
        assertEquals(0, leaks(s, FULL_ANGLES, 24, true));
    }

    /**
     * Regression guard: sampling points along the ray (the obvious "simplification") slips through diagonal block
     * edges. The model counts 312 and 257 such leaks in these two rooms; the footprint ray above counts 0.
     */
    @Test void givenPointSampling_whenTracedThroughDiagonalWalls_thenItLeaks() {
        assertEquals(312, naiveLeaks(SceneWorld.diamondRoom(), FULL_ANGLES));
        assertEquals(257, naiveLeaks(SceneWorld.diagonalStrip(), FULL_ANGLES));
        assertTrue(naiveLeaks(SceneWorld.diamondRoom(), new double[]{45}) > 0, "point sampling leaks at 45°");
    }
    private static long naiveLeaks(SceneWorld s, double[] angles) {
        boolean[] sky = s.skyConnected();
        long n = 0;
        for (double angle : angles) for (int az = 0; az < 360; az += 5) for (SceneWorld.Surface c : s.sheltered())
            if (!s.open(sky, c.x(), c.y(), c.z()) && s.naiveRay(c.x() + 0.5, c.z() + 0.5, c.y(), angle, az, 24)) n++;
        return n;
    }

    @Test void givenARoomWithAHoleInTheWindwardWall_whenTheWindBlowsThroughIt_thenSnowGetsInAlongTheHole() {
        // proof.js gapRoom: open (face-connected), snow may come in along the hole's line
        SceneWorld s = new SceneWorld(20, 8, 16).ground();
        s.fill(5, 1, 3, 14, 4, 12, SceneWorld.PLANKS).fill(6, 1, 4, 13, 4, 11, SceneWorld.AIR).fill(4, 5, 2, 15, 5, 13, SceneWorld.PLANKS);
        s.set(5, 1, 7, SceneWorld.AIR);
        boolean any = false;
        for (int az = 0; az < 360; az += 5) for (SceneWorld.Surface c : s.sheltered())
            any |= WindRay.ray(s, c.x() + 0.5, c.z() + 0.5, c.y(), 20, az, 24, WindRay.FLAKE_HALF_WIDTH);
        assertTrue(any);
    }

    /** Reach table for admins: single ray floor(h * tan 20° + 0.45). */
    @Test void givenRoofsOfSeveralHeights_whenASingleRayIsTraced_thenTheReachIsTheTable() {
        int[] heights = {2, 3, 4, 5, 6, 8, 10, 12}, table = {1, 1, 1, 2, 2, 3, 4, 4};
        for (int i = 0; i < heights.length; i++) {
            int h = heights[i];
            assertEquals(table[i], WindRay.singleRayReach(h, 20), "formula h=" + h);
            assertEquals((int)Math.floor(h * 0.364 + 0.45), table[i]);
            SceneWorld s = SceneWorld.roof(h, 6);
            int edge = SceneWorld.roofEdge(6), reach = 0;
            while (reach < 7 && WindRay.ray(s, edge + reach + 0.5, 10.5, 1, 20, 270, 12, WindRay.FLAKE_HALF_WIDTH)) reach++;
            assertEquals(table[i], reach, "traced h=" + h);
        }
    }

    /** The cone from the study's model (reach.js scene, maxRise 12): free rays of 8 per cell from the windward edge. */
    @Test void givenRoofsOfSeveralHeights_whenTheConeIsTraced_thenTheSharesMatchTheModel() {
        int[] heights = {2, 3, 4, 5, 6, 8, 10, 12};
        int[][] free = {{6, 0, 0, 0, 0, 0, 0}, {8, 0, 0, 0, 0, 0, 0}, {8, 1, 0, 0, 0, 0, 0}, {8, 7, 0, 0, 0, 0, 0},
                {8, 8, 0, 0, 0, 0, 0}, {8, 8, 5, 0, 0, 0, 0}, {8, 8, 8, 3, 0, 0, 0}, {8, 8, 8, 3, 0, 0, 0}};
        WindRay.Wind wind = new WindRay.Wind(20, 270, 12);
        for (int i = 0; i < heights.length; i++) {
            SceneWorld s = SceneWorld.roof(heights[i], 6);
            int edge = SceneWorld.roofEdge(6);
            int[] got = new int[7];
            for (int d = 0; d < 7; d++) got[d] = WindRay.free(s, edge + d, 1, 10, wind);
            assertArrayEquals(free[i], got, "h=" + heights[i]);
            int reach = WindRay.singleRayReach(heights[i], 20);
            assertTrue(got[0] >= 6, "first cell >= 75%");
            if (reach < 7) assertTrue(got[reach] < 4, "the cell after the single-ray reach < 50%");
        }
    }

    /** Port check over whole scenes: per sheltered surface the cone's free rays, summed and hashed like the Node script. */
    @Test void givenTheStudysScenes_whenEverySurfaceIsTraced_thenTheResultEqualsTheModel() {
        WindRay.Wind wind = new WindRay.Wind(20, 270, 12);
        record Expect(SceneWorld world, int surfaces, int free, int withSnow, int hash) { }
        for (Expect e : List.of(new Expect(SceneWorld.roof(5, 3), 117, 181, 33, 864865971), new Expect(SceneWorld.porch(), 86, 53, 7, -4661068),
                new Expect(SceneWorld.trees(), 116, 190, 34, 1920150368), new Expect(SceneWorld.houses(), 181, 181, 22, -1313780994))) {
            int n = 0, sum = 0, snow = 0, hash = 0;
            for (SceneWorld.Surface c : e.world().sheltered()) {
                int f = WindRay.free(e.world(), c.x(), c.y(), c.z(), wind);
                sum += f;
                n++;
                if (f >= 2) snow++;
                hash = hash * 31 + f * 1000003 + c.x() * 7919 + c.y() * 104729 + c.z();
            }
            assertEquals(e.surfaces(), n);
            assertEquals(e.free(), sum);
            assertEquals(e.withSnow(), snow);
            assertEquals(e.hash(), hash);
        }
    }

    @Test void givenAnUnloadedChunkUpwind_whenARayWouldEnterIt_thenTheRayFails() {
        SceneWorld s = SceneWorld.roof(5, 6);
        int edge = SceneWorld.roofEdge(6);
        assertTrue(WindRay.ray(s, edge + 0.5, 10.5, 1, 20, 270, 12, WindRay.FLAKE_HALF_WIDTH));
        s.unloaded = (x, z) -> x < edge;
        assertFalse(WindRay.ray(s, edge + 0.5, 10.5, 1, 20, 270, 12, WindRay.FLAKE_HALF_WIDTH));
        assertEquals(0, WindRay.free(s, edge, 1, 10, new WindRay.Wind(20, 270, 12)));
    }

    @Test void givenAShortMaxHeight_whenTheRayNeedsMoreLayers_thenItFails() {
        SceneWorld s = SceneWorld.roof(8, 6);
        int edge = SceneWorld.roofEdge(6);
        // the third cell from the edge needs about 7 layers to leave the roof's shadow
        assertTrue(WindRay.ray(s, edge + 2.5, 10.5, 1, 20, 270, 12, WindRay.FLAKE_HALF_WIDTH));
        assertFalse(WindRay.ray(s, edge + 2.5, 10.5, 1, 20, 270, 3, WindRay.FLAKE_HALF_WIDTH));
    }

    @Test void givenTheWind_whenItsHorizontalReachIsAsked_thenItBoundsEveryRay() {
        assertEquals(7, WindRay.horizontalReach(new WindRay.Wind(20, 270, 12)));
        assertEquals(1, WindRay.horizontalReach(new WindRay.Wind(0, 270, 0)));
    }
}
