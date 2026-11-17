package at.vystorm.winter.storage;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CalendarStoreTest {
    @TempDir Path tempDir;
    private final UUID alex=UUID.randomUUID(),sam=UUID.randomUUID();

    @Test void openedDoorsSurviveARestart() {
        Path path=tempDir.resolve("calendar.json");
        CalendarStore store=new CalendarStore(path, Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface.DEFAULT);
        assertEquals(Set.of(),store.opened(alex,2026));
        store.open(alex,2026,1);store.open(alex,2026,3);store.open(sam,2026,1);
        CalendarStore reopened=new CalendarStore(path, Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface.DEFAULT);
        assertEquals(Set.of(1,3),reopened.opened(alex,2026));
        assertEquals(Set.of(1),reopened.opened(sam,2026));
    }

    @Test void aNewYearStartsWithAClosedCalendar() {
        CalendarStore store=new CalendarStore(tempDir.resolve("calendar.json"), Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface.DEFAULT);
        store.open(alex,2026,24);
        assertEquals(Set.of(),store.opened(alex,2027));
        store.open(sam,2027,1);
        assertEquals(Set.of(),store.opened(alex,2026),"only the current December is kept");
        assertEquals(Set.of(1),store.opened(sam,2027));
    }

    @Test void returnedSetsAreSnapshots() {
        CalendarStore store=new CalendarStore(tempDir.resolve("calendar.json"), Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface.DEFAULT);
        store.open(alex,2026,2);
        assertThrows(UnsupportedOperationException.class,()->store.opened(alex,2026).add(9));
    }
}
