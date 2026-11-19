package at.vystorm.winter.world;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/** Bounded four-neighbor search for the nearest known shoreline. */
public final class FrostBudget {
    private static final int MAX_ALLOWED_DISTANCE = 128;
    private static final int MAX_ALLOWED_VISITED = 65_536;

    private FrostBudget() { }

    /**
     * Finds the number of water steps from a water cell to the nearest shore.
     * Returns {@code -1} if no shore is reachable within the supplied bounds.
     */
    public static int distance(int x, int z, int maxDistance, int maxVisited, Surface surface) {
        return search(x, z, maxDistance, maxVisited, surface).distance();
    }

    /** Result of one search: the distance ({@code -1} = none) and how many cells it looked at. */
    public record Found(int distance, int visited) { }

    /** Cells a search up to {@code maxDistance} can look at at most: every cell within that many steps. */
    public static int bound(int maxDistance, int maxVisited) {
        long reach = Math.max(0, maxDistance);
        return (int)Math.min(maxVisited, 2 * reach * (reach + 1) + 1);
    }

    /**
     * Ice may form on this water cell when the result is a distance (a shore at most {@code radius} water steps away).
     * The search stops at the radius; breadth-first order makes this the same answer as a search to the full
     * radius setting followed by {@code 0 < d <= radius} (1.24.2), only with far fewer cells at low frost.
     */
    public static Found freezes(int x, int z, int radius, int maxVisited, Surface surface) {
        if (radius < 1) return new Found(-1, 0);
        return search(x, z, radius, maxVisited, surface);
    }

    /**
     * Distance that decides whether this ice melts ({@code d > radius} melts, {@code -1} keeps it, as before). Checks
     * the radius first; only ice outside it pays the full search to {@code maxDistance} (1.24.2). The result counts the
     * cells of both steps.
     */
    public static Found meltDistance(int x, int z, int radius, int maxDistance, int maxVisited, Surface surface) {
        int near = Math.min(Math.max(radius, 0), maxDistance);
        Found first = search(x, z, near, maxVisited, surface);
        if (first.distance() != -1) return first;
        Found full = search(x, z, maxDistance, maxVisited, surface);
        return new Found(full.distance(), first.visited() + full.visited());
    }

    /** {@link #distance} with the number of cells looked at. */
    public static Found search(int x, int z, int maxDistance, int maxVisited, Surface surface) {
        if (maxDistance < 0 || maxDistance > MAX_ALLOWED_DISTANCE) {
            throw new IllegalArgumentException("maxDistance must be between 0 and 128");
        }
        if (maxVisited < 1 || maxVisited > MAX_ALLOWED_VISITED) {
            throw new IllegalArgumentException("maxVisited must be between 1 and 65536");
        }
        Objects.requireNonNull(surface, "surface");

        Set<Long> seen = new HashSet<>();
        ArrayDeque<Node> queue = new ArrayDeque<>();
        seen.add(key(x, z));
        Cell origin = Objects.requireNonNull(surface.at(x, z), "surface cell");
        if (origin != Cell.WATER) {
            return new Found(-1, 1);
        }
        queue.addLast(new Node(x, z, 0));
        int visited = 1;

        while (!queue.isEmpty()) {
            Node current = queue.removeFirst();
            if (current.distance == maxDistance) {
                continue;
            }
            for (int[] direction : DIRECTIONS) {
                int nextX = current.x + direction[0];
                int nextZ = current.z + direction[1];
                long key = key(nextX, nextZ);
                if (seen.contains(key)) {
                    continue;
                }
                if (visited >= maxVisited) {
                    return new Found(-1, visited);
                }
                seen.add(key);
                visited++;
                Cell cell = Objects.requireNonNull(surface.at(nextX, nextZ), "surface cell");
                if (cell == Cell.SHORE) {
                    return new Found(current.distance + 1, visited);
                }
                if (cell == Cell.WATER) {
                    queue.addLast(new Node(nextX, nextZ, current.distance + 1));
                }
            }
        }
        return new Found(-1, visited);
    }

    private static long key(int x, int z) {
        return ((long) x << 32) ^ (z & 0xffff_ffffL);
    }

    private static final int[][] DIRECTIONS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    public interface Surface {
        Cell at(int x, int z);
    }

    public enum Cell {
        WATER,
        SHORE,
        BLOCKED,
        UNKNOWN
    }

    private record Node(int x, int z, int distance) { }
}
