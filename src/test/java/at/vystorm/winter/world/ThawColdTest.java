package at.vystorm.winter.world;

import at.vystorm.winter.WinterPlugin;
import at.vystorm.winter.WinterSettings;
import at.vystorm.winter.season.SeasonCalendar;
import java.util.*;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ThawColdTest {
    @Test void naturalThawClearsColdAndWarmthWithoutScanningForHeat() throws Exception {
        WinterPlugin plugin=plugin();Player player=player();WinterCold cold=new WinterCold(plugin, Vystorm.vystorm_core.compat.PlatformCompatInterface.DEFAULT);
        seed(cold,"levels",player,0.6);seed(cold,"reserves",player,20);
        try(var bukkit=mockStatic(Bukkit.class);var compat=mockStatic(Vystorm.vystorm_core.compat.PlatformCompat.class);var biomes=mockStatic(BiomeKeys.class)) {
            biomes.when(()->BiomeKeys.key(any())).thenReturn("plains");
            bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of(player));cold.tick();
            assertEquals(0,cold.level(player.getUniqueId()));assertEquals(0,cold.reserve(player.getUniqueId()));
            verify(player,never()).getLocation();
        }
    }
    @Test void naturalThawPreventsSnowballChill() {
        WinterPlugin plugin=plugin();Player player=player();WinterCold cold=new WinterCold(plugin, Vystorm.vystorm_core.compat.PlatformCompatInterface.DEFAULT);
        cold.chill(player,0.1);assertEquals(0,cold.level(player.getUniqueId()));
    }
    private WinterPlugin plugin() {
        WinterPlugin plugin=mock(WinterPlugin.class);
        when(plugin.controls(any())).thenReturn(true);
        when(plugin.season()).thenReturn(new SeasonCalendar.State(0.5,"THAW",2026,"NONE",0));
        when(plugin.cold()).thenReturn(ColdModel.Settings.DEFAULTS);
        when(plugin.reserve()).thenReturn(WarmthReserve.Settings.DEFAULTS);
        when(plugin.settings()).thenReturn(mock(WinterSettings.class));
        when(plugin.lang()).thenReturn(mock(Vystorm.vystorm_core.lang.Lang.class));
        return plugin;
    }
    private Player player() {
        Player player=mock(Player.class);when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.getGameMode()).thenReturn(GameMode.SURVIVAL);
        World world=mock(World.class);when(player.getWorld()).thenReturn(world);
        Block block=mock(Block.class);when(world.getBlockAt(anyInt(),anyInt(),anyInt())).thenReturn(block);
        when(world.getBlockAt(any(Location.class))).thenReturn(block);
        when(block.getType()).thenReturn(Material.AIR);
        when(player.getLocation()).thenReturn(new Location(world,0,64,0));
        when(player.getEyeLocation()).thenReturn(new Location(world,0,65,0));
        PlayerInventory inventory=mock(PlayerInventory.class);when(player.getInventory()).thenReturn(inventory);
        when(inventory.getArmorContents()).thenReturn(new ItemStack[4]);
        ItemStack empty=mock(ItemStack.class);when(empty.getType()).thenReturn(Material.AIR);
        when(inventory.getItemInMainHand()).thenReturn(empty);when(inventory.getItemInOffHand()).thenReturn(empty);
        return player;
    }
    @SuppressWarnings("unchecked") private void seed(WinterCold cold,String name,Player player,double amount) throws Exception {
        var field=WinterCold.class.getDeclaredField(name);field.setAccessible(true);
        ((Map<UUID,Double>)field.get(cold)).put(player.getUniqueId(),amount);
    }
}
