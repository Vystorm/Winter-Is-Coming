package at.vystorm.winter;

import at.vystorm.winter.storage.SnowballScores;
import at.vystorm.winter.storage.WinterState;
import java.time.*;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class StorageFailureLifecycleTest {
    @Test void reloadDoesNotRestartTasksAfterScoreSaveFault() throws Exception {
        WinterPlugin plugin=mock(WinterPlugin.class,CALLS_REAL_METHODS);
        doReturn(Logger.getAnonymousLogger()).when(plugin).getLogger();doNothing().when(plugin).reloadConfig();
        var config=new org.bukkit.configuration.file.YamlConfiguration();doReturn(config).when(plugin).getConfig();
        var lang=mock(Vystorm.vystorm_core.lang.Lang.class);set(plugin,"lang",lang);
        var sender=mock(org.bukkit.command.CommandSender.class);when(sender.hasPermission("winter.admin")).thenReturn(true);
        var tasks=mock(Vystorm.vystorm_core.schedule.TaskGroup.class);set(plugin,"tasks",tasks);
        var weather=mock(at.vystorm.winter.world.WinterWeather.class);set(plugin,"weather",weather);
        var fight=mock(SnowballFight.class);set(plugin,"snowball",fight);
        doAnswer(invocation->{plugin.storageFailure(new IllegalStateException("disk full"));return null;}).when(fight).save();
        WinterSettings original=mock(WinterSettings.class);set(plugin,"settings",original);
        try(var settings=mockStatic(WinterSettings.class);
            var calendar=mockStatic(at.vystorm.winter.season.AdventCalendar.Settings.class);
            var missions=mockStatic(CommunityMissions.Settings.class)) {
            settings.when(()->WinterSettings.read(config)).thenReturn(mock(WinterSettings.class));
            plugin.commandReload(sender);
            assertTrue(plugin.faulted());assertSame(original,plugin.settings());assertEquals(0,plugin.generation());
            verify(tasks,never()).timer(anyLong(),anyLong(),any());verify(lang).send(sender,"faulted");
        }
    }
    @Test void scoreSaveFailureStopsWinterWork() {
        WinterPlugin plugin=mock(WinterPlugin.class);when(plugin.getLogger()).thenReturn(Logger.getAnonymousLogger());
        SnowballScores scores=mock(SnowballScores.class);var failure=new IllegalStateException("disk full");
        doThrow(failure).when(scores).saveIfDirty();
        new SnowballFight(plugin,scores, TestCore.api()).save();verify(plugin).storageFailure(failure);
    }
    @Test void newYearReceiptFailureStopsWinterWork() throws Exception {
        WinterPlugin plugin=mock(WinterPlugin.class,CALLS_REAL_METHODS);
        doReturn(Logger.getAnonymousLogger()).when(plugin).getLogger();
        WinterSettings settings=mock(WinterSettings.class);when(settings.zone()).thenReturn(ZoneOffset.UTC);
        when(settings.newYear()).thenReturn(true);
        WinterState state=mock(WinterState.class);when(state.data()).thenReturn(new WinterState.Data());
        when(state.celebrate(2027)).thenThrow(new IllegalStateException("disk full"));
        set(plugin,"settings",settings);set(plugin,"state",state);
        set(plugin,"previousClock",ZonedDateTime.parse("2026-12-31T23:59:59Z"));
        ZonedDateTime now=ZonedDateTime.parse("2027-01-01T00:00:00Z");
        var celebration=WinterPlugin.class.getDeclaredMethod("celebration");celebration.setAccessible(true);
        try(var clock=mockStatic(ZonedDateTime.class,CALLS_REAL_METHODS)) {
            clock.when(()->ZonedDateTime.now(ZoneOffset.UTC)).thenReturn(now);
            assertDoesNotThrow(()->celebration.invoke(plugin));assertTrue(plugin.faulted());
        }
    }
    private void set(WinterPlugin plugin,String name,Object value) throws Exception {
        var field=WinterPlugin.class.getDeclaredField(name);field.setAccessible(true);field.set(plugin,value);
    }
}
