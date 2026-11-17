package at.vystorm.winter;
import Vystorm.vystorm_core.lang.Lang;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MissionClaimFailureTest {
    @Test void missionCreditFailureStopsFurtherRewardsAndWorldEdits() throws Exception {
        WinterPlugin plugin=mock(WinterPlugin.class,CALLS_REAL_METHODS);Player player=mock(Player.class);
        Lang lang=mock(Lang.class);CommunityMissions missions=mock(CommunityMissions.class);
        var texts=WinterPlugin.class.getDeclaredField("lang");texts.setAccessible(true);texts.set(plugin,lang);
        var field=WinterPlugin.class.getDeclaredField("missions");field.setAccessible(true);field.set(plugin,missions);
        doReturn(Logger.getAnonymousLogger()).when(plugin).getLogger();when(player.hasPermission("winter.claim")).thenReturn(true);
        doThrow(new IllegalStateException("unavailable mission credit storage")).when(missions).credit(player);
        assertDoesNotThrow(()->plugin.claim(player));assertTrue(plugin.faulted());verify(lang).send(player,"faulted");
        plugin.claim(player);verify(missions,times(1)).credit(player);
    }
}
