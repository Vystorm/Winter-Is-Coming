package at.vystorm.winter.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** 1.24.2: snow and coastal frost have their own budgets, so neither starves the other. */
class PassBudgetTest {
    /** One tick the way WinterTerrain runs it: frost (reserved share), snow, frost again with what is left. */
    private static int[] tick(PassBudget b, boolean snowWants, boolean frostWants) {
        int frost = 0, snow = 0;
        while (frostWants && b.frostMayChange(true) && b.frostColumn()) { b.frostChanged(); frost++; }
        while (snowWants && b.mayChange() && b.snowColumn()) { b.changed(); snow++; }
        while (frostWants && b.frostMayChange(false) && b.frostColumn()) { b.frostChanged(); frost++; }
        return new int[]{frost, snow};
    }

    @Test
    void bothGetTheirShareWhenBothWantEveryChange() {
        PassBudget b = new PassBudget();
        int frost = 0, snow = 0;
        // defaults: 16 changes per 20-tick cycle, 96 columns per cycle
        for (int t = 0; t < 2000; t++) {
            b.tick(16, 20, 4, 8192);
            int[] r = tick(b, true, true);
            frost += r[0];
            snow += r[1];
        }
        int total = frost + snow;
        assertTrue(total >= 1590 && total <= 1601, "all 1600 changes are used: " + total);
        assertTrue(frost >= total / 5 && frost <= total * 3 / 10, "frost about a quarter: " + frost + " of " + total);
        assertTrue(snow >= total * 7 / 10, "snow keeps the rest: " + snow);
    }

    @Test
    void eachPassGetsEverythingWhenTheOtherHasNothingToDo() {
        PassBudget b = new PassBudget();
        int frostOnly = 0, snowOnly = 0;
        for (int t = 0; t < 200; t++) {
            b.tick(512, 20, 409, 8192);
            frostOnly += tick(b, false, true)[0];
        }
        for (int t = 0; t < 200; t++) {
            b.tick(512, 20, 409, 8192);
            snowOnly += tick(b, true, false)[1];
        }
        assertTrue(frostOnly >= 200 * 25, "frost alone: " + frostOnly);
        assertTrue(snowOnly >= 200 * 25, "snow alone: " + snowOnly);
    }

    @Test
    void columnLimitsAreSeparate() {
        PassBudget b = new PassBudget();
        b.tick(512, 20, 3, 8192);
        for (int i = 0; i < 3; i++) assertTrue(b.frostColumn());
        assertFalse(b.frostColumn());
        for (int i = 0; i < 3; i++) assertTrue(b.snowColumn(), "frost columns do not use up the snow columns");
        assertFalse(b.snowColumn());
        b.frostColumnBack();
        assertTrue(b.frostColumn(), "a deferred column is given back");
    }

    @Test
    void waterColumnsDoNotUseUpTheSnowColumnsButStayBounded() {
        PassBudget b = new PassBudget();
        b.tick(16, 20, 4, 8192);
        int visits = 0;
        // a coast: every column the snow pass meets is water
        while (b.snowColumn()) {
            visits++;
            b.waterSkip();
            assertTrue(visits < 1000, "bounded");
        }
        assertEquals(4 + 4 * 8, visits, "four columns plus eight water skips per column");
    }

    @Test
    void searchCellsHaveTheirOwnLimitAndTheFirstSearchAlwaysRuns() {
        PassBudget b = new PassBudget();
        b.tick(16, 20, 4, 8192);
        assertTrue(b.maySearch(50_000), "the first search of a tick always runs");
        b.searched(3_000);
        assertTrue(b.maySearch(1_000));
        b.searched(1_000);
        assertFalse(b.maySearch(200), "half the search budget per tick: 4096 cells");
        assertTrue(b.maySearch(96));
        assertEquals(4_000, b.cells());
        b.tick(16, 20, 4, 8192);
        assertEquals(0, b.cells());
        assertTrue(b.maySearch(4_000));
    }

    @Test
    void searchesDoNotUseTheChangeAllowance() {
        PassBudget b = new PassBudget();
        b.tick(20, 20, 4, 8192);
        b.searched(4_000);
        assertTrue(b.mayChange(), "a shore search leaves the snow its change");
        assertTrue(b.snowColumn());
    }
}
