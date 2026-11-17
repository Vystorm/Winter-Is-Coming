package at.vystorm.winter;

import at.vystorm.winter.storage.MissionStore;
import Vystorm.vystorm_core.storage.VersionedStore;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static at.vystorm.winter.CommunityMissions.Objective.*;

class MissionStoreTest {
    static class Memory implements VersionedStore.Backend {
        String raw;boolean fail;int writes;
        public String read(){return raw;}
        public void write(String text){if(fail)throw new IllegalStateException("disk full");raw=text;writes++;}
    }
    private final CommunityMissions.Definition goal=new CommunityMissions.Definition("supper",HOT_MEAL,2,"COOKIE",4);
    @Test void restartKeepsPartialProgressAndCompletionIsIdempotent(){
        Memory backend=new Memory();MissionStore store=new MissionStore(backend);
        assertFalse(store.advance(2026,goal));store=new MissionStore(backend);
        assertEquals(1,store.progress(2026,goal).count());assertTrue(store.advance(2026,goal));
        int writes=backend.writes;assertFalse(store.advance(2026,goal));assertEquals(writes,backend.writes);
        var completed=new MissionStore(backend).completed(2026);
        assertEquals(1,completed.size());assertEquals("community/2026/supper",completed.getFirst().rewardId());
        assertTrue(store.completed(2027).isEmpty());
    }
    @Test void reloadCannotChangeFrozenGoalObjectiveOrReward(){
        Memory backend=new Memory();MissionStore store=new MissionStore(backend);store.advance(2026,goal);
        var changed=new CommunityMissions.Definition("supper",SNOWBALL_HIT,1,"DIAMOND",64);
        assertEquals(goal,store.progress(2026,changed).definition());assertTrue(store.advance(2026,changed));
        assertEquals(goal,new MissionStore(backend).completed(2026).getFirst().definition());
    }
    @Test void failedSaveDoesNotAdvanceOrLosePreviousDiskState(){
        Memory backend=new Memory();MissionStore store=new MissionStore(backend);store.advance(2026,goal);
        String before=backend.raw;backend.fail=true;
        assertThrows(IllegalStateException.class,()->store.advance(2026,goal));
        assertEquals(before,backend.raw);assertEquals(1,store.progress(2026,goal).count());
        backend.fail=false;assertTrue(store.advance(2026,goal));
    }
    @Test void corruptedAndFutureStoresAreRetained(){
        Memory b=new Memory();b.raw="broken";assertThrows(RuntimeException.class,()->new MissionStore(b));assertEquals("broken",b.raw);
        b.raw="{\"schema\":99,\"data\":{}}";assertThrows(RuntimeException.class,()->new MissionStore(b));assertEquals(0,b.writes);
    }
    @Test void seasonsHaveDistinctStableReceiptsAndOldProgressIsBounded(){
        Memory b=new Memory();MissionStore store=new MissionStore(b);
        for(int year=2020;year<=2030;year++){store.advance(year,goal);store.advance(year,goal);}
        assertTrue(store.completed(2020).isEmpty());assertEquals("community/2030/supper",store.completed(2030).getFirst().rewardId());
        assertNotEquals(store.completed(2030).getFirst().rewardId(),store.completed(2029).getFirst().rewardId());
    }
}
