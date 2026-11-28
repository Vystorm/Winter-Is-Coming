package at.vystorm.winter.world;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * The snow pass's walk over the loaded chunks (1.27.0): one column per visit, chunk after chunk, alternating a record
 * visit (reconcile one tracked position) with a landscape column (lay snow, grow plants).
 * <p>
 * Two rules keep the work where something can change:
 * <ul>
 *   <li>record visits walk a cursor over the positions that still have to come back; given-up markers (a bulk edit or
 *       a player took the cell) cost no visit;</li>
 *   <li>a chunk with nothing to restore and nothing to sample costs no column ({@link PassBudget#idleSkip}); while the
 *       landscape half has nothing to do (a thaw), every visit of a chunk is a record visit.</li>
 * </ul>
 * Before, a record visit took index {@code (visits / 2) % records}. Each visit that removed the last layer of a cell
 * shrank the record count by one and so moved that index by about visits / records positions: on a server that had run
 * for a while the walk jumped over a block of remaining snow and found about one cell per lap, and a restart (counter
 * back to 0) cleared it at once. On top, every loaded chunk took the same share of the columns, also chunks without
 * records, and record visits went to given-up markers (measured in the stall: 96 % of the columns went to 203 chunks
 * without records, 99 % of the record visits to markers). Pure (no Bukkit), so the scheduling is unit-tested on its own.
 * <p>
 * The coastal frost walks with a second instance on its own budget ({@link #run(Budget, boolean, Chunks, Work)}): the
 * old frost cursor had the same {@code (visits / 2) % records} index, so ice whose shore went out of reach while the
 * winter fades melted at about one cell per lap. A frost visit whose shore search does not fit this tick is
 * {@link Work#deferred deferred}: the walk stays on that chunk, record and column for the next tick, as before.
 */
final class ChunkRounds<K> {
    /** The column and change allowance of one walk. */
    interface Budget {
        /** Another visit fits: a change is allowed and a column is taken. */
        boolean visit();
        /** The visited chunk had nothing to do: its column is given back (capped). */
        void idle();
        void changed();
        /** A deferred visit gives its column back. */
        void back();
    }
    /** The snow pass's share of the {@link PassBudget}. */
    static Budget snow(PassBudget b) {
        return new Budget() {
            @Override public boolean visit() { return b.mayChange() && b.snowColumn(); }
            @Override public void idle() { b.idleSkip(); }
            @Override public void changed() { b.changed(); }
            @Override public void back() { }
        };
    }
    /** The coastal frost's share: its own columns, the reserved part of the changes first ({@code reservedOnly}). */
    static Budget frost(PassBudget b, boolean reservedOnly) {
        return new Budget() {
            @Override public boolean visit() { return b.frostMayChange(reservedOnly) && b.frostColumn(); }
            @Override public void idle() { b.frostIdleSkip(); }
            @Override public void changed() { b.frostChanged(); }
            @Override public void back() { b.frostColumnBack(); }
        };
    }
    /** What the walk needs to know about a chunk. */
    interface Chunks<K> {
        boolean loaded(K chunk);
        /** Index of the first position at or after {@code from} still to restore, -1 when none. */
        int nextActive(K chunk, int from);
        /** The landscape half has something to do in this chunk now (snow to lay or plants to grow). */
        boolean samples(K chunk);
    }
    /** The work of one visit; each returns true when it planned a change. */
    interface Work<K> {
        boolean record(K chunk, int index);
        /** @param turn the chunk's visit counter, as the landscape columns have always been chosen from it */
        boolean landscape(K chunk, int turn);
        /** Asked after each visit: true = the visit could not be done this tick and is repeated first next tick. */
        default boolean deferred() { return false; }
    }

    private final ArrayDeque<K> order = new ArrayDeque<>();
    private final Set<K> queued = new HashSet<>();
    private final Map<K, Integer> turns = new HashMap<>();
    private final Map<K, Integer> cursors = new HashMap<>();

    /** Adds a chunk at the end of the round (once). */
    void add(K chunk) {
        if (queued.add(chunk)) order.addLast(chunk);
    }
    int size() {
        return order.size();
    }

    /**
     * Visits chunks while the budget has columns and changes.
     * @param touches false = the plugin's snow stays as it is (snow.enabled off): no record visits
     */
    void run(PassBudget budget, boolean touches, Chunks<K> chunks, Work<K> work) {
        run(snow(budget), touches, chunks, work);
    }
    /** The same walk on any budget (the frost's). */
    void run(Budget budget, boolean touches, Chunks<K> chunks, Work<K> work) {
        while (!order.isEmpty() && budget.visit()) {
            K ref = order.removeFirst();
            if (!chunks.loaded(ref)) {
                queued.remove(ref);
                turns.remove(ref);
                cursors.remove(ref);
                continue;
            }
            order.addLast(ref);
            boolean samples = chunks.samples(ref);
            int at = touches ? chunks.nextActive(ref, cursors.getOrDefault(ref, 0)) : -1;
            if (at < 0 && !samples) {
                budget.idle();
                continue;
            }
            int turn = turns.merge(ref, 1, Integer::sum) - 1;
            Integer cursor = cursors.get(ref);
            boolean changed;
            if (at >= 0 && (!samples || turn % 2 == 0)) {
                cursors.put(ref, at + 1);
                changed = work.record(ref, at);
            } else changed = samples && work.landscape(ref, turn);
            if (work.deferred()) {
                // Back to this very chunk, record and column next tick.
                turns.put(ref, turn);
                if (cursor == null) cursors.remove(ref);
                else cursors.put(ref, cursor);
                order.removeLast();
                order.addFirst(ref);
                budget.back();
                return;
            }
            if (changed) budget.changed();
        }
    }
}
