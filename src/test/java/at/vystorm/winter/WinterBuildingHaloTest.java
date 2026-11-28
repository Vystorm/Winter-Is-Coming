package at.vystorm.winter;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import Vystorm.vystorm_core.API;
import at.vystorm.winter.world.BuildAreas;
import at.vystorm.winter.world.WinterTerrain;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.bukkit.HeightMap;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

/**
 * The halo around fresh blocks (1.28.0) is opt-in: with the shipped config placing, breaking and pouring next to the
 * plugin's snow clear nothing (the owner saw snow vanish beside a placed block on the test build, where it was on).
 * Switched on, the old behaviour comes back. The snow kept under a placed block is a separate rule and stays on.
 */
class WinterBuildingHaloTest {
    final WinterPlugin plugin = mock(WinterPlugin.class);
    final WinterTerrain terrain = mock(WinterTerrain.class);
    final World world = mock(World.class);
    final Player player = mock(Player.class);
    final Block block = mock(Block.class);
    final BuildAreas areas = new BuildAreas(() -> 1_000_000L);
    final YamlConfiguration config = YamlConfiguration.loadConfiguration(new InputStreamReader(
            WinterBuildingHaloTest.class.getResourceAsStream("/config.yml"), StandardCharsets.UTF_8));

    WinterBuilding building(WinterSwitches switches) {
        when(plugin.getConfig()).thenReturn(config);
        when(plugin.switches()).thenReturn(switches);
        when(plugin.terrain()).thenReturn(terrain);
        when(plugin.controls(world)).thenReturn(true);
        when(world.getUID()).thenReturn(UUID.fromString("00000000-0000-0000-0000-0000000000d1"));
        when(world.getHighestBlockYAt(anyInt(), anyInt(), any(HeightMap.class))).thenReturn(64);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.hasPermission(anyString())).thenReturn(true);
        when(block.getWorld()).thenReturn(world);
        when(block.getX()).thenReturn(10);
        when(block.getY()).thenReturn(65);
        when(block.getZ()).thenReturn(10);
        when(block.getType()).thenReturn(Material.OAK_PLANKS);
        areas.configure(BuildAreas.Settings.read(config, switches.on(WinterSwitches.Feature.BUILD_HALO)));
        return new WinterBuilding(plugin, mock(API.class, RETURNS_DEEP_STUBS), areas);
    }

    @Test void givenTheShippedConfig_whenAPlayerPlacesBreaksOrPours_thenNoHaloAndNothingIsCleared() {
        WinterSwitches switches = WinterSwitches.read(config);
        assertFalse(switches.on(WinterSwitches.Feature.BUILD_HALO));
        assertTrue(switches.on(WinterSwitches.Feature.UNDER_BLOCKS), "snow under a placed block is still kept");
        WinterBuilding building = building(switches);

        building.placed(player, block);
        building.broke(player, block);
        building.bucket(player, block);

        assertEquals(0, areas.halos());
        assertFalse(areas.free(world.getUID(), 10, 10));
        assertFalse(areas.free(world.getUID(), 11, 10), "the neighbour column keeps its snow");
        verify(terrain, never()).clearCircle(any(), any());
        verify(terrain).keepUnder(block);
    }

    @Test void givenTheHaloSwitchedOn_whenAPlayerPlaces_thenTheCircleAroundIsClearedAsInTheTestBuild() {
        WinterBuilding building = building(WinterSwitches.with(WinterSwitches.Feature.BUILD_HALO));
        building.placed(player, block);
        assertEquals(1, areas.halos());
        verify(terrain).clearCircle(eq(new BuildAreas.Clear(world.getUID(), 10, 10, 6)), isNull());
    }

    @Test void givenTheHaloSwitchedOn_whenAPlayerBreaksOrPours_thenAHaloToo() {
        WinterBuilding building = building(WinterSwitches.with(WinterSwitches.Feature.BUILD_HALO));
        building.broke(player, block);
        assertEquals(1, areas.halos());
        when(block.getX()).thenReturn(100);
        building.bucket(player, block);
        assertEquals(2, areas.halos());
        verify(terrain, times(2)).clearCircle(any(), isNull());
    }
}
