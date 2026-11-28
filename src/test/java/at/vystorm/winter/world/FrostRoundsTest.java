package at.vystorm.winter.world;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

/**
 * The coastal frost on {@link ChunkRounds} (1.27.0). Its old cursor was {@code (visits / 2) % records} like the snow's:
 * a chunk's records are sorted by position (y first), so the ice of one water level is one block of records, and each
 * melted ice cell shrank the count and moved the index over the rest of that block. Freezing keeps its columns.
 */
class FrostRoundsTest {
    /** Records of one chunk: 's' snow (the frost looks and leaves it), 'i' ice whose shore went out of reach, 'm' given-up marker. */
    static final class Chunk {
        final List<Character> records = new ArrayList<>();
        boolean loaded = true;
        Chunk add(char kind, int n) { for (int i = 0; i < n; i++) records.add(kind); return this; }
        long ice() { return records.stream().filter(c -> c == 'i').count(); }
        /** One frost record visit: ice out of reach melts and leaves the records, anything else stays. */
        boolean visit(int index) {
            if (records.get(index) != 'i') return false;
            records.remove(index);
            return true;
        }
        int nextActive(int from) {
            int n = records.size();
            if (n == 0) return -1;
            int start = Math.floorMod(from, n);
            for (int i = 0; i < n; i++) if (records.get((start + i) % n) != 'm') return (start + i) % n;
            return -1;
        }
    }

    static final class Scene {
        final List<Chunk> chunks = new ArrayList<>();
        final ChunkRounds<Chunk> rounds = new ChunkRounds<>();
        boolean freezing = true;
        /** Landscape visits that ask for a search that does not fit: deferred once each. */
        final Set<String> deferOnce = new HashSet<>();
        final List<String> log = new ArrayList<>();
        Chunk add(Chunk c) { chunks.add(c); rounds.add(c); return c; }
        void tick(PassBudget b, boolean reservedOnly) {
            boolean[] deferred = {false};
            rounds.run(ChunkRounds.frost(b, reservedOnly), true, new ChunkRounds.Chunks<>() {
                @Override public boolean loaded(Chunk c) { return c.loaded; }
                @Override public int nextActive(Chunk c, int from) { return c.nextActive(from); }
                @Override public boolean samples(Chunk c) { return freezing; }
            }, new ChunkRounds.Work<>() {
                @Override public boolean record(Chunk c, int index) {
                    deferred[0] = false;
                    log.add("r" + chunks.indexOf(c) + ":" + index);
                    return c.visit(index);
                }
                @Override public boolean landscape(Chunk c, int turn) {
                    String id = "l" + chunks.indexOf(c) + ":" + turn;
                    deferred[0] = deferOnce.remove(id);
                    log.add(deferred[0] ? id + "(later)" : id);
                    return false;
                }
                @Override public boolean deferred() { return deferred[0]; }
            });
        }
    }

    /** A lake chunk late in the winter: 700 snow records on land, 120 ice cells at the water level, 80 markers. */
    static Chunk lake() {
        return new Chunk().add('s', 300).add('m', 80).add('i', 120).add('s', 400);
    }

    /** The old frost cursor: even visits take record (visits / 2) % records, odd visits freeze. */
    static int oldVisits(Chunk c, long counter, int limit) {
        int visits = 0;
        while (c.ice() > 0 && visits < limit) {
            long offset = counter++;
            visits++;
            if (offset % 2 == 0 && !c.records.isEmpty()) c.visit((int)((offset / 2) % c.records.size()));
        }
        return visits;
    }

    @Test
    void iceOutOfReachMeltsInAFewLapsWhileTheFreezingColumnsGoOn() {
        Scene s = new Scene();
        Chunk c = s.add(lake());
        int lap = c.records.size() - 80; // markers cost no visit
        PassBudget b = new PassBudget();
        int visits = 0;
        while (c.ice() > 0 && visits < 1_000_000) {
            b.tick(512, 1, 1, 8192);
            s.tick(b, false);
            visits++;
        }
        assertEquals(0, c.ice());
        // A melted cell leaves the records, so the cursor passes over its neighbour: a block of ice about halves per lap
        // (the snow records still cost the frost a visit each; measured 9233 visits, under six laps).
        assertTrue(visits <= 2 * 8 * lap, "record visits every second visit, a lap is " + lap + " records; took " + visits);

        int fresh = oldVisits(lake(), 0, 10_000_000);
        int running = oldVisits(lake(), 200_000, 10_000_000);
        System.out.println("ice out of reach: new walk " + visits + " visits, old cursor " + fresh + " (counter 0), " + running + " (counter 200000)");
        // From a fresh counter the old cursor was about three times slower, on a server that had run for a while more
        // than ten times (the counter is never reset while the chunk stays loaded).
        assertTrue(fresh >= 2 * visits, "old cursor from 0: " + fresh + ", new " + visits);
        assertTrue(running >= 10 * visits, "old cursor after a while: " + running + ", new " + visits);
    }

    /** Freezing keeps its order: the same columns (turn / 2) at the same visits as the old cursor. */
    @Test
    void theFreezingColumnsAreTheSameAsBefore() {
        Scene s = new Scene();
        s.add(new Chunk().add('s', 5));   // records to look at: freezing on odd turns
        s.add(new Chunk());               // no records: every visit freezes (each column twice, as before)
        PassBudget b = new PassBudget();
        b.tick(512, 1, 12, 8192);
        s.tick(b, false);
        assertEquals(List.of("r0:0", "l1:0", "l0:1", "l1:1", "r0:1", "l1:2", "l0:3", "l1:3", "r0:2", "l1:4", "l0:5", "l1:5"), s.log);

        // the old frost on the same two chunks: offset per chunk, even = record if any, otherwise a freezing column offset / 2
        List<String> old = new ArrayList<>();
        int[] offsets = new int[2];
        int[] records = {5, 0};
        for (int v = 0; v < 12; v++) {
            int c = v % 2, offset = offsets[c]++;
            old.add(offset % 2 == 0 && records[c] > 0 ? "r" + c : "l" + c + ":" + offset);
        }
        List<String> now = new ArrayList<>();
        for (String e : s.log) now.add(e.startsWith("r") ? e.substring(0, 2) : e);
        assertEquals(old, now, "same visits, same freezing turns");
    }

    @Test
    void aSearchThatDoesNotFitStaysOnItsColumnForTheNextTick() {
        Scene s = new Scene();
        s.add(new Chunk().add('s', 3));
        s.add(new Chunk().add('s', 3));
        s.deferOnce.add("l1:1");
        PassBudget b = new PassBudget();
        b.tick(512, 1, 6, 8192);
        s.tick(b, false);
        assertEquals(List.of("r0:0", "r1:0", "l0:1", "l1:1(later)"), s.log, "the tick ends at the deferred search");
        assertTrue(b.frostColumn() && b.frostColumn() && b.frostColumn(), "the deferred column was given back: 6 - 4 + 1");
        assertFalse(b.frostColumn());
        s.log.clear();
        b.tick(512, 1, 3, 8192);
        s.tick(b, false);
        assertEquals(List.of("l1:1", "r0:1", "r1:1"), s.log, "first the same chunk and column again, then the round goes on");
    }

    @Test
    void duringAThawEveryVisitLooksAtARecordAndIdleChunksCostNoFrostColumn() {
        Scene s = new Scene();
        s.freezing = false;
        for (int i = 0; i < 5; i++) s.add(new Chunk().add('m', 10));
        Chunk c = s.add(new Chunk().add('i', 4));
        PassBudget b = new PassBudget();
        b.tick(512, 1, 4, 8192);
        s.tick(b, false);
        assertEquals(0, c.ice(), "four columns, all on the one chunk with records, although five idle chunks lie between");
        assertEquals(4, s.log.size());
        assertTrue(s.log.stream().allMatch(e -> e.startsWith("r5:")), s.log.toString());
    }

    @Test
    void theReservedShareLimitsTheFirstFrostTurn() {
        Scene s = new Scene();
        Chunk c = s.add(new Chunk().add('i', 100));
        s.freezing = false;
        PassBudget b = new PassBudget();
        b.tick(16, 1, 100, 8192);
        s.tick(b, true);
        assertEquals(96, c.ice(), "a quarter of the 16 changes in the reserved turn");
        s.tick(b, false);
        assertEquals(84, c.ice(), "the rest of the allowance afterwards");
    }
}
