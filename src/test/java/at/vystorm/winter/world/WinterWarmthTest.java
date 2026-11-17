package at.vystorm.winter.world;

import at.vystorm.winter.WinterPlugin;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WinterWarmthTest {
    @Test void ineligiblePlayersLoseReserveEvenWithNoCold() throws Exception {
        WinterPlugin plugin=mock(WinterPlugin.class);
        when(plugin.cold()).thenReturn(ColdModel.Settings.DEFAULTS);
        WinterCold loop=new WinterCold(plugin, Vystorm.vystorm_core.compat.PlatformCompatInterface.DEFAULT);
        Player player=player();
        seed(loop,player);
        try(var bukkit=mockStatic(Bukkit.class)){
            bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of(player));
            loop.tick();
            assertEquals(0,loop.reserve(player.getUniqueId()));
            verify(player,never()).lockFreezeTicks(anyBoolean());
        }
    }

    @Test void releaseClearsOnlyThatPlayersReserve() throws Exception {
        WinterCold loop=new WinterCold(mock(WinterPlugin.class), Vystorm.vystorm_core.compat.PlatformCompatInterface.DEFAULT);
        Player first=player(),second=player();
        seed(loop,first);seed(loop,second);
        loop.release(first);
        assertEquals(0,loop.reserve(first.getUniqueId()));
        assertEquals(60,loop.reserve(second.getUniqueId()));
        loop.release(first);
        assertEquals(60,loop.reserve(second.getUniqueId()));
    }

    @Test void shutdownClearsAllReservesIncludingOfflineEntries() throws Exception {
        WinterCold loop=new WinterCold(mock(WinterPlugin.class), Vystorm.vystorm_core.compat.PlatformCompatInterface.DEFAULT);
        Player online=player(),offline=player();
        seed(loop,online);seed(loop,offline);
        try(var bukkit=mockStatic(Bukkit.class)){
            bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of(online));
            loop.close();
            assertEquals(0,loop.reserve(online.getUniqueId()));
            assertEquals(0,loop.reserve(offline.getUniqueId()));
        }
    }

    private static Player player(){
        Player player=mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        return player;
    }

    @SuppressWarnings("unchecked")
    private static void seed(WinterCold loop,Player player) throws Exception {
        var field=WinterCold.class.getDeclaredField("reserves");field.setAccessible(true);
        ((Map<UUID,Double>)field.get(loop)).put(player.getUniqueId(),60.0);
    }
}
