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
            return -1;
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
                    return -1;
                }
                seen.add(key);
                visited++;
                Cell cell = Objects.requireNonNull(surface.at(nextX, nextZ), "surface cell");
                if (cell == Cell.SHORE) {
                    return current.distance + 1;
                }
                if (cell == Cell.WATER) {
                    queue.addLast(new Node(nextX, nextZ, current.distance + 1));
                }
            }
        }
        return -1;
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
