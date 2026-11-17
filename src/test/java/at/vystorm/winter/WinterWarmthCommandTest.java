package at.vystorm.winter;

import static org.mockito.Mockito.*;
import Vystorm.vystorm_core.lang.Lang;
import at.vystorm.winter.world.ColdModel;
import at.vystorm.winter.world.WinterCold;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

class WinterWarmthCommandTest {
    private static void set(WinterPlugin plugin,String name,Object value)throws Exception{
        var field=WinterPlugin.class.getDeclaredField(name);field.setAccessible(true);field.set(plugin,value);
    }
    @Test void reportsSavedColdReserveAndEquipmentWithoutChangingPlayer() throws Exception {
        WinterPlugin plugin=mock(WinterPlugin.class,CALLS_REAL_METHODS);
        Lang lang=mock(Lang.class);WinterCold cold=mock(WinterCold.class);Player player=mock(Player.class);
        var id=java.util.UUID.randomUUID();when(player.getUniqueId()).thenReturn(id);
        set(plugin,"lang",lang);set(plugin,"coldLoop",cold);set(plugin,"cold",ColdModel.Settings.DEFAULTS);
        when(cold.level(id)).thenReturn(.2);when(cold.reserve(id)).thenReturn(5.1);when(cold.protection(player)).thenReturn(3);
        plugin.commandWarmth(player);
        verify(lang).send(player,"warmth-overview","cold",20L,"seconds",6,"protection",75);
        verify(lang).send(player,"warmth-tip");
        verify(cold,never()).tick();verify(cold,never()).release(any());verify(player,never()).getWorld();
        set(plugin,"failed",true);plugin.commandWarmth(player);verify(lang).send(player,"faulted");
    }
}
