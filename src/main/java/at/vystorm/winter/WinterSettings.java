package at.vystorm.winter;

import at.vystorm.winter.season.SeasonCalendar;
import java.time.MonthDay;
import java.time.ZoneId;
import java.util.Set;
import java.util.stream.Collectors;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;

/** Immutable, validated configuration. Reload swaps it only after full validation. */
public record WinterSettings(Set<String> worlds, ZoneId zone, SeasonCalendar calendar, int layers, boolean treeSnow, boolean vegetationSnow, double driftStrength, int transitionBlocks,
        Set<String> excludedBiomes, Set<Material> clearMaterials, boolean frost, int oceanRadius,
        int inlandRadius, int searchBudget, Set<Material> shores, boolean protectRegions, String regionFlag,
        int period, int columns, int changes, int maxTracked, boolean weather, double snowChance,
        int weatherSeconds, boolean particles, boolean packetSnow, boolean spawns, double skeleton, double spider, double creeper,
        boolean advent, boolean christmas, boolean newYear, MonthDay christmasStart, MonthDay christmasEnd,
        boolean fireworks, Material reward, int rewardAmount, Terrain terrain, WindBlown windBlown) {
    public static WinterSettings read(FileConfiguration c) {
        Material reward = Material.valueOf(c.getString("holidays.reward-material", "COOKIE"));
        if (!reward.isItem() || reward.isAir()) throw new IllegalArgumentException("Reward must be an item");
        return new WinterSettings(Set.copyOf(c.getStringList("worlds")), ZoneId.of(c.getString("timezone", "Europe/Vienna")),
                new SeasonCalendar(date(c,"calendar.start"),date(c,"calendar.peak"),date(c,"calendar.thaw"),date(c,"calendar.end")),
                integer(c,"snow.max-layers",1,64),c.getBoolean("snow.cover-leaves",true),c.getBoolean("snow.cover-small-plants",true),
                driftStrength(c),transitionBlocks(c),
                Set.copyOf(c.getStringList("snow.excluded-biomes")),materials(c,"snow.clear-materials"),
                c.getBoolean("frost.enabled"),integer(c,"frost.ocean-radius",0,128),integer(c,"frost.inland-radius",0,128),
                integer(c,"frost.search-budget",1,65536),materials(c,"frost.shore-materials"),c.getBoolean("protection.protect-core-regions"),
                c.getString("protection.region-flag","winter"),integer(c,"performance.period-ticks",1,1200),
                integer(c,"performance.columns-per-cycle",1,8192),integer(c,"performance.changes-per-cycle",1,512),
                integer(c,"performance.max-tracked-blocks",1,100000000),c.getBoolean("weather.enabled"),number(c,"weather.snow-chance-at-peak",0,1),
                integer(c,"weather.interval-seconds",10,86400),c.getBoolean("weather.warm-biome-particles"),c.getBoolean("weather.packet-snow",true),c.getBoolean("spawns.enabled"),
                number(c,"spawns.skeleton-weight-at-peak",0,3),number(c,"spawns.spider-weight-at-peak",0,3),number(c,"spawns.creeper-weight-at-peak",0,3),
                holiday(c,"holidays.advent"),holiday(c,"holidays.christmas"),holiday(c,"holidays.new-year"),
                date(c,"holidays.christmas-start"),date(c,"holidays.christmas-end"),holiday(c,"holidays.fireworks"),
                reward,integer(c,"holidays.reward-amount",1,reward.getMaxStackSize()),
                Terrain.read(c), WindBlown.read(c));
    }
    /**
     * Wind-blown snow (1.25.0, {@code snow.wind-blown.*}): snow also settles under roof edges and canopies that are open
     * to the wind. {@code angle} from the vertical, {@code from} = compass degrees the wind comes from, {@code maxHeight}
     * = highest roof or canopy snow still gets under, {@code maxLayers} = cap under roofs. The on/off switch is
     * {@code snow.wind-blown.enabled} in {@link WinterSwitches}.
     */
    public record WindBlown(double angle, double from, int maxHeight, int maxLayers, boolean leavesBlock) {
        public static final WindBlown DEFAULTS = new WindBlown(20, 270, 12, 4, true);
        public static WindBlown read(FileConfiguration c) {
            return new WindBlown(optionalNumber(c, "snow.wind-blown.angle", 20, 0, 40), windFrom(c.getString("snow.wind-blown.wind-from", "west")),
                    optionalInteger(c, "snow.wind-blown.max-height", 12, 2, 24), optionalInteger(c, "snow.wind-blown.max-layers", 4, 1, 8),
                    c.getBoolean("snow.wind-blown.leaves-block", true));
        }
        /** {@code west|north|east|south} or a compass degree (0 = north, 90 = east): where the wind comes from. */
        static double windFrom(String value) {
            String v = value == null ? "west" : value.trim().toLowerCase(java.util.Locale.ROOT);
            switch (v) {
                case "north": return 0;
                case "east": return 90;
                case "south": return 180;
                case "west": return 270;
                default:
            }
            try {
                double degrees = Double.parseDouble(v);
                if (Double.isFinite(degrees) && degrees >= 0 && degrees <= 360) return degrees % 360;
            } catch (NumberFormatException ignored) {
                // falls through to the message
            }
            throw new IllegalArgumentException("snow.wind-blown.wind-from must be west, north, east, south or 0..360");
        }
        public at.vystorm.winter.world.WindRay.Wind wind() {
            return new at.vystorm.winter.world.WindRay.Wind(angle, from, maxHeight);
        }
    }
    /** Per-block terrain switches, read once with the rest of the settings. */
    public record Terrain(boolean ramps, boolean coverStairsAndSlabs, boolean removeTallPlants) {
        public static Terrain read(FileConfiguration c) {
            return new Terrain(c.getBoolean("snow.ramps", true), c.getBoolean("snow.cover-stairs-and-slabs", true), c.getBoolean("snow.remove-tall-plants", true));
        }
    }
    /**
     * One holiday key under the master switch {@code holidays.enabled} (1.24.0): off = no holiday, no new gift, no
     * countdown and no fireworks, whatever the single keys say; a missing master key = on.
     */
    static boolean holiday(FileConfiguration c, String key) { return c.getBoolean("holidays.enabled", true) && c.getBoolean(key); }
    /** {@code snow.drift-strength}, 0 while {@code snow.drifts.enabled} is false (the value is still checked). */
    static double driftStrength(FileConfiguration c) {
        double value = optionalNumber(c, "snow.drift-strength", 0.45, 0, 1);
        return c.getBoolean("snow.drifts.enabled", true) ? value : 0;
    }
    /** {@code snow.transition-blocks}, 0 while {@code snow.soft-edges.enabled} is false (the value is still checked). */
    static int transitionBlocks(FileConfiguration c) {
        int value = optionalInteger(c, "snow.transition-blocks", 4, 0, 12);
        return c.getBoolean("snow.soft-edges.enabled", true) ? value : 0;
    }
    public boolean includes(World world) { return world.getEnvironment() == World.Environment.NORMAL && worlds.contains(world.getName()); }
    private static MonthDay date(FileConfiguration c,String key) { return MonthDay.parse("--"+c.getString(key)); }
    private static Set<Material> materials(FileConfiguration c,String key) { return c.getStringList(key).stream().map(Material::valueOf).collect(Collectors.toUnmodifiableSet()); }
    private static int integer(FileConfiguration c,String key,int min,int max) {
        int n=c.getInt(key); if(n<min||n>max) throw new IllegalArgumentException(key+" must be "+min+".."+max);return n;
    }
    private static int optionalInteger(FileConfiguration c,String key,int fallback,int min,int max) {
        int n=c.getInt(key,fallback);if(n<min||n>max)throw new IllegalArgumentException(key+" must be "+min+".."+max);return n;
    }
    private static double optionalNumber(FileConfiguration c,String key,double fallback,double min,double max) {
        double n=c.getDouble(key,fallback);if(!Double.isFinite(n)||n<min||n>max)throw new IllegalArgumentException(key+" must be "+min+".."+max);return n;
    }
    private static double number(FileConfiguration c,String key,double min,double max) {
        double n=c.getDouble(key);if(!Double.isFinite(n)||n<min||n>max) throw new IllegalArgumentException(key+" must be "+min+".."+max);return n;
    }
}
