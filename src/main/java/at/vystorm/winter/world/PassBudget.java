package at.vystorm.winter.world;

/**
 * Per-tick budgets of the terrain pass (1.24.2). Snow and coastal frost walk the chunks with their own cursors and their
 * own column limits; they share the change allowance, with a reserved share for the frost so neither can starve the
 * other. Shore searches have their own cell budget per tick: a search no longer ends the snow pass of that tick.
 */
public final class PassBudget {
    /** Part of the change allowance the frost may take while the snow also wants changes. */
    static final int FROST_SHARE_DIVISOR = 4;

    private double changes;
    private double frostShare;
    private int snowColumns, frostColumns, waterSkips, idleSkips, frostIdleSkips, cells, cellLimit;
    private boolean searched;

    /** Starts a tick: earns changes, resets the column and cell limits. */
    public void tick(int maxChanges, int period, int columnsThisTick, int searchBudget) {
        int max = Math.max(1, maxChanges);
        changes = Math.min(changes + (double)maxChanges / period, max);
        double share = Math.max(1, max / (double)FROST_SHARE_DIVISOR);
        frostShare = Math.min(frostShare + (double)maxChanges / period / FROST_SHARE_DIVISOR, share);
        snowColumns = columnsThisTick;
        frostColumns = columnsThisTick;
        waterSkips = columnsThisTick * 8;
        idleSkips = columnsThisTick * 8;
        frostIdleSkips = columnsThisTick * 8;
        cellLimit = Math.max(1, searchBudget / 2);
        cells = 0;
        searched = false;
        rays = RAYS_PER_TICK;
    }

    /** Rays of wind-blown snow per tick (1.25.0): sixteen sheltered block tops of eight rays each. */
    public static final int RAYS_PER_TICK = 128;
    private int rays;
    /** One more sheltered block top (a cone of rays) fits into this tick. */
    public boolean mayTrace() { return rays >= WindRay.CONE_RAYS; }
    public void traced(int count) { rays -= count; }
    public int rays() { return rays; }

    public boolean mayChange() { return changes >= 1; }
    public void changed() { changes--; }
    public double changes() { return changes; }

    /** The frost's reserved turn: allowed while the frost share and the common allowance both hold a change. */
    public boolean frostMayChange(boolean reservedOnly) {
        return changes >= 1 && (!reservedOnly || frostShare >= 1);
    }
    public void frostChanged() {
        changes--;
        frostShare = Math.max(0, frostShare - 1);
    }

    public boolean snowColumn() {
        if (snowColumns <= 0) return false;
        snowColumns--;
        return true;
    }
    public boolean frostColumn() {
        if (frostColumns <= 0) return false;
        frostColumns--;
        return true;
    }
    /**
     * The snow pass met still water, which belongs to the frost: the column is given back, up to eight times the column
     * limit per tick, so coasts do not use up the snow's columns (looking at the top block is cheap).
     */
    public boolean waterSkip() {
        if (waterSkips <= 0) return false;
        waterSkips--;
        snowColumns++;
        return true;
    }
    /**
     * The snow pass met a chunk with nothing to restore and nothing to sample (during a thaw: only given-up markers or
     * no records): the column is given back, up to eight times the column limit per tick (1.27.0). Without it every
     * loaded chunk took the same share of the columns, and a few hundred idle chunks starved the thaw of the rest.
     */
    public boolean idleSkip() {
        if (idleSkips <= 0) return false;
        idleSkips--;
        snowColumns++;
        return true;
    }
    /**
     * The frost met a chunk with no record to look at and no water to freeze (a thaw, outside the winter, an excluded
     * world): the column is given back like {@link #idleSkip}, with its own cap of eight times the column limit (1.27.0).
     */
    public boolean frostIdleSkip() {
        if (frostIdleSkips <= 0) return false;
        frostIdleSkips--;
        frostColumns++;
        return true;
    }
    /** Gives back a column the frost looked at but could not search this tick (the cursor goes back as well). */
    public void frostColumnBack() { frostColumns++; }

    /** A search that may look at up to {@code bound} cells fits this tick; the first search of a tick always fits. */
    public boolean maySearch(int bound) {
        return !searched || cells + bound <= cellLimit;
    }
    public void searched(int visited) {
        searched = true;
        cells += visited;
    }
    public int cells() { return cells; }
}
