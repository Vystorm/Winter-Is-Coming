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
    private int snowColumns, frostColumns, waterSkips, cells, cellLimit;
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
        cellLimit = Math.max(1, searchBudget / 2);
        cells = 0;
        searched = false;
    }

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
