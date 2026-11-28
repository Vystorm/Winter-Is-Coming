package at.vystorm.winter.world;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

/** Snow-free building (1.28.0): the sweep that clears a circle ring by ring within its own allowance per tick. */
class ClearSweepTest {
    static final UUID WORLD = UUID.randomUUID();

    /** A world of tracked positions: a visit restores a position, unless it has to wait (snow above it, in flight). */
    static final class Cells implements ClearSweep.Cells {
        final Set<List<Integer>> tracked = new LinkedHashSet<>();
        final Map<List<Integer>, Integer> waits = new HashMap<>();
        final List<List<Integer>> visited = new ArrayList<>();
        int collects;
        void add(int x, int y, int z) { tracked.add(List.of(x, y, z)); }
        @Override public List<int[]> collect(BuildAreas.Clear c) {
            collects++;
            List<int[]> out = new ArrayList<>();
            for (List<Integer> p : tracked) {
                long dx = p.get(0) - c.x(), dz = p.get(2) - c.z();
                if (dx * dx + dz * dz <= (long) c.radius() * c.radius()) out.add(new int[]{p.get(0), p.get(1), p.get(2)});
            }
            return out;
        }
        @Override public ClearSweep.Visit visit(UUID world, int x, int y, int z) {
            List<Integer> p = List.of(x, y, z);
            visited.add(p);
            if (!tracked.contains(p)) return ClearSweep.Visit.DONE;
            int w = waits.getOrDefault(p, 0);
            if (w > 0) {
                waits.put(p, w - 1);
                return ClearSweep.Visit.WAIT;
            }
            tracked.remove(p);
            return ClearSweep.Visit.CHANGED;
        }
    }

    /** A full-winter circle of radius r: one snow block per column, two in every fourth column. */
    static Cells winter(int r) {
        Cells cells = new Cells();
        for (int x = -r - 4; x <= r + 4; x++) for (int z = -r - 4; z <= r + 4; z++) {
            cells.add(x, 64, z);
            if (Math.floorMod(x + z, 4) == 0) cells.add(x, 65, z);
        }
        return cells;
    }

    @Test void givenAFullWinterCircleOfRadius32_whenSwept_thenClearedNearestFirstWithinTheAllowanceAndNothingOutside() {
        Cells cells = winter(32);
        int inside = cells.collect(new BuildAreas.Clear(WORLD, 0, 0, 32)).size();
        cells.collects = 0;
        ClearSweep sweep = new ClearSweep();
        List<ClearSweep.Done> done = new ArrayList<>();
        sweep.add(new BuildAreas.Clear(WORLD, 0, 0, 32), done::add);
        int ticks = 0;
        long lastDistance = -1;
        while (!sweep.idle() && ticks < 10_000) {
            int before = cells.visited.size();
            int planned = sweep.run(64, 512, cells);
            assertTrue(planned <= 64, "never more than the allowance");
            for (List<Integer> p : cells.visited.subList(before, cells.visited.size())) {
                long d = (long) p.get(0) * p.get(0) + (long) p.get(2) * p.get(2);
                assertTrue(d >= lastDistance, "ring by ring outwards");
                lastDistance = d;
            }
            ticks++;
        }
        assertEquals(1, done.size());
        assertEquals(inside, done.get(0).changed());
        assertEquals((inside + 63) / 64, ticks, "every tick full: " + inside + " positions");
        assertEquals(1, cells.collects, "one collect when nothing had to wait");
        for (List<Integer> p : cells.tracked) assertTrue((long) p.get(0) * p.get(0) + (long) p.get(2) * p.get(2) > 32 * 32, "outside stays: " + p);
        assertEquals(inside, sweep.changedTotal());
        // 64 per tick = 1280 per second: a radius-32 circle of full winter (about 4000 positions here) in about 3 s
        assertTrue(ticks <= 70, ticks + " ticks");
    }

    @Test void givenPositionsThatMustWait_whenSwept_thenALaterSweepTakesThemAndItEndsAfterMaxSweeps() {
        Cells cells = new Cells();
        cells.add(0, 64, 0);
        cells.add(1, 64, 0);
        cells.add(2, 64, 0);
        cells.waits.put(List.of(1, 64, 0), 1);     // in flight once
        cells.waits.put(List.of(2, 64, 0), 100);   // never clears (a foreign block on top, say)
        ClearSweep sweep = new ClearSweep();
        List<ClearSweep.Done> done = new ArrayList<>();
        sweep.add(new BuildAreas.Clear(WORLD, 0, 0, 8), done::add);
        for (int tick = 0; tick < 50 && !sweep.idle(); tick++) sweep.run(64, 512, cells);
        assertTrue(sweep.idle());
        assertEquals(1, done.size());
        assertEquals(2, done.get(0).changed());
        assertEquals(Set.of(List.of(2, 64, 0)), cells.tracked);
        assertEquals(ClearSweep.MAX_SWEEPS, cells.collects);
    }

    @Test void givenTwoCircles_whenSwept_thenTheFirstFinishesFirstAndNothingRunsWhenIdle() {
        Cells cells = winter(8);
        for (int x = 100; x < 104; x++) cells.add(x, 64, 0);
        ClearSweep sweep = new ClearSweep();
        List<BuildAreas.Clear> order = new ArrayList<>();
        sweep.add(new BuildAreas.Clear(WORLD, 0, 0, 8), d -> order.add(d.circle()));
        sweep.add(new BuildAreas.Clear(WORLD, 101, 0, 3), d -> order.add(d.circle()));
        while (!sweep.idle()) sweep.run(16, 128, cells);
        assertEquals(List.of(new BuildAreas.Clear(WORLD, 0, 0, 8), new BuildAreas.Clear(WORLD, 101, 0, 3)), order);
        int visits = cells.visited.size();
        assertEquals(0, sweep.run(16, 128, cells));
        assertEquals(visits, cells.visited.size(), "idle: no visit, no collect");
    }
}
