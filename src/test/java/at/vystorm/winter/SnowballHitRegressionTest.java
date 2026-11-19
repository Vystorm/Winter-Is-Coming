package at.vystorm.winter;

import Vystorm.vystorm_core._coreMethods.eventRegulator.EventStage;
import Vystorm.vystorm_core._coreMethods.eventRegulator.EventStageTesting;
import Vystorm.vystorm_core.lang.Lang;
import at.vystorm.winter.fun.SnowballDuels;
import at.vystorm.winter.season.SeasonCalendar;
import at.vystorm.winter.storage.SnowballScores;
import at.vystorm.winter.world.WinterCold;
import java.nio.file.Path;
import java.util.UUID;
import java.util.logging.Logger;
import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SnowballHitRegressionTest {
    @TempDir Path folder;
    private final WinterPlugin plugin=mock(WinterPlugin.class);
    private final Player alex=player("Alex"),sam=player("Sam");

    private SnowballFight fight() {
        when(plugin.snowballSettings()).thenReturn(SnowballDuels.Settings.DEFAULTS);
        when(plugin.controls(any())).thenReturn(true);
        when(plugin.winterAt(any())).thenReturn(true);
        when(plugin.season()).thenReturn(new SeasonCalendar.State(1,"WINTER",2026,"NONE",0));
        when(plugin.lang()).thenReturn(mock(Lang.class));
        when(plugin.coldLoop()).thenReturn(mock(WinterCold.class));
        when(plugin.getLogger()).thenReturn(Logger.getAnonymousLogger());
        when(plugin.getName()).thenReturn("WinterIsComing");
        when(plugin.isEnabled()).thenReturn(true);
        return new SnowballFight(plugin,new SnowballScores(folder.resolve("scores.json"), Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface.DEFAULT), TestCore.api());
    }

    @ParameterizedTest @EnumSource(value=GameMode.class,names={"CREATIVE","SPECTATOR"})
    void ineligibleThrowersCannotScoreOrChill(GameMode mode) {
        SnowballFight fight=fight();when(alex.getGameMode()).thenReturn(mode);
        fire(fight,hit());
        assertEquals(0,fight.scores().row(alex.getUniqueId(),2026).hits());
        verifyNoInteractions(plugin.coldLoop());
    }

    @Test void disconnectedThrowerCannotScore() {
        SnowballFight fight=fight();when(alex.isOnline()).thenReturn(false);
        fire(fight,hit());
        assertEquals(0,fight.scores().row(alex.getUniqueId(),2026).hits());
    }

    @ParameterizedTest @EnumSource(value=GameMode.class,names={"SURVIVAL","ADVENTURE"})
    void eligibleHitsStillScoreOncePerPairCooldown(GameMode mode) {
        SnowballFight fight=fight();when(alex.getGameMode()).thenReturn(mode);
        fire(fight,hit());fire(fight,hit());
        assertEquals(1,fight.scores().row(alex.getUniqueId(),2026).hits());
        verify(plugin.coldLoop()).chill(sam,SnowballDuels.Settings.DEFAULTS.chill());
    }

    @Test void hitThatAProtectionCancelledDoesNotScore() {
        SnowballFight fight=fight();
        EventStageTesting stages=EventStageTesting.detached();
        fight.handleDamageEvents(stages.stages());
        EntityDamageByEntityEvent hit=hit();
        stages.handle(EventStageTesting.plugin("PvpProtection"),EntityDamageByEntityEvent.class,EventStage.PROTECT,event->when(hit.isCancelled()).thenReturn(true));
        stages.fire(hit);
        assertEquals(0,fight.scores().row(alex.getUniqueId(),2026).hits());
        verifyNoInteractions(plugin.coldLoop());
    }

    @Test void naturalThawDisablesFights() {
        SnowballFight fight=fight();
        when(plugin.season()).thenReturn(new SeasonCalendar.State(0.5,"THAW",2026,"NONE",0));
        assertFalse(fight.active(alex));
    }

    @Test void hitAfterDeadlineCannotChangeDuelResult() {
        SnowballFight fight=fight();startExpired(fight);
        fight.duels().hitInDuel(alex.getUniqueId(),sam.getUniqueId());
        var duel=fight.duels().duelOf(alex.getUniqueId()).orElseThrow();
        try(var bukkit=mockStatic(Bukkit.class)) {
            bukkit.when(()->Bukkit.getOfflinePlayer(any(UUID.class))).thenReturn(mock(OfflinePlayer.class));
            fire(fight,hit());fight.tick();
            assertEquals(SnowballDuels.End.TIME,duel.end());
            assertEquals(1,duel.score(alex.getUniqueId()));
            assertEquals(0,fight.scores().row(sam.getUniqueId(),2026).wins());
            assertEquals(0,fight.scores().row(alex.getUniqueId(),2026).wins());
            assertTrue(fight.duels().duelOf(alex.getUniqueId()).isEmpty());
        }
    }

    @Test void disconnectAfterDeadlineKeepsTimedWinner() {
        SnowballFight fight=fight();startExpired(fight);
        try(var bukkit=mockStatic(Bukkit.class)) {
            bukkit.when(()->Bukkit.getOfflinePlayer(any(UUID.class))).thenReturn(mock(OfflinePlayer.class));
            PlayerQuitEvent event=mock(PlayerQuitEvent.class);when(event.getPlayer()).thenReturn(sam);
            fight.quit(event);fight.tick();
            assertEquals(1,fight.scores().row(sam.getUniqueId(),2026).wins());
            assertEquals(0,fight.scores().row(alex.getUniqueId(),2026).wins());
        }
    }

    /** Fires the hit through Core's stage dispatcher, the way the server delivers it. */
    private static void fire(SnowballFight fight,EntityDamageByEntityEvent event) {
        EventStageTesting stages=EventStageTesting.detached();
        fight.handleDamageEvents(stages.stages());
        stages.fire(event);
    }
    private void startExpired(SnowballFight fight) {
        long past=System.currentTimeMillis()-10_000;
        fight.duels().challenge(alex.getUniqueId(),sam.getUniqueId(),past,2,1000);
        fight.duels().challenge(sam.getUniqueId(),alex.getUniqueId(),past,2,1000);
        fight.duels().hitInDuel(sam.getUniqueId(),alex.getUniqueId());
    }
    private EntityDamageByEntityEvent hit() {
        Snowball snowball=mock(Snowball.class);when(snowball.getShooter()).thenReturn(alex);
        EntityDamageByEntityEvent event=mock(EntityDamageByEntityEvent.class);
        when(event.getDamager()).thenReturn(snowball);when(event.getEntity()).thenReturn(sam);
        return event;
    }
    private static Player player(String name) {
        Player p=mock(Player.class);when(p.getUniqueId()).thenReturn(UUID.randomUUID());
        when(p.getName()).thenReturn(name);when(p.hasPermission("winter.snowball")).thenReturn(true);
        when(p.isOnline()).thenReturn(true);when(p.getGameMode()).thenReturn(GameMode.SURVIVAL);
        return p;
    }
}
