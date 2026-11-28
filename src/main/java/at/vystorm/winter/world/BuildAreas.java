package at.vystorm.winter.world;

import java.util.*;
import java.util.function.LongSupplier;

/**
 * Snow-free building (1.28.0): circles in which the plugin lays no snow or ice and takes its own away, so a player can
 * build. Two kinds:
 * <ul>
 *   <li>a player's <b>area</b> (the menu switch or {@code /winter build}): one per player, fixed where he switched it
 *   on, kept while it is on and for {@code after} once he switches it off or logs out; a server-wide cap on areas
 *   (players with the unlimited permission are not counted) and a short cooldown between two starts;</li>
 *   <li>a <b>halo</b> (off unless {@code build-mode.halo.enabled} is true): a small circle around a block a player
 *   placed or broke near the surface, kept for a while. Halos close to each other are merged; the oldest goes over the
 *   cap.</li>
 * </ul>
 * A circle can carry a mask of the columns its player may change (protected plots of others are left out). Lookups are
 * per chunk, so {@link #free} costs one map lookup where no circle is. Server thread; tested without a server.
 */
public final class BuildAreas {
    /** Settings under {@code build-mode}; times in milliseconds. */
    public record Settings(int radius, int maxRadius, long afterMillis, int maxAreas, long cooldownMillis,
                           boolean halo, int haloRadius, long haloMillis, int maxHalos, int clearPerTick) {
        public static final Settings DEFAULTS = new Settings(24, 64, 30 * 60_000L, 16, 10_000L, false, 6, 30 * 60_000L, 4096, 64);
        /** Hard cap of a radius, also for admins. */
        public static final int HARD_RADIUS = 96;
        public Settings {
            maxRadius = Math.clamp(maxRadius, 4, HARD_RADIUS);
            radius = Math.clamp(radius, 4, maxRadius);
            afterMillis = Math.clamp(afterMillis, 0L, 24 * 3_600_000L);
            maxAreas = Math.clamp(maxAreas, 0, 10_000);
            cooldownMillis = Math.clamp(cooldownMillis, 0L, 3_600_000L);
            haloRadius = Math.clamp(haloRadius, 1, 16);
            haloMillis = Math.clamp(haloMillis, 0L, 24 * 3_600_000L);
            maxHalos = Math.clamp(maxHalos, 0, 65_536);
            clearPerTick = Math.clamp(clearPerTick, 1, 512);
        }
        /** {@code build-mode.*} of config.yml; {@code halo} is the switch {@code build-mode.halo.enabled}. */
        public static Settings read(org.bukkit.configuration.ConfigurationSection c, boolean halo) {
            return new Settings(c.getInt("build-mode.radius", 24), c.getInt("build-mode.max-radius", 64),
                    Math.round(c.getDouble("build-mode.after-minutes", 30) * 60_000), c.getInt("build-mode.max-areas", 16),
                    Math.round(c.getDouble("build-mode.cooldown-seconds", 10) * 1000), halo, c.getInt("build-mode.halo.radius", 6),
                    Math.round(c.getDouble("build-mode.halo.minutes", 30) * 60_000), c.getInt("build-mode.halo.max", 4096),
                    c.getInt("build-mode.clear-per-tick", 64));
        }
    }

    /** Columns of a circle its player may change; asked once per column when the circle starts. */
    @FunctionalInterface
    public interface Permit {
        boolean may(int x, int z);
        Permit ALL = (x, z) -> true;
    }

    /** A player's area as the menu shows it. {@code until} is {@link Long#MAX_VALUE} while it is on. */
    public record Area(UUID player, UUID world, int x, int z, int radius, boolean on, long until, boolean unlimited) {
        /** Whole minutes left after switching off (rounded up), 0 while on. */
        public long minutesLeft(long now) {
            return on ? 0 : Math.max(0, (until - now + 59_999) / 60_000);
        }
    }

    /** What {@link #start} did. {@code waitMillis} is the rest of the cooldown. */
    public enum Outcome { STARTED, LIMIT, COOLDOWN }
    public record Started(Outcome outcome, Area area, long waitMillis) {}

    /** A circle to clear: the terrain pass restores the plugin's snow and ice inside, nearest first. */
    public record Clear(UUID world, int x, int z, int radius) {}

    private static final class Circle {
        final UUID player, world;
        final int x, z, radius;
        final boolean area, unlimited;
        /** Allowed columns of the square around the centre, null = all. */
        final BitSet mask;
        boolean on;
        long until;
        Circle(UUID player, UUID world, int x, int z, int radius, boolean area, boolean unlimited, BitSet mask, boolean on, long until) {
            this.player = player;
            this.world = world;
            this.x = x;
            this.z = z;
            this.radius = radius;
            this.area = area;
            this.unlimited = unlimited;
            this.mask = mask;
            this.on = on;
            this.until = until;
        }
        boolean contains(int px, int pz) {
            int dx = px - x, dz = pz - z;
            if ((long) dx * dx + (long) dz * dz > (long) radius * radius) return false;
            return mask == null || mask.get((dx + radius) * (2 * radius + 1) + dz + radius);
        }
        Area view() { return new Area(player, world, x, z, radius, on, until, unlimited); }
    }

    private final LongSupplier clock;
    private Settings settings = Settings.DEFAULTS;
    private final Map<UUID, Circle> areas = new HashMap<>();
    /** Halos in the order they were made or refreshed; the first is the oldest. */
    private final LinkedHashSet<Circle> halos = new LinkedHashSet<>();
    /** world -> chunk (x << 32 | z) -> circles touching that chunk. */
    private final Map<UUID, Map<Long, List<Circle>>> index = new HashMap<>();
    private final Map<UUID, Long> lastStart = new HashMap<>();

    public BuildAreas(LongSupplier clock) {
        this.clock = clock;
    }

    public void configure(Settings settings) {
        this.settings = settings;
        if (!settings.halo()) for (Circle halo : List.copyOf(halos)) remove(halo);
    }

    public Settings settings() { return settings; }

    public long now() { return clock.getAsLong(); }

    // ---- lookups (every block the terrain pass looks at) --------------------------------------------------------------

    /** True when no plugin snow or ice may lie at this column now. */
    public boolean free(UUID world, int x, int z) {
        if (index.isEmpty()) return false;
        Map<Long, List<Circle>> chunks = index.get(world);
        if (chunks == null) return false;
        List<Circle> here = chunks.get(chunk(x >> 4, z >> 4));
        if (here == null) return false;
        long now = clock.getAsLong();
        for (Circle c : here) if (c.until > now && c.contains(x, z)) return true;
        return false;
    }

    public boolean empty() { return index.isEmpty(); }

    /** True when a circle that keeps snow away now reaches into this chunk. */
    public boolean touches(UUID world, int chunkX, int chunkZ) {
        Map<Long, List<Circle>> chunks = index.get(world);
        List<Circle> here = chunks == null ? null : chunks.get(chunk(chunkX, chunkZ));
        if (here == null) return false;
        long now = clock.getAsLong();
        for (Circle c : here) if (c.until > now) return true;
        return false;
    }

    // ---- player areas -------------------------------------------------------------------------------------------------

    /**
     * Switches the player's area on at x/z (replacing his earlier one). Refused over the server-wide cap or within the
     * cooldown, unless {@code unlimited}. {@code radius} is capped by {@code max-radius}; 0 or less takes the default.
     */
    public Started start(UUID player, UUID world, int x, int z, int radius, boolean unlimited, Permit permit) {
        long now = clock.getAsLong();
        Long last = lastStart.get(player);
        if (!unlimited && last != null && now - last < settings.cooldownMillis())
            return new Started(Outcome.COOLDOWN, area(player).orElse(null), settings.cooldownMillis() - (now - last));
        Circle old = areas.get(player);
        if (!unlimited && counted(player, now) >= settings.maxAreas()) return new Started(Outcome.LIMIT, old == null ? null : old.view(), 0);
        if (old != null) remove(old);
        int r = radius <= 0 ? settings.radius() : Math.clamp(radius, 4, settings.maxRadius());
        Circle c = new Circle(player, world, x, z, r, true, unlimited, mask(x, z, r, permit), true, Long.MAX_VALUE);
        areas.put(player, c);
        add(c);
        lastStart.put(player, now);
        return new Started(Outcome.STARTED, c.view(), 0);
    }

    /** Areas counted against the cap: active, not unlimited, not this player's own. */
    private int counted(UUID player, long now) {
        int n = 0;
        for (Circle c : areas.values()) if (!c.unlimited && c.until > now && !c.player.equals(player)) n++;
        return n;
    }

    /** Active areas counted against the cap (for the admin view). */
    public int counted() {
        long now = clock.getAsLong();
        int n = 0;
        for (Circle c : areas.values()) if (!c.unlimited && c.until > now) n++;
        return n;
    }

    public int halos() { return halos.size(); }

    /** Switches the area off: it stays snow-free for {@code after}, then snow comes back. Empty when there was none on. */
    public Optional<Area> stop(UUID player) {
        Circle c = areas.get(player);
        if (c == null || !c.on) return Optional.empty();
        c.on = false;
        c.until = clock.getAsLong() + settings.afterMillis();
        if (settings.afterMillis() == 0) remove(c);
        return Optional.of(c.view());
    }

    /** The player logged out: the same as switching off. */
    public void left(UUID player) { stop(player); }

    /** The player's area while it keeps snow away (on or within its time after). */
    public Optional<Area> area(UUID player) {
        Circle c = areas.get(player);
        return c == null || c.until <= clock.getAsLong() ? Optional.empty() : Optional.of(c.view());
    }

    /** Removes everything (build mode switched off in config.yml). */
    public void clear() {
        areas.clear();
        halos.clear();
        index.clear();
    }

    // ---- halos ---------------------------------------------------------------------------------------------------------

    /**
     * A player placed or broke a block at x/z near the surface: the halo there is made or refreshed. Returns the circle
     * to clear when a new halo was made, null when nothing new is needed (halos off, inside his own area, refreshed).
     */
    public Clear halo(UUID player, UUID world, int x, int z, java.util.function.Supplier<Permit> permits) {
        if (!settings.halo() || settings.haloMillis() == 0 || settings.maxHalos() == 0) return null;
        long now = clock.getAsLong();
        int r = settings.haloRadius();
        Map<Long, List<Circle>> chunks = index.get(world);
        List<Circle> here = chunks == null ? null : chunks.get(chunk(x >> 4, z >> 4));
        if (here != null) for (Circle c : here) {
            if (c.until <= now) continue;
            int dx = x - c.x, dz = z - c.z;
            long d2 = (long) dx * dx + (long) dz * dz;
            if (c.area && c.on && c.mask == null && Math.sqrt(d2) + r <= c.radius) return null;
            if (!c.area && d2 <= 4) {
                c.until = now + settings.haloMillis();
                halos.remove(c);
                halos.add(c);
                return null;
            }
        }
        while (halos.size() >= settings.maxHalos()) remove(halos.iterator().next());
        Circle c = new Circle(player, world, x, z, r, false, false, mask(x, z, r, permits.get()), true, now + settings.haloMillis());
        halos.add(c);
        add(c);
        return new Clear(world, x, z, r);
    }

    // ---- time ----------------------------------------------------------------------------------------------------------

    /** Removes circles whose time is over; returns the player areas that ended (to tell their players). */
    public List<Area> expire() {
        long now = clock.getAsLong();
        List<Area> ended = new ArrayList<>();
        for (Circle c : List.copyOf(areas.values())) if (c.until <= now) {
            remove(c);
            ended.add(c.view());
        }
        for (Circle c : List.copyOf(halos)) if (c.until <= now) remove(c);
        lastStart.values().removeIf(t -> now - t > settings.cooldownMillis());
        return ended;
    }

    // ---- bookkeeping ---------------------------------------------------------------------------------------------------

    private static BitSet mask(int x, int z, int r, Permit permit) {
        if (permit == null || permit == Permit.ALL) return null;
        int side = 2 * r + 1;
        BitSet mask = new BitSet(side * side);
        boolean all = true;
        for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) {
            if ((long) dx * dx + (long) dz * dz > (long) r * r) continue;
            if (permit.may(x + dx, z + dz)) mask.set((dx + r) * side + dz + r);
            else all = false;
        }
        return all ? null : mask;
    }

    private static long chunk(int cx, int cz) { return (long) cx << 32 ^ (cz & 0xFFFFFFFFL); }

    private void add(Circle c) {
        Map<Long, List<Circle>> chunks = index.computeIfAbsent(c.world, w -> new HashMap<>());
        for (int cx = (c.x - c.radius) >> 4; cx <= (c.x + c.radius) >> 4; cx++)
            for (int cz = (c.z - c.radius) >> 4; cz <= (c.z + c.radius) >> 4; cz++)
                chunks.computeIfAbsent(chunk(cx, cz), k -> new ArrayList<>(2)).add(c);
    }

    private void remove(Circle c) {
        if (c.area) areas.remove(c.player, c);
        else halos.remove(c);
        Map<Long, List<Circle>> chunks = index.get(c.world);
        if (chunks == null) return;
        for (int cx = (c.x - c.radius) >> 4; cx <= (c.x + c.radius) >> 4; cx++)
            for (int cz = (c.z - c.radius) >> 4; cz <= (c.z + c.radius) >> 4; cz++) {
                long key = chunk(cx, cz);
                List<Circle> list = chunks.get(key);
                if (list == null) continue;
                list.removeIf(o -> o == c);
                if (list.isEmpty()) chunks.remove(key);
            }
        if (chunks.isEmpty()) index.remove(c.world);
    }

    /** The order in which a circle is cleared: nearest column first, the top of a column before the cell below it. */
    public static Comparator<int[]> nearestFirst(int x, int z) {
        return Comparator.<int[]>comparingLong(p -> (long) (p[0] - x) * (p[0] - x) + (long) (p[2] - z) * (p[2] - z))
                .thenComparing(p -> -p[1]);
    }
}
