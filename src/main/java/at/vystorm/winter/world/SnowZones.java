package at.vystorm.winter.world;

import Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Where the plugin may lay snow (1.20.0): season spots (circles with a radius around a centre, {@code spots.yml}) and
 * build mode (no plugin snow within a radius of players who turned it on). With at least one spot and
 * {@code season-spots.only-inside}, snow only falls inside the spots. Server thread; checks are cheap because they run
 * for every block the terrain cycle looks at. Tested without a server.
 */
public final class SnowZones {
    /** A spot: world, centre x/z and radius in blocks. */
    public record Spot(UUID world, int x, int z, int radius) {
        boolean contains(UUID w, double px, double pz) {
            double dx = px - x, dz = pz - z;
            return world.equals(w) && dx * dx + dz * dz <= (double) radius * radius;
        }
    }

    private record Builder(UUID world, double x, double z) {}

    private final Path file;
    private final AtomicFilesInterface files;
    private final TreeMap<String, Spot> spots = new TreeMap<>();
    private final Set<UUID> buildMode = new HashSet<>();
    private List<Builder> builders = List.of();
    private boolean onlyInside = true;
    private int buildRadius = 24;

    /** @param files Core's file part, {@code api.data().files()} */
    public SnowZones(Path file, AtomicFilesInterface files) {
        this.file = file;
        this.files = files;
        if (!Files.isRegularFile(file)) return;
        ConfigurationSection all = YamlConfiguration.loadConfiguration(file.toFile()).getConfigurationSection("spots");
        if (all != null) for (String name : all.getKeys(false)) {
            ConfigurationSection s = all.getConfigurationSection(name);
            if (s == null) continue;
            try { spots.put(name, new Spot(UUID.fromString(s.getString("world", "")), s.getInt("x"), s.getInt("z"), Math.max(1, s.getInt("radius")))); }
            catch (IllegalArgumentException broken) { /* skip */ }
        }
    }

    public void configure(boolean onlyInside, int buildRadius) {
        this.onlyInside = onlyInside;
        this.buildRadius = Math.max(0, buildRadius);
    }

    /** True when the plugin may lay or keep snow at this position. */
    public boolean allows(UUID world, double x, double z) {
        for (Builder b : builders) {
            if (!b.world().equals(world)) continue;
            double dx = x - b.x(), dz = z - b.z();
            if (dx * dx + dz * dz <= (double) buildRadius * buildRadius) return false;
        }
        if (!onlyInside || spots.isEmpty()) return true;
        // only worlds that have spots are limited to them; other worlds keep snow everywhere (1.20.1)
        boolean worldHasSpot = false;
        for (Spot s : spots.values()) {
            if (!s.world().equals(world)) continue;
            if (s.contains(world, x, z)) return true;
            worldHasSpot = true;
        }
        return !worldHasSpot;
    }

    public boolean allows(Location loc) { return loc.getWorld() == null || allows(loc.getWorld().getUID(), loc.getX(), loc.getZ()); }

    // ---- spots ----------------------------------------------------------------------------------------------------

    public SortedMap<String, Spot> spots() { return Collections.unmodifiableSortedMap(spots); }

    /** True when the position is inside a spot of its world, or snow is not limited to spots (visible flakes; build mode is ignored). */
    public boolean insideSpots(UUID world, double x, double z) {
        if (!limiting()) return true;
        boolean worldHasSpot = false;
        for (Spot s : spots.values()) {
            if (!s.world().equals(world)) continue;
            if (s.contains(world, x, z)) return true;
            worldHasSpot = true;
        }
        return !worldHasSpot;
    }

    /** True when snow is currently limited to the spots (at least one spot and {@code only-inside} on). */
    public boolean limiting() { return onlyInside && !spots.isEmpty(); }

    /** True when the spots limit winter in this world: {@code only-inside} on and at least one spot in this world. */
    public boolean limiting(UUID world) {
        if (!onlyInside) return false;
        for (Spot s : spots.values()) if (s.world().equals(world)) return true;
        return false;
    }

    /**
     * Whether a player at this position gets the visible winter (falling snow, snow colours, storms, cold): the plugin's
     * winter is active in the world and the position is inside a spot or the spots do not limit that world (1.24.1).
     */
    public static boolean winterAt(boolean controls, SnowZones zones, UUID world, double x, double z) {
        return controls && (zones == null || zones.insideSpots(world, x, z));
    }

    /** A four-block aligned biome cell (quart) for the client-side snow view. */
    public record Cell(int x, int y, int z) {}

    /** Most cells and chunks one client-side biome overlay may carry (Core's limit). */
    public static final int MAX_CELLS = 4096, WINDOW_CHUNKS = 4, LAYERS = 16;

    /**
     * Biome cells inside the spots of {@code world} that a viewer at x/z gets as snowy biome while the spots limit winter
     * (1.24.1): a window of 4 x 4 chunks around the point of the nearest spot that is closest to the viewer (the viewer
     * himself when inside), 16 cells (64 blocks) high from twelve blocks below {@code baseY}. Cells whose centre lies
     * outside every spot stay as they are, so the snow colours and the falling snow end at the spot border. Empty when
     * the world has no spot.
     */
    public List<Cell> projection(UUID world, double x, double z, int baseY, int minY, int maxY) {
        Spot nearest = null;
        double best = Double.MAX_VALUE;
        for (Spot s : spots.values()) {
            if (!s.world().equals(world)) continue;
            double gap = Math.hypot(x - s.x(), z - s.z()) - s.radius();
            if (gap < best) { best = gap; nearest = s; }
        }
        if (nearest == null) return List.of();
        double cx = x, cz = z;
        if (best > 0) {
            double d = Math.hypot(x - nearest.x(), z - nearest.z());
            cx = nearest.x() + (x - nearest.x()) * nearest.radius() / d;
            cz = nearest.z() + (z - nearest.z()) * nearest.radius() / d;
        }
        int chunkX = ((int) Math.floor(cx) >> 4) - WINDOW_CHUNKS / 2 + 1, chunkZ = ((int) Math.floor(cz) >> 4) - WINDOW_CHUNKS / 2 + 1;
        int x0 = chunkX << 4, z0 = chunkZ << 4;
        int y0 = Math.max(minY, Math.floorDiv(baseY - 12, 4) * 4);
        int y1 = Math.min(maxY, y0 + LAYERS * 4);
        List<Cell> cells = new ArrayList<>();
        for (int qx = x0; qx < x0 + WINDOW_CHUNKS * 16; qx += 4) for (int qz = z0; qz < z0 + WINDOW_CHUNKS * 16; qz += 4) {
            if (!insideAny(world, qx + 2, qz + 2)) continue;
            for (int qy = y0; qy + 4 <= y1 && cells.size() < MAX_CELLS; qy += 4) cells.add(new Cell(qx, qy, qz));
        }
        return cells;
    }
    private boolean insideAny(UUID world, double x, double z) {
        for (Spot s : spots.values()) if (s.contains(world, x, z)) return true;
        return false;
    }

    public void add(String name, Spot spot) throws IOException { spots.put(name, spot); save(); }

    public boolean remove(String name) throws IOException {
        if (spots.remove(name) == null) return false;
        save(); return true;
    }

    private void save() throws IOException {
        YamlConfiguration yaml = new YamlConfiguration();
        spots.forEach((name, s) -> {
            yaml.set("spots." + name + ".world", s.world().toString());
            yaml.set("spots." + name + ".x", s.x());
            yaml.set("spots." + name + ".z", s.z());
            yaml.set("spots." + name + ".radius", s.radius());
        });
        Files.createDirectories(file.getParent());
        files.write(file, yaml);
    }

    // ---- build mode -----------------------------------------------------------------------------------------------

    /** Toggles build mode; returns the new state. */
    public boolean toggleBuild(UUID player) {
        if (buildMode.remove(player)) return false;
        buildMode.add(player);
        return true;
    }

    public boolean building(UUID player) { return buildMode.contains(player); }

    public void forget(UUID player) { buildMode.remove(player); }

    /** Snapshot of builder positions; call about once a second with (player, world, x, z) of online builders. */
    public void updateBuilders(List<Object[]> positions) {
        List<Builder> next = new ArrayList<>(positions.size());
        for (Object[] p : positions) next.add(new Builder((UUID) p[0], (double) p[1], (double) p[2]));
        builders = List.copyOf(next);
    }

    public Set<UUID> buildModePlayers() { return Collections.unmodifiableSet(buildMode); }
}
