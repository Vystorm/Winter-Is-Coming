package at.vystorm.winter;

import Vystorm.vystorm_core.lang.Lang;
import at.vystorm.winter.season.SeasonCalendar;
import at.vystorm.winter.storage.SnowballScores;
import java.nio.file.Path;
import java.util.UUID;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SnowballSurrenderTest {
    @TempDir Path folder;

    @Test void commandEndsDuelAnnouncesBothResultsAndCreditsOnlyOnce() throws Exception {
        WinterPlugin plugin=mock(WinterPlugin.class,CALLS_REAL_METHODS);
        Lang lang=mock(Lang.class);
        var langField=WinterPlugin.class.getDeclaredField("lang");langField.setAccessible(true);langField.set(plugin,lang);
        doReturn(new SeasonCalendar.State(1,"PEAK",2026,"NONE",0)).when(plugin).season();
        doReturn(Logger.getAnonymousLogger()).when(plugin).getLogger();
        SnowballScores scores=new SnowballScores(folder.resolve("snowball.json"), Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface.DEFAULT);
        SnowballFight fight=new SnowballFight(plugin,scores, TestCore.api());
        var fightField=WinterPlugin.class.getDeclaredField("snowball");fightField.setAccessible(true);fightField.set(plugin,fight);
        Player alex=player("Alex"),sam=player("Sam");
        UUID a=alex.getUniqueId(),s=sam.getUniqueId();
        long now=System.currentTimeMillis();
        fight.duels().challenge(a,s,now,10,60_000);
        fight.duels().challenge(s,a,now,10,60_000);
        fight.duels().hitInDuel(a,s);
        try(var bukkit=mockStatic(Bukkit.class)){
            bukkit.when(()->Bukkit.getPlayer(a)).thenReturn(alex);
            bukkit.when(()->Bukkit.getPlayer(s)).thenReturn(sam);
            plugin.commandSurrender(alex);
            verify(lang).send(alex,"snowball-surrender-lost","player","Sam");
            verify(lang).send(sam,"snowball-surrender-won","player","Alex");
            plugin.commandSurrender(alex);
            verify(lang).send(alex,"snowball-no-duel");
            fight.tick();
            assertEquals(1,scores.row(s,2026).wins());
            assertEquals(0,scores.row(a,2026).wins());
            assertTrue(fight.duels().duelOf(s).isEmpty());
        }
    }

    @Test void deniedSurrenderLeavesRunningDuelUntouched() {
        WinterPlugin plugin=mock(WinterPlugin.class);
        Lang lang=mock(Lang.class);when(plugin.lang()).thenReturn(lang);
        SnowballFight fight=new SnowballFight(plugin,new SnowballScores(folder.resolve("scores.json"), Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface.DEFAULT), TestCore.api());
        Player alex=player("Alex"),sam=player("Sam");
        when(alex.hasPermission("winter.snowball")).thenReturn(false);
        fight.duels().challenge(alex.getUniqueId(),sam.getUniqueId(),0,10,60_000);
        fight.duels().challenge(sam.getUniqueId(),alex.getUniqueId(),0,10,60_000);
        fight.surrender(alex);
        assertTrue(fight.duels().duelOf(alex.getUniqueId()).isPresent());
        verify(lang).send(alex,"snowball-denied");
    }

    private static Player player(String name) {
        Player p=mock(Player.class);
        when(p.getUniqueId()).thenReturn(UUID.randomUUID());
        when(p.getName()).thenReturn(name);
        when(p.hasPermission("winter.snowball")).thenReturn(true);
        return p;
    }
}
