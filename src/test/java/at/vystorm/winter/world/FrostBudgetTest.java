package at.vystorm.winter.world;

import static at.vystorm.winter.world.FrostBudget.Cell.BLOCKED;
import static at.vystorm.winter.world.FrostBudget.Cell.SHORE;
import static at.vystorm.winter.world.FrostBudget.Cell.UNKNOWN;
import static at.vystorm.winter.world.FrostBudget.Cell.WATER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class FrostBudgetTest {
    @Test
    void findsAdjacentShoreAndCountsWaterSteps() {
        assertEquals(1, FrostBudget.distance(0, 0, 4, 30,
                (x, z) -> x == 0 && z == 0 ? WATER : x == 1 && z == 0 ? SHORE : BLOCKED));

        Map<String, FrostBudget.Cell> cells = new HashMap<>();
        cells.put("0,0", WATER);
        cells.put("1,0", WATER);
        cells.put("2,0", WATER);
        cells.put("3,0", SHORE);
        assertEquals(3, FrostBudget.distance(0, 0, 4, 30,
                (x, z) -> cells.getOrDefault(key(x, z), BLOCKED)));
    }

    @Test
    void blockedAndUnknownCellsAreNotTraversedButOtherKnownPathsRemainUsable() {
        Map<String, FrostBudget.Cell> cells = new HashMap<>();
        cells.put("0,0", WATER);
        cells.put("1,0", UNKNOWN);
        cells.put("-1,0", BLOCKED);
        cells.put("0,1", WATER);
        cells.put("0,2", SHORE);
        assertEquals(2, FrostBudget.distance(0, 0, 4, 30,
                (x, z) -> cells.getOrDefault(key(x, z), BLOCKED)));
    }

    @Test
    void doesNotTraverseShoreAndHonorsDistanceAndVisitBudgets() {
        Map<String, FrostBudget.Cell> cells = new HashMap<>();
        cells.put("0,0", WATER);
        cells.put("1,0", SHORE);
        cells.put("2,0", WATER);
        cells.put("3,0", SHORE);
        assertEquals(1, FrostBudget.distance(0, 0, 4, 30,
                (x, z) -> cells.getOrDefault(key(x, z), BLOCKED)));
        assertEquals(-1, FrostBudget.distance(0, 0, 0, 1,
                (x, z) -> cells.getOrDefault(key(x, z), BLOCKED)));
        assertEquals(-1, FrostBudget.distance(0, 0, 4, 1,
                (x, z) -> cells.getOrDefault(key(x, z), BLOCKED)));
    }

    @Test
    void queriesEachCellAtMostOnceAndNeverExceedsVisitLimit() {
        Map<String, Integer> calls = new HashMap<>();
        int result = FrostBudget.distance(0, 0, 20, 13, (x, z) -> {
            String key = key(x, z);
            calls.merge(key, 1, Integer::sum);
            return WATER;
        });
        assertEquals(-1, result);
        assertEquals(13, calls.size());
        assertEquals(1, calls.values().stream().mapToInt(Integer::intValue).max().orElseThrow());
    }

    @Test
    void validatesSearchBoundsAndSurface() {
        assertThrows(IllegalArgumentException.class, () -> FrostBudget.distance(0, 0, -1, 1, (x, z) -> WATER));
        assertThrows(IllegalArgumentException.class, () -> FrostBudget.distance(0, 0, 129, 1, (x, z) -> WATER));
        assertThrows(IllegalArgumentException.class, () -> FrostBudget.distance(0, 0, 0, 0, (x, z) -> WATER));
        assertThrows(IllegalArgumentException.class, () -> FrostBudget.distance(0, 0, 0, 65_537, (x, z) -> WATER));
        assertThrows(NullPointerException.class, () -> FrostBudget.distance(0, 0, 0, 1, null));
    }

    private static String key(int x, int z) {
        return x + "," + z;
    }
}
