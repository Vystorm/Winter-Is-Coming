package at.vystorm.winter.storage;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SnowballScoresTest {
    @TempDir Path tempDir;
    private final UUID alex=UUID.randomUUID(),sam=UUID.randomUUID(),kim=UUID.randomUUID();

    @Test void leaderboardOrdersByHitsThenWins() {
        SnowballScores scores=new SnowballScores(tempDir.resolve("snowball.json"), Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface.DEFAULT);
        for(int i=0;i<3;i++)scores.hit(alex,"Alex",2026);
        for(int i=0;i<3;i++)scores.hit(sam,"Sam",2026);
        scores.win(sam,"Sam",2026);
        scores.hit(kim,"Kim",2026);
        var top=scores.top(5,2026);
        assertEquals("Sam",top.get(0).name());
        assertEquals("Alex",top.get(1).name());
        assertEquals("Kim",top.get(2).name());
        assertEquals(1,scores.rank(sam,2026));
        assertEquals(3,scores.rank(kim,2026));
        assertEquals(2,scores.top(2,2026).size());
        assertEquals(0,scores.rank(UUID.randomUUID(),2026));
    }

    @Test void aNewSeasonStartsEmpty() {
        SnowballScores scores=new SnowballScores(tempDir.resolve("snowball.json"), Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface.DEFAULT);
        scores.hit(alex,"Alex",2026);
        assertTrue(scores.top(5,2027).isEmpty());
        assertEquals(0,scores.row(alex,2027).hits());
        scores.hit(sam,"Sam",2027);
        assertEquals(1,scores.top(5,2027).size());
        assertEquals(0,scores.row(alex,2026).hits(),"only the current season is kept");
    }

    @Test void changesAreWrittenOnlyWhenSaved() throws Exception {
        Path path=tempDir.resolve("snowball.json");
        SnowballScores scores=new SnowballScores(path, Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface.DEFAULT);
        scores.hit(alex,"Alex",2026);scores.win(alex,"Alex",2026);
        assertEquals(0,new SnowballScores(path, Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface.DEFAULT).row(alex,2026).hits(),"hits stay in memory until saved");
        scores.saveIfDirty();
        var row=new SnowballScores(path, Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface.DEFAULT).row(alex,2026);
        assertEquals(1,row.hits());assertEquals(1,row.wins());assertEquals("Alex",row.name());
    }
}
