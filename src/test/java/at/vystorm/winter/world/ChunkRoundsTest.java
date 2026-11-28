package at.vystorm.winter.world;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

/**
 * The snow pass's walk over the loaded chunks (1.27.0 thaw stall on Paper 1.20.6): with a few hundred extra chunks
 * loaded that hold only given-up markers, the last snow of a thaw must still get its visits.
 */
class ChunkRoundsTest {
    /** A chunk's records: true = still to restore (with layers left), false = given-up marker. */
    static final class Chunk {
        final List<int[]> records = new ArrayList<>();   // {active 0/1, layers}
        boolean loaded = true;
        int landscapeTurns;
        Chunk markers(int n) { for (int i = 0; i < n; i++) records.add(new int[]{0, 0}); return this; }
        Chunk snow(int n, int layers) { for (int i = 0; i < n; i++) records.add(new int[]{1, layers}); return this; }
        int layers() { return records.stream().filter(r -> r[0] == 1).mapToInt(r -> r[1]).sum(); }
        /** One reconcile visit: one layer less; the last layer gone = the position leaves the records (forget). */
        boolean visit(int index) {
            int[] r = records.get(index);
            if (r[0] == 0) return false;
            if (--r[1] == 0) records.remove(index);
            return true;
        }
        int nextActive(int from) {
            int n = records.size();
            if (n == 0) return -1;
            int start = Math.floorMod(from, n);
            for (int i = 0; i < n; i++) if (records.get((start + i) % n)[0] == 1) return (start + i) % n;
            return -1;
        }
    }

    static final class Scene {
        final List<Chunk> chunks = new ArrayList<>();
        final ChunkRounds<Chunk> rounds = new ChunkRounds<>();
        boolean samples;
        final List<String> log = new ArrayList<>();
        Chunk add(Chunk c) { chunks.add(c); rounds.add(c); return c; }
        int layers() { return chunks.stream().mapToInt(Chunk::layers).sum(); }
        void tick(PassBudget b, boolean touches) {
            rounds.run(b, touches, new ChunkRounds.Chunks<>() {
                @Override public boolean loaded(Chunk c) { return c.loaded; }
                @Override public int nextActive(Chunk c, int from) { return c.nextActive(from); }
                @Override public boolean samples(Chunk c) { return samples; }
            }, new ChunkRounds.Work<>() {
                @Override public boolean record(Chunk c, int index) { log.add("r" + chunks.indexOf(c) + ":" + index); return c.visit(index); }
                @Override public boolean landscape(Chunk c, int turn) { log.add("l" + chunks.indexOf(c) + ":" + turn); c.landscapeTurns++; return false; }
            });
        }
    }

    /** The suite's settings: 8192 columns and 512 changes per 20-tick cycle. */
    static PassBudget suiteTick(PassBudget b) {
        b.tick(512, 20, 8192 / 20, 8192);
        return b;
    }

    /** The walk before 1.27.0, for comparison: every loaded chunk one column, even turns a record (markers too), odd turns idle during a thaw. */
    static int oldTicks(List<Chunk> chunks, int limit) {
        Map<Chunk, Integer> turns = new HashMap<>();
        ArrayDeque<Chunk> order = new ArrayDeque<>(chunks);
        PassBudget b = new PassBudget();
        for (int t = 1; t <= limit; t++) {
            suiteTick(b);
            while (b.mayChange() && b.snowColumn()) {
                Chunk c = order.removeFirst();
                order.addLast(c);
                int turn = turns.merge(c, 1, Integer::sum) - 1, n = c.records.size();
                if (turn % 2 == 0 && n > 0 && c.visit((turn / 2) % n)) b.changed();
            }
            if (chunks.stream().mapToInt(Chunk::layers).sum() == 0) return t;
        }
        return limit + 1;
    }

    /**
     * The stalled suite case (Paper 1.20.6, measured at the stall: 211 chunks in the round, 203 without records, the last
     * snow in 4 chunks with about 300 given-up markers each), plus 200 chunks holding markers only.
     */
    static Scene stalledThaw(Scene s) {
        for (int i = 0; i < 4; i++) s.add(new Chunk().markers(300).snow(48, 3));
        for (int i = 0; i < 203; i++) s.add(new Chunk());
        for (int i = 0; i < 200; i++) s.add(new Chunk().markers(450));
        return s;
    }

    @Test
    void theLastSnowOfAThawIsNotStarvedByChunksThatHoldOnlyGivenUpMarkers() {
        Scene s = stalledThaw(new Scene());
        int total = s.layers();
        assertEquals(16 * 12 * 3, total);
        PassBudget b = new PassBudget();
        int ticks = 0;
        while (s.layers() > 0 && ticks < 20 * 900) { s.tick(suiteTick(b), true); ticks++; }
        assertEquals(0, s.layers(), "the thaw finishes");
        // 576 layer steps at 25.6 changes per tick: the change allowance is the only limit left
        assertTrue(ticks <= 40, "about 23 ticks of changes; took " + ticks);

        Scene old = stalledThaw(new Scene());
        int oldTicks = oldTicks(old.chunks, 20 * 900);
        System.out.println("stalled thaw in small: new walk " + ticks + " ticks, old walk " + oldTicks + " ticks");
        assertTrue(oldTicks >= 20 * ticks, "the old walk needed " + oldTicks + " ticks for the same snow, the new " + ticks);
    }

    /**
     * The stall itself: the old cursor was {@code (visits / 2) % records}. Every hit on the last layer forgets its record,
     * the count shrinks by one and the cursor jumps by about visits / records positions, over the rest of a block of
     * remaining snow. After 25 minutes (about 33,000 visits per chunk) it found about one cell per lap; a restart set the
     * counter back to 0, which is why the stalled positions cleared at once after a restart.
     */
    @Test
    void theLastSnowBehindMarkersIsFoundInAboutOneLapAlthoughEveryHitForgetsItsRecord() {
        Scene s = new Scene();
        Chunk c = s.add(new Chunk().markers(256).snow(57, 1));
        PassBudget b = new PassBudget();
        int visits = 0;
        while (c.layers() > 0 && visits < 100_000) {
            b.tick(512, 1, 1, 8192);
            s.tick(b, true);
            visits++;
        }
        assertEquals(0, c.layers());
        assertTrue(visits <= 2 * 313, "a lap is 313 records; took " + visits + " visits");

        // the old cursor from the same state, with the counter a running server had reached
        Chunk old = new Chunk().markers(256).snow(57, 1);
        long counter = 33_073 / 2;
        int oldVisits = 0;
        while (old.layers() > 0 && oldVisits < 100_000) {
            old.visit((int)(counter++ % old.records.size()));
            oldVisits++;
        }
        System.out.println("last snow behind markers: new cursor " + visits + " record visits, old cursor " + oldVisits);
        assertTrue(oldVisits >= 10 * visits, "old cursor " + oldVisits + " record visits, new " + visits);
    }

    @Test
    void recordVisitsSkipGivenUpMarkersAndEveryVisitIsARecordVisitWhileNothingSamples() {
        Scene s = new Scene();
        Chunk c = s.add(new Chunk().markers(2).snow(1, 9).markers(3).snow(1, 9).markers(1));
        PassBudget b = new PassBudget();
        b.tick(512, 1, 6, 8192);
        s.tick(b, true);
        assertEquals(List.of("r0:2", "r0:6", "r0:2", "r0:6", "r0:2", "r0:6"), s.log, "only the two snow records, one after the other");
        assertEquals(0, c.landscapeTurns);
    }

    @Test
    void whileTheLandscapeSamplesRecordAndLandscapeVisitsAlternateAsBefore() {
        Scene s = new Scene();
        s.samples = true;
        s.add(new Chunk().markers(1).snow(1, 9));
        s.add(new Chunk().markers(5));
        PassBudget b = new PassBudget();
        b.tick(512, 1, 8, 8192);
        s.tick(b, true);
        // chunk 0: record on even turns, landscape on odd; chunk 1 (markers only): a landscape column every visit
        assertEquals(List.of("r0:1", "l1:0", "l0:1", "l1:1", "r0:1", "l1:2", "l0:3", "l1:3"), s.log);
    }

    @Test
    void chunksWithNothingToDoCostNoColumnButTheWalkStillEnds() {
        Scene s = new Scene();
        for (int i = 0; i < 5; i++) s.add(new Chunk().markers(10));
        Chunk snowy = s.add(new Chunk().snow(1, 100));
        PassBudget b = new PassBudget();
        b.tick(512, 1, 10, 8192);
        s.tick(b, true);
        // 10 columns all go to the one snowy chunk; the 5 idle chunks pass 10 times within the 80 free skips
        assertEquals(10, s.log.size());
        assertEquals(90, snowy.layers());
        // only idle chunks: the walk ends (skips run out, then the columns), nothing is visited
        snowy.records.clear();
        s.log.clear();
        b.tick(512, 1, 10, 8192);
        s.tick(b, true);
        assertTrue(s.log.isEmpty());
        assertFalse(b.snowColumn(), "the columns were used up after the free skips");
    }

    @Test
    void anUnloadedChunkLeavesTheRoundAndComesBackWhenAddedAgain() {
        Scene s = new Scene();
        Chunk a = s.add(new Chunk().snow(1, 50));
        Chunk gone = s.add(new Chunk().snow(1, 50));
        gone.loaded = false;
        PassBudget b = new PassBudget();
        b.tick(512, 1, 4, 8192);
        s.tick(b, true);
        assertEquals(1, s.rounds.size());
        assertEquals(50, gone.layers());
        assertEquals(47, a.layers(), "three of the four columns: the one that met the unloaded chunk is spent, as before");
        gone.loaded = true;
        s.rounds.add(gone);
        s.rounds.add(gone);
        assertEquals(2, s.rounds.size(), "added once");
    }

    @Test
    void withoutTouchingTheSnowNoRecordIsVisited() {
        Scene s = new Scene();
        Chunk c = s.add(new Chunk().snow(3, 2));
        PassBudget b = new PassBudget();
        b.tick(512, 1, 5, 8192);
        s.tick(b, false);
        assertTrue(s.log.isEmpty());
        assertEquals(6, c.layers());
    }
}
