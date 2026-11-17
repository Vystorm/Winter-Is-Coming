package at.vystorm.winter;

import Vystorm.vystorm_core.lang.Lang;
import Vystorm.vystorm_core.panel.PanelOpenResult;
import Vystorm.vystorm_core.panel.Panels;
import at.vystorm.winter.season.AdventCalendar;
import at.vystorm.winter.season.SeasonClock;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WinterPanelsTest {
    private final WinterPlugin plugin = mock(WinterPlugin.class);
    private final Player player = mock(Player.class);
    private final CalendarMenu calendar = mock(CalendarMenu.class);
    private final WinterAdminMenu admin = mock(WinterAdminMenu.class);
    private final WinterSettings settings = mock(WinterSettings.class);

    @BeforeEach void setup() {
        when(plugin.isEnabled()).thenReturn(true);
        when(plugin.lang()).thenReturn(mock(Lang.class));
        when(plugin.getLogger()).thenReturn(Logger.getAnonymousLogger());
        when(plugin.settings()).thenReturn(settings);
        when(plugin.calendarSettings()).thenReturn(new AdventCalendar.Settings(true, true, false,
                List.of(new AdventCalendar.Reward(Material.COOKIE, 6))));
        when(player.isOnline()).thenReturn(true);
        when(player.hasPermission(anyString())).thenReturn(true);
        when(settings.includes(any())).thenReturn(true);
        when(calendar.today(player)).thenReturn(LocalDate.of(2026, 12, 9));
        when(calendar.opened(player, 2026)).thenReturn(Set.of(1));
    }

    @Test void givenPermissionRevoked_whenActionsArrive_thenNoServiceIsCalled() {
        when(player.hasPermission(anyString())).thenReturn(false);
        var actions = WinterPanels.adminActions(plugin, Vystorm.vystorm_core._uiMethods.settings.ConfigEditorInterface.DEFAULT, admin);
        assertFalse(actions.run(player, "thaw").success());
        assertFalse(actions.date(player, "2026-12-24").success());
        assertFalse(actions.speed(player, 32).success());
        actions.settings(player);
        actions.lamp(player);
        var gifts = WinterPanels.calendarActions(plugin, Vystorm.vystorm_core.compat.PlatformCompatInterface.DEFAULT, calendar);
        assertFalse(gifts.openDoor(player, 9).success());
        verify(plugin, never()).commandThaw(any());
        verify(plugin, never()).commandSetDate(any(), any());
        verify(plugin, never()).lamps();
        verify(admin, never()).applySpeed(any(), anyFloat());
        verify(calendar, never()).openDoor(any(), anyInt());
    }

    @Test void givenStaleOrInvalidDoor_whenOpened_thenNoRewardIsCredited() {
        var actions = WinterPanels.calendarActions(plugin, Vystorm.vystorm_core.compat.PlatformCompatInterface.DEFAULT, calendar);
        for (int day : new int[]{0, 1, 10, 25}) assertFalse(actions.openDoor(player, day).success());
        verify(calendar, never()).openDoor(any(), anyInt());
        assertTrue(actions.openDoor(player, 9).success());
        verify(calendar).openDoor(player, 9);
    }

    @Test void givenCalendarDisabledOrWrongWorld_whenOpened_thenActionIsRejected() {
        var actions = WinterPanels.calendarActions(plugin, Vystorm.vystorm_core.compat.PlatformCompatInterface.DEFAULT, calendar);
        when(settings.includes(any())).thenReturn(false);
        assertFalse(actions.openDoor(player, 9).success());
        when(plugin.calendarSettings()).thenReturn(new AdventCalendar.Settings(false, true, false, List.of()));
        assertFalse(actions.check(player).success());
        actions.gifts(player);
        verify(calendar, never()).openDoor(any(), anyInt());
        verify(plugin, never()).claim(any());
    }

    @Test void givenFaultedOrOfflinePlayer_whenActionArrives_thenItIsRejected() {
        when(plugin.faulted()).thenReturn(true);
        assertFalse(WinterPanels.adminActions(plugin, Vystorm.vystorm_core._uiMethods.settings.ConfigEditorInterface.DEFAULT, admin).run(player, "auto").success());
        assertFalse(WinterPanels.calendarActions(plugin, Vystorm.vystorm_core.compat.PlatformCompatInterface.DEFAULT, calendar).openDoor(player, 9).success());
        when(plugin.faulted()).thenReturn(false);
        when(player.isOnline()).thenReturn(false);
        assertFalse(WinterPanels.adminActions(plugin, Vystorm.vystorm_core._uiMethods.settings.ConfigEditorInterface.DEFAULT, admin).speed(player, 32).success());
        verifyNoInteractions(admin);
    }

    @Test void givenAdminActions_whenValid_thenExistingServicesHandleThem() {
        var actions = WinterPanels.adminActions(plugin, Vystorm.vystorm_core._uiMethods.settings.ConfigEditorInterface.DEFAULT, admin);
        assertTrue(actions.run(player, "auto").success());
        assertTrue(actions.run(player, "thaw").success());
        assertTrue(actions.date(player, "2026-12-24").success());
        assertTrue(actions.speed(player, 32).success());
        verify(plugin).commandResume(player);
        verify(plugin).commandThaw(player);
        verify(plugin).commandSetDate(player, "2026-12-24");
        verify(admin).applySpeed(player, 32);
    }

    @Test void givenInvalidAdminInputOrDisabledSeason_whenSubmitted_thenNoChangeOccurs() {
        var actions = WinterPanels.adminActions(plugin, Vystorm.vystorm_core._uiMethods.settings.ConfigEditorInterface.DEFAULT, admin);
        when(plugin.clock()).thenReturn(new SeasonClock(false, SeasonClock.Mode.REAL, LocalDate.of(2026, 1, 1)));
        assertFalse(actions.run(player, "next").success());
        assertFalse(actions.run(player, "unknown").success());
        assertFalse(actions.date(player, "2026-02-30").success());
        for (double speed : new double[]{Double.NaN, Double.POSITIVE_INFINITY, 3, 513}) assertFalse(actions.speed(player, speed).success());
        verify(plugin, never()).commandSetDate(any(), any());
        verify(plugin, never()).nextSeason(any());
        verifyNoInteractions(admin);
    }

    @Test void givenPanelOpenResults_whenOpening_thenOnlyUnsuccessfulResultsRequestChestFallback() {
        Panels panels = mock(Panels.class);
        for (PanelOpenResult result : PanelOpenResult.values()) {
            when(panels.open(player, CalendarPanel.NAME)).thenReturn(result);
            assertEquals(result != PanelOpenResult.NONE, WinterPanels.open(plugin, panels, player, CalendarPanel.NAME));
        }
        when(panels.open(player, CalendarPanel.NAME)).thenThrow(new IllegalStateException("unavailable"));
        assertFalse(WinterPanels.open(plugin, panels, player, CalendarPanel.NAME));
    }

    @Test void givenMenuOpeners_whenPanelsUnavailable_thenBothOriginalChestsRemainReachable() {
        CalendarMenu calendarMenu = spy(new CalendarMenu(plugin, TestCore.api()));
        WinterAdminMenu adminMenu = spy(new WinterAdminMenu(plugin, TestCore.api()));
        doNothing().when(calendarMenu).openChest(player);
        doNothing().when(adminMenu).openChest(player);
        calendarMenu.open(player);
        adminMenu.open(player);
        verify(calendarMenu).openChest(player);
        verify(adminMenu).openChest(player);
        calendarMenu.panelOpener(p -> true);
        adminMenu.panelOpener(p -> true);
        calendarMenu.open(player);
        adminMenu.open(player);
        verify(calendarMenu, times(1)).openChest(player);
        verify(adminMenu, times(1)).openChest(player);
    }
}
