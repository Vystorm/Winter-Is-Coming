package at.vystorm.winter.world;

import at.vystorm.winter.season.SeasonClock.Season;
import java.util.Set;
import org.bukkit.Material;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Slab;
import org.bukkit.block.data.type.Stairs;

/** Pure rules: which plant a column wants in a season, and which blocks carry winter snow. */
public final class SeasonPlants {
    public enum Plant { NONE, SHORT, FLOWER, TALL, BERRY }
    public enum Overlay { NONE, SLAB, STAIRS }
    private static final Set<String> TALL = Set.of("TALL_GRASS", "LARGE_FERN", "SUNFLOWER", "LILAC", "ROSE_BUSH", "PEONY");
    private SeasonPlants() {}

    public static boolean tallPlant(Material material) { return TALL.contains(material.name()); }
    public static boolean tallPlantData(String data) {
        for (String name : TALL) if (data.startsWith("minecraft:" + name.toLowerCase(java.util.Locale.ROOT))) return true;
        return false;
    }
    /** Stable per column and world, so a plant stays where it grew and nothing has to be stored. */
    static int roll(long seed, int x, int z) {
        long h = seed ^ (x * 341873128712L) ^ (z * 132897987541L);
        h = (h ^ (h >>> 29)) * 0xBF58476D1CE4E135L;
        return (int) Math.floorMod(h ^ (h >>> 32), 1000L);
    }
    /** Spring fills in with its progress, so plants appear gradually. */
    public static Plant plan(Season season, double progress, long seed, int x, int z) {
        int roll = roll(seed, x, z);
        return switch (season) {
            case SPRING -> roll < progress * 20 ? Plant.FLOWER : roll < progress * 140 ? Plant.SHORT : Plant.NONE;
            case SUMMER -> roll < 45 ? Plant.TALL : roll < 200 ? Plant.SHORT : Plant.NONE;
            case AUTUMN -> roll < 25 ? Plant.BERRY : Plant.NONE;
            case WINTER -> Plant.NONE;
        };
    }
    public static boolean oursBerry(long seed, int x, int z) { return plan(Season.AUTUMN, 1, seed, x, z) == Plant.BERRY; }

    /** Stairs and bottom slabs: vanilla snow cannot rest on them, winter snow can. */
    public static Overlay overlay(BlockData data) {
        if (data instanceof Stairs stairs) return stairs.getHalf() == Bisected.Half.BOTTOM ? Overlay.STAIRS : Overlay.NONE;
        if (data instanceof Slab slab) return slab.getType() == Slab.Type.BOTTOM ? Overlay.SLAB : Overlay.NONE;
        return Overlay.NONE;
    }
}
