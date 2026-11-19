package at.vystorm.winter.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * 1.24.2 searches only as far as the current frost radius. The ice that forms and the ice that melts must be exactly
 * what the full search to the radius setting decided before.
 */
class FrostDecisionTest {
    /** Random coast: water with islands, shores and unloaded patches, the same every run. */
    private static FrostBudget.Surface coast(long seed, int size) {
        Random random = new Random(seed);
        FrostBudget.Cell[][] cells = new FrostBudget.Cell[size][size];
        double land = 0.02 + random.nextDouble() * 0.2;
        for (int x = 0; x < size; x++) for (int z = 0; z < size; z++) {
            double r = random.nextDouble();
            cells[x][z] = r < land ? FrostBudget.Cell.SHORE : r < land * 1.5 ? FrostBudget.Cell.BLOCKED
                    : r < land * 1.6 ? FrostBudget.Cell.UNKNOWN : FrostBudget.Cell.WATER;
        }
        // a straight coast on one side, like an ocean beach
        for (int x = 0; x < size; x++) for (int z = 0; z < 3; z++) cells[x][z] = FrostBudget.Cell.SHORE;
        return (x, z) -> x < 0 || z < 0 || x >= size || z >= size ? FrostBudget.Cell.UNKNOWN : cells[x][z];
    }

    @Test
    void freezingAndMeltingDecideExactlyLikeTheFullSearch() {
        int checked = 0, froze = 0, melted = 0, kept = 0;
        for (long seed = 1; seed <= 60; seed++) {
            int size = 96;
            FrostBudget.Surface surface = coast(seed, size);
            Random random = new Random(seed * 31);
            for (int i = 0; i < 120; i++) {
                int x = random.nextInt(size), z = random.nextInt(size);
                int max = new int[]{4, 12, 40, 64}[random.nextInt(4)];
                int radius = random.nextInt(max + 1);
                int budget = random.nextInt(3) == 0 ? 1 + random.nextInt(400) : 8192;
                int full = FrostBudget.distance(x, z, max, budget, surface);
                boolean freezeBefore = radius >= 1 && full > 0 && full <= radius;
                var freeze = FrostBudget.freezes(x, z, radius, budget, surface);
                boolean freezeNow = freeze.distance() > 0 && freeze.distance() <= radius;
                assertEquals(freezeBefore, freezeNow, "freeze seed " + seed + " at " + x + "," + z + " r" + radius + " max" + max + " budget" + budget);
                boolean meltBefore = full > radius;
                var melt = FrostBudget.meltDistance(x, z, radius, max, budget, surface);
                assertEquals(meltBefore, melt.distance() > radius, "melt seed " + seed + " at " + x + "," + z + " r" + radius + " max" + max + " budget" + budget);
                assertTrue(freeze.visited() <= FrostBudget.bound(radius, budget) || radius < 1, "freeze bound");
                assertTrue(melt.visited() <= FrostBudget.bound(radius, budget) + FrostBudget.bound(max, budget), "melt bound");
                checked++;
                if (freezeNow) froze++;
                if (meltBefore) melted++; else kept++;
            }
        }
        // the random coasts cover all three outcomes
        assertTrue(froze > 100 && melted > 100 && kept > 100, checked + " checked, " + froze + " froze, " + melted + " melted, " + kept + " kept");
    }

    @Test
    void lowFrostLooksAtFarFewerCells() {
        FrostBudget.Surface open = (x, z) -> z < 0 ? FrostBudget.Cell.SHORE : FrostBudget.Cell.WATER;
        // 60 blocks out on open water, ocean radius 40, frost radius 5: the full search walked ~3300 cells for nothing
        var before = FrostBudget.search(0, 60, 40, 8192, open);
        var now = FrostBudget.freezes(0, 60, 5, 8192, open);
        assertEquals(-1, now.distance());
        assertEquals(-1, before.distance());
        assertTrue(now.visited() * 10 < before.visited(), now.visited() + " vs " + before.visited());
    }
}
