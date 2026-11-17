package at.vystorm.winter.storage;

import java.nio.file.*;
import java.util.UUID;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class StoreCorruptionTest {
    @TempDir Path folder;

    @ParameterizedTest @ValueSource(strings={"state","calendar","snowball","missions"})
    void blankExistingStoreFailsWithoutOverwriting(String kind) throws Exception {
        Path file=folder.resolve(kind+".json");Files.writeString(file," \n");
        assertThrows(RuntimeException.class,()->open(kind,file));
        assertEquals(" \n",Files.readString(file));
    }

    @ParameterizedTest @ValueSource(strings={"calendar","snowball"})
    void nullLedgerFailsWithoutResetting(String kind) throws Exception {
        Path file=folder.resolve(kind+".json");
        String value="{\"schema\":1,\"data\":{\""+(kind.equals("calendar")?"opened":"players")+"\":null}}";
        Files.writeString(file,value);
        assertThrows(RuntimeException.class,()->open(kind,file));
        assertEquals(value,Files.readString(file));
    }

    @ParameterizedTest @ValueSource(strings={"calendar","snowball"})
    void nullPlayerRowIsRejectedOnLoad(String kind) throws Exception {
        Path file=folder.resolve(kind+".json");
        String value="{\"schema\":1,\"data\":{\""+(kind.equals("calendar")?"opened":"players")+"\":{\""+UUID.randomUUID()+"\":null}}}";
        Files.writeString(file,value);
        assertThrows(RuntimeException.class,()->open(kind,file));
        assertEquals(value,Files.readString(file));
    }

    private Object open(String kind,Path file) {
        return switch(kind) {
            case "state" -> new WinterState(file, Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface.DEFAULT);
            case "calendar" -> new CalendarStore(file, Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface.DEFAULT);
            case "snowball" -> new SnowballScores(file, Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface.DEFAULT);
            default -> new MissionStore(file, Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface.DEFAULT);
        };
    }

    @ParameterizedTest @ValueSource(strings={"[null]","[0]","[25]"})
    void invalidCalendarDaysAreRejectedBeforeMenuAccess(String days) throws Exception {
        Path file=folder.resolve("calendar.json");
        String value="{\"schema\":1,\"data\":{\"year\":2026,\"opened\":{\""+UUID.randomUUID()+"\":"+days+"}}}";
        Files.writeString(file,value);assertThrows(RuntimeException.class,()->new CalendarStore(file, Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface.DEFAULT));
        assertEquals(value,Files.readString(file));
    }
    @ParameterizedTest @ValueSource(strings={"not-a-uuid","1234"})
    void invalidScorePlayerIdIsRejectedBeforeLeaderboardAccess(String id) throws Exception {
        Path file=folder.resolve("scores.json");
        String value="{\"schema\":1,\"data\":{\"season\":2026,\"players\":{\""+id+"\":{\"name\":\"Alex\",\"hits\":1,\"wins\":0}}}}";
        Files.writeString(file,value);assertThrows(RuntimeException.class,()->new SnowballScores(file, Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface.DEFAULT));
        assertEquals(value,Files.readString(file));
    }
}
