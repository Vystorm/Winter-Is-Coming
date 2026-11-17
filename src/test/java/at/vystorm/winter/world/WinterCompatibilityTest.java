package at.vystorm.winter.world;

import Vystorm.vystorm_core.packet.ClientBiomes;
import at.vystorm.winter.WinterPlugin;
import at.vystorm.winter.WinterSettings;
import at.vystorm.winter.season.SeasonCalendar;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;
import org.bukkit.*;
import org.bukkit.block.Biome;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WinterCompatibilityTest {
    @Test void biomeKeysUseStableKeyedContract(){
        Keyed biome=()->NamespacedKey.minecraft("frozen_ocean");
        assertEquals("frozen_ocean",BiomeKeys.key(biome));
        if(Biome.class.isEnum()){
            Keyed legacy=mock(Biome.class);
            when(legacy.getKey()).thenReturn(NamespacedKey.minecraft("frozen_ocean"));
            assertEquals("frozen_ocean",BiomeKeys.key(legacy));
        }
    }

    @Test void unavailablePacketSnowFallsBackAndLogsOnlyOnce(){weather(false,true);}
    @Test void availablePacketSnowKeepsNativeView(){weather(true,true);}
    @Test void disabledPacketSnowClearsNativeView(){weather(false,false);}
    @Test void excludedBiomeReceivesNoFallbackParticles(){weather(false,false,true);}

    private void weather(boolean available,boolean enabled){
        weather(available,enabled,false);
    }
    private void weather(boolean available,boolean enabled,boolean excluded){
        WinterPlugin plugin=mock(WinterPlugin.class);
        WinterSettings settings=mock(WinterSettings.class);
        Logger logger=mock(Logger.class);
        World world=mock(World.class);
        Player player=mock(Player.class);
        when(plugin.settings()).thenReturn(settings);
        when(plugin.getLogger()).thenReturn(logger);
        when(plugin.controls(world)).thenReturn(true);
        when(settings.weather()).thenReturn(true);
        when(settings.packetSnow()).thenReturn(enabled);
        when(settings.particles()).thenReturn(true);
        when(settings.excludedBiomes()).thenReturn(excluded?Set.of("desert"):Set.of());
        when(plugin.season()).thenReturn(mock(SeasonCalendar.State.class));
        when(world.getUID()).thenReturn(UUID.randomUUID());
        when(world.hasStorm()).thenReturn(true);
        when(world.getPlayers()).thenReturn(List.of(player));
        when(world.isChunkLoaded(0,0)).thenReturn(true);
        when(player.getLocation()).thenReturn(new Location(world,0,64,0));
        when(player.getWorld()).thenReturn(world);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        org.bukkit.block.Block block=mock(org.bukkit.block.Block.class);
        when(world.getBlockAt(any(Location.class))).thenReturn(block);
        try(var bukkit=mockStatic(Bukkit.class);var biomes=mockStatic(ClientBiomes.class);var keys=mockStatic(BiomeKeys.class)){
            keys.when(()->BiomeKeys.key(any())).thenReturn("desert");
            bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of(player));
            bukkit.when(Bukkit::getWorlds).thenReturn(List.of(world));
            biomes.when(()->ClientBiomes.snow(plugin,player,Set.of())).thenReturn(available);
            WinterWeather weather=new WinterWeather(plugin, Vystorm.vystorm_core.packet.ClientBiomesInterface.DEFAULT);
            weather.tick();weather.tick();
            if(enabled)biomes.verify(()->ClientBiomes.snow(plugin,player,Set.of()),times(2));
            else biomes.verify(()->ClientBiomes.clear(plugin,player),times(2));
            verify(logger,times(enabled&&!available?1:0)).info(anyString());
            verify(player,times(excluded||enabled&&available?0:2)).spawnParticle(eq(Particle.SNOWFLAKE),any(Location.class),eq(24),eq(5.0),eq(3.0),eq(5.0),eq(0.025));
        }
    }
}
