package at.vystorm.winter.world;

import org.bukkit.Keyed;

final class BiomeKeys {
    private BiomeKeys(){ }
    static String key(Keyed biome){
        // Biome changed from enum to interface; Keyed is stable on both APIs.
        return biome.getKey().getKey();
    }
}
