package at.vystorm.winter;
import at.vystorm.winter.storage.*;
import at.vystorm.winter.season.SeasonCalendar;
import java.nio.file.Path;
import java.util.*;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CommunityMissionsTest {
    @TempDir Path directory;
    @Test void permissionsAdministrativeDatesAndCreativeCannotMintProgress(){
        WinterPlugin plugin=mock(WinterPlugin.class);Player player=mock(Player.class);World world=mock(World.class);
        var state=new WinterState(directory.resolve("state.json"), Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface.DEFAULT);when(plugin.state()).thenReturn(state);
        when(plugin.season()).thenReturn(new SeasonCalendar.State(1,"WINTER",2026,"NONE",0));
        when(player.getWorld()).thenReturn(world);when(plugin.controls(world)).thenReturn(true);
        when(player.getGameMode()).thenReturn(GameMode.SURVIVAL);
        var definition=new CommunityMissions.Definition("hot",CommunityMissions.Objective.HOT_MEAL,3,"COOKIE",1);
        var store=new MissionStore(directory.resolve("missions.json"), Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface.DEFAULT);
        var missions=new CommunityMissions(plugin,store,new CommunityMissions.Settings(true,List.of(definition)));
        missions.contribute(player,CommunityMissions.Objective.HOT_MEAL);assertEquals(0,store.progress(2026,definition).count());
        when(player.hasPermission("winter.missions")).thenReturn(true);state.data().manualDate="2026-12-20";
        missions.contribute(player,CommunityMissions.Objective.HOT_MEAL);assertEquals(0,store.progress(2026,definition).count());
        state.data().manualDate=null;when(player.getGameMode()).thenReturn(GameMode.CREATIVE);
        missions.contribute(player,CommunityMissions.Objective.HOT_MEAL);assertEquals(0,store.progress(2026,definition).count());
        when(player.getGameMode()).thenReturn(GameMode.SURVIVAL);missions.contribute(player,CommunityMissions.Objective.HOT_MEAL);
        assertEquals(1,store.progress(2026,definition).count());
        missions.configure(new CommunityMissions.Settings(true,List.of(new CommunityMissions.Definition("hot",CommunityMissions.Objective.SNOWBALL_HIT,1,"DIAMOND",64))));
        missions.contribute(player,CommunityMissions.Objective.SNOWBALL_HIT);assertEquals(1,store.progress(2026,definition).count());
        missions.contribute(player,CommunityMissions.Objective.HOT_MEAL);assertEquals(2,store.progress(2026,definition).count());
    }
}
