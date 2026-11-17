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
