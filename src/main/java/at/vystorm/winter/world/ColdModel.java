package at.vystorm.winter.world;

import java.util.EnumSet;
import java.util.Set;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;

/** Pure cold/warmth rules; the player loop in {@link WinterCold} only gathers the inputs. */
public final class ColdModel {
    /** Where the player is standing this tick. */
    public enum Exposure { WARM, SHELTERED, OUTSIDE }
    public record Settings(boolean enabled,double maxFrost,boolean damage,int warmRadius,double minutesToFreeze,boolean breath,boolean warnings,double warmFood,Set<Material> handWarmers) {
        public static final Set<Material> DEFAULT_HAND_WARMERS=Set.of(Material.LANTERN,Material.SOUL_LANTERN);
        public static final Settings DEFAULTS=new Settings(true,0.4,false,4,3,true,true,0.5);
        public Settings { handWarmers=Set.copyOf(handWarmers); }
        public Settings(boolean enabled,double maxFrost,boolean damage,int warmRadius,double minutesToFreeze,boolean breath,boolean warnings,double warmFood) {
            this(enabled,maxFrost,damage,warmRadius,minutesToFreeze,breath,warnings,warmFood,DEFAULT_HAND_WARMERS);
        }
        public static Settings read(FileConfiguration c) {
            double maxFrost=number(c,"cold.max-frost",DEFAULTS.maxFrost,0,1);
            double minutes=number(c,"cold.minutes-to-freeze",DEFAULTS.minutesToFreeze,0.5,60);
            int radius=c.getInt("cold.warm-radius",DEFAULTS.warmRadius);
            if(radius<1||radius>8)throw new IllegalArgumentException("cold.warm-radius must be 1..8");
            Set<Material> warmers=EnumSet.noneOf(Material.class);
            if(c.isList("cold.hand-warmers")){
                for(String name:c.getStringList("cold.hand-warmers")){
                    Material material=Material.getMaterial(name==null?"":name.trim().toUpperCase(java.util.Locale.ROOT).replace("MINECRAFT:",""));
                    if(material==null||material.isLegacy())throw new IllegalArgumentException("cold.hand-warmers: unknown item "+name);
                    warmers.add(material);
                }
            } else warmers.addAll(DEFAULT_HAND_WARMERS);
            // 1.24.0 switches: cold.hot-meals.enabled and cold.warm-hands.enabled turn the two helpers off, whatever the values say.
            double food=number(c,"cold.warm-food",DEFAULTS.warmFood,0,1);
            if(!c.getBoolean("cold.hot-meals.enabled",true))food=0;
            if(!c.getBoolean("cold.warm-hands.enabled",true))warmers.clear();
            return new Settings(c.getBoolean("cold.enabled",DEFAULTS.enabled),maxFrost,c.getBoolean("cold.damage",DEFAULTS.damage),
                    radius,minutes,c.getBoolean("cold.breath",DEFAULTS.breath),c.getBoolean("cold.warnings",DEFAULTS.warnings),
                    food,warmers);
        }
        private static double number(FileConfiguration c,String key,double fallback,double min,double max) {
            double n=c.getDouble(key,fallback);
            if(!Double.isFinite(n)||n<min||n>max)throw new IllegalArgumentException(key+" must be "+min+".."+max);
            return n;
        }
    }
    /** Vanilla freezes an entity fully at 140 ticks; at that point it starts taking freeze damage. */
    public static final int FULLY_FROZEN=140;
    private ColdModel() {}

    /**
     * Cold level change per second (positive = getting colder). Each leather armour piece blocks a
     * quarter of the cold, like vanilla's full leather set makes players immune to powder snow.
     */
    public static double perSecond(Exposure exposure,Settings s,double intensity,boolean night,boolean storm,boolean wet,int leather) {
        return switch(exposure) {
            case WARM -> -1/8.0;
            case SHELTERED -> -1/60.0;
            case OUTSIDE -> {
                double gain=intensity/(s.minutesToFreeze()*60);
                if(night)gain*=1.5;
                if(storm)gain*=1.3;
                if(wet)gain*=2;
                gain*=Math.max(0,1-0.25*Math.clamp(leather,0,4));
                yield gain>0?gain:-1/60.0;
            }
        };
    }

    /** Cold-blocking pieces: each leather armour piece counts one, a hand warmer (lantern in a hand) one more; at most 4. */
    public static int protection(int leather,boolean handWarmer) { return Math.clamp(leather+(handWarmer?1:0),0,4); }

    /** Next cold level, clamped to 0..1. */
    public static double step(double level,double perSecond,double seconds) { return Math.clamp(level+perSecond*seconds,0,1); }

    /** Freeze ticks shown to the client: never fully frozen (no damage) unless damage is enabled. */
    public static int freezeTicks(double level,Settings s) {
        if(level<=0)return 0;
        if(s.damage())return (int)Math.round(level*FULLY_FROZEN);
        return (int)Math.min(FULLY_FROZEN-1,Math.round(level*s.maxFrost()*FULLY_FROZEN));
    }

    /** Heat sources; campfires and furnaces only count while lit. */
    public static boolean warms(Material material,boolean lit) {
        return switch(material) {
            case FIRE,SOUL_FIRE,LAVA,MAGMA_BLOCK -> true;
            case CAMPFIRE,SOUL_CAMPFIRE,FURNACE,SMOKER,BLAST_FURNACE -> lit;
            default -> false;
        };
    }

    /** Hot bowls (stews and soups) warm a cold player from the inside. */
    public static boolean warmFood(Material material) {
        return switch(material) {
            case MUSHROOM_STEW,RABBIT_STEW,BEETROOT_SOUP,SUSPICIOUS_STEW -> true;
            default -> false;
        };
    }

    /** Cold level after eating a hot bowl; warm-food 0 disables the effect. */
    public static double afterFood(double level,Material food,Settings s) {
        return warmFood(food)?Math.clamp(level-s.warmFood(),0,1):level;
    }

    /** Minecraft night (including dusk/dawn darkness) by world time. */
    public static boolean night(long time) { long t=Math.floorMod(time,24000L); return t>=13000 && t<23000; }
}
