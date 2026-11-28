package at.vystorm.winter.world;

import java.util.*;
import java.util.function.Consumer;

/**
 * Clears the plugin's snow and ice inside snow-free circles ({@link BuildAreas}, 1.28.0): per circle the tracked
 * positions of the loaded chunks are collected once and visited nearest column first (the top of a column before the
 * cell below), so the snow goes ring by ring from the player outwards. Its own change allowance per tick on top of the
 * normal passes; a position that could not change yet (a change still in flight, snow above it) is looked at again in
 * a later sweep of the same circle, at most {@link #MAX_SWEEPS} sweeps. Never loads a chunk. Tested without a server.
 */
public final class ClearSweep {
    /** Sweeps per circle at most (the first one plus the repeats for positions that had to wait). */
    public static final int MAX_SWEEPS = 4;
    /** Ticks between two sweeps of a circle, so the changes of the last one are in the world. */
    static final int SWEEP_PAUSE_TICKS = 2;
    /** Circles waiting at most; a new one over the limit replaces the oldest. */
    static final int MAX_JOBS = 512;

    public enum Visit { CHANGED, DONE, WAIT }

    /** The world side: the tracked positions of a circle in loaded chunks and one visit of a position. */
    public interface Cells {
        /** x, y, z of every tracked position inside the circle whose chunk is loaded. */
        List<int[]> collect(BuildAreas.Clear circle);
        Visit visit(UUID world, int x, int y, int z);
    }

    /** A circle that was cleared: how many changes it took and in how many ticks. */
    public record Done(BuildAreas.Clear circle, int changed, long ticks) {}

    private static final class Job {
        final BuildAreas.Clear circle;
        final Consumer<Done> done;
        final long started;
        List<int[]> cells;
        int cursor, sweeps, changed;
        boolean waited;
        long restUntil;
        Job(BuildAreas.Clear circle, Consumer<Done> done, long started) {
            this.circle = circle;
            this.done = done;
            this.started = started;
        }
    }

    private final ArrayDeque<Job> jobs = new ArrayDeque<>();
    private long tick;
    private long changedTotal;

    /** Queues a circle; {@code done} (may be null) hears when it is clear. */
    public void add(BuildAreas.Clear circle, Consumer<Done> done) {
        if (jobs.size() >= MAX_JOBS) jobs.pollFirst();
        jobs.addLast(new Job(circle, done, tick));
    }

    public boolean idle() { return jobs.isEmpty(); }

    public int waiting() { return jobs.size(); }

    /** Changes planned by all sweeps since the start (status). */
    public long changedTotal() { return changedTotal; }

    /**
     * One tick: at most {@code changes} changes and {@code visits} position visits over the waiting circles, oldest
     * first. Returns the changes planned.
     */
    public int run(int changes, int visits, Cells cells) {
        tick++;
        if (jobs.isEmpty()) return 0;
        int planned = 0;
        int rounds = jobs.size();
        while (!jobs.isEmpty() && planned < changes && visits > 0 && rounds-- > 0) {
            Job job = jobs.pollFirst();
            if (job.restUntil > tick) {
                jobs.addLast(job);
                continue;
            }
            if (job.cells == null) {
                job.cells = new ArrayList<>(cells.collect(job.circle));
                job.cells.sort(BuildAreas.nearestFirst(job.circle.x(), job.circle.z()));
                job.cursor = 0;
                job.waited = false;
                visits -= Math.max(1, job.cells.size() / 16);
            }
            while (job.cursor < job.cells.size() && planned < changes && visits > 0) {
                int[] p = job.cells.get(job.cursor++);
                visits--;
                switch (cells.visit(job.circle.world(), p[0], p[1], p[2])) {
                    case CHANGED -> {
                        planned++;
                        job.changed++;
                    }
                    case WAIT -> job.waited = true;
                    case DONE -> { }
                }
            }
            if (job.cursor < job.cells.size()) {
                jobs.addFirst(job);   // budget ran out: this circle goes on next tick
                break;
            }
            if (job.waited && ++job.sweeps < MAX_SWEEPS) {
                job.cells = null;
                job.restUntil = tick + SWEEP_PAUSE_TICKS;
                jobs.addLast(job);
                continue;
            }
            if (job.done != null) job.done.accept(new Done(job.circle, job.changed, tick - job.started));
        }
        changedTotal += planned;
        return planned;
    }

    /** Drops every waiting circle (build mode switched off). */
    public void clear() { jobs.clear(); }
}
