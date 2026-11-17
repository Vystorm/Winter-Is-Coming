package at.vystorm.winter;

import Vystorm.vystorm_core.item.RewardPouch;
import Vystorm.vystorm_core.lang.Lang;
import at.vystorm.winter.season.AdventCalendar;
import at.vystorm.winter.season.SeasonCalendar;
import at.vystorm.winter.storage.CalendarStore;
import at.vystorm.winter.storage.WinterState;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.MonthDay;
import java.util.*;
import java.util.logging.Logger;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RewardFailureRegressionTest {
    @TempDir Path folder;
    private WinterPlugin plugin;
    private Player player;
    private RewardPouch gifts;
    private Lang lang;

    @BeforeEach void setup() throws Exception {
        plugin=mock(WinterPlugin.class,CALLS_REAL_METHODS);player=mock(Player.class);
        gifts=mock(RewardPouch.class);lang=mock(Lang.class);
        set("gifts",gifts);set("lang",lang);set("missions",mock(CommunityMissions.class));
        set("state",new WinterState(folder.resolve("state.json"), Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface.DEFAULT));
        set("holiday","NONE");
        WinterSettings settings=mock(WinterSettings.class);set("settings",settings);
        when(settings.includes(any())).thenReturn(true);
        doReturn(Logger.getAnonymousLogger()).when(plugin).getLogger();
        when(player.hasPermission(anyString())).thenReturn(true);
        when(player.isOnline()).thenReturn(true);when(player.getUniqueId()).thenReturn(UUID.randomUUID());
    }

    @Test void holidayCreditFailureFaultsPluginWithoutDelivery() throws Exception {
        set("holiday","CHRISTMAS");set("season",new SeasonCalendar.State(1,"WINTER",2026,"CHRISTMAS",0));
        doReturn(LocalDate.of(2026,12,24)).when(plugin).date();
        when(plugin.settings().reward()).thenReturn(Material.COOKIE);
        when(plugin.settings().rewardAmount()).thenReturn(1);
        when(plugin.settings().christmasStart()).thenReturn(MonthDay.of(12,24));
        when(plugin.settings().christmasEnd()).thenReturn(MonthDay.of(12,26));
        doThrow(new IllegalStateException("disk unavailable")).when(gifts).credit(any(),anyString(),any(ItemStack.class));
        try(var items=mockConstruction(ItemStack.class)) {
            assertDoesNotThrow(()->plugin.claim(player));
        }
        assertTrue(plugin.faulted());verify(gifts,never()).claim(any());verify(lang).send(player,"faulted");
    }

    @Test void pouchDeliveryFailureFaultsPlugin() {
        when(gifts.claim(player)).thenThrow(new IllegalStateException("disk unavailable"));
        assertDoesNotThrow(()->plugin.claim(player));
        assertTrue(plugin.faulted());verify(lang).send(player,"faulted");
    }

    @Test void calendarDeliveryFailureFaultsPlugin() throws Exception {
        CalendarMenu menu=calendar();
        when(gifts.claim(player)).thenThrow(new IllegalStateException("disk unavailable"));
        try(var items=mockConstruction(ItemStack.class)) {
            assertDoesNotThrow(()->menu.openDoor(player,1));
        }
        assertTrue(plugin.faulted());verify(lang).send(player,"faulted");
        assertTrue(plugin.calendarStore().opened(player.getUniqueId(),2026).contains(1));
    }

    @Test void calendarStopsDeliveryWhenMissionContributionFaultsPlugin() throws Exception {
        CalendarMenu menu=calendar();
        CommunityMissions missions=plugin.missions();
        when(gifts.claim(player)).thenReturn(RewardPouch.ClaimResult.FULL);
        doAnswer(invocation->{plugin.storageFailure(new IllegalStateException("mission disk failure"));return null;})
                .when(missions).contribute(player,CommunityMissions.Objective.ADVENT_DOOR);
        try(var items=mockConstruction(ItemStack.class)) { menu.openDoor(player,1); }
        verify(gifts,never()).claim(any());
    }

    @Test void delayedDoorClickAfterDisconnectDoesNotOpenOrCredit() throws Exception {
        CalendarMenu menu=calendar();when(player.isOnline()).thenReturn(false);
        when(gifts.claim(player)).thenReturn(RewardPouch.ClaimResult.FULL);
        try(var items=mockConstruction(ItemStack.class)) { menu.openDoor(player,1); }
        verifyNoInteractions(gifts);
        assertTrue(plugin.calendarStore().opened(player.getUniqueId(),2026).isEmpty());
    }

    private CalendarMenu calendar() throws Exception {
        set("calendarSettings",new AdventCalendar.Settings(true,true,true,List.of(new AdventCalendar.Reward(Material.COOKIE,1))));
        set("calendarStore",new CalendarStore(folder.resolve("calendar.json"), Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface.DEFAULT));
        CalendarMenu menu=spy(new CalendarMenu(plugin, TestCore.api()));doReturn(LocalDate.of(2026,12,1)).when(menu).today();return menu;
    }
    private void set(String name,Object value) throws Exception {
        var field=WinterPlugin.class.getDeclaredField(name);field.setAccessible(true);field.set(plugin,value);
    }
}
